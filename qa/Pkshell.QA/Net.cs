using System.Diagnostics;
using System.Net.Security;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Pkshell.QA;

internal static class Pin
{
    public static string Sha256Hex(byte[] der) => Convert.ToHexString(SHA256.HashData(der)).ToLowerInvariant();

    /// <summary>Accepts the server cert only when SHA-256(DER) equals the pinned fingerprint (DESIGN §2). CA/hostname are ignored.</summary>
    public static RemoteCertificateValidationCallback Callback(string fp) =>
        (_, cert, _, _) => cert != null && string.Equals(Sha256Hex(cert.GetRawCertData()), fp, StringComparison.OrdinalIgnoreCase);
}

internal sealed record ApiResult(int Status, string Body, JsonNode? Json)
{
    public bool Ok => Status is >= 200 and < 300;
    public override string ToString() => $"{Status} {Util.Trunc(Body, 160)}";
}

/// <summary>HTTPS REST client bound to one host IP, pinned to the host cert fingerprint.</summary>
internal sealed class Api : IDisposable
{
    private readonly HttpClient _http;
    public string Host { get; }

    public Api(string host, int port, string fp)
    {
        Host = host;
        var handler = new SocketsHttpHandler
        {
            SslOptions = new SslClientAuthenticationOptions { RemoteCertificateValidationCallback = Pin.Callback(fp) },
            ConnectTimeout = TimeSpan.FromSeconds(5),
            PooledConnectionLifetime = TimeSpan.FromSeconds(30),
        };
        _http = new HttpClient(handler) { BaseAddress = new Uri($"https://{host}:{port}"), Timeout = TimeSpan.FromSeconds(15) };
    }

    public async Task<ApiResult> Call(HttpMethod method, string path, string? token, object? body = null)
    {
        try
        {
            using var req = new HttpRequestMessage(method, path);
            if (token != null) req.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", token);
            if (body != null) req.Content = new StringContent(JsonSerializer.Serialize(body), Encoding.UTF8, "application/json");
            using var resp = await _http.SendAsync(req);
            var text = await resp.Content.ReadAsStringAsync();
            JsonNode? json = null;
            if (text.Length > 0)
            {
                try { json = JsonNode.Parse(text); }
                catch (JsonException) { json = null; }
            }
            return new ApiResult((int)resp.StatusCode, text, json);
        }
        catch (Exception e) when (e is HttpRequestException or TaskCanceledException or IOException)
        {
            return new ApiResult(-1, $"{e.GetType().Name}: {Util.AllMessages(e)}", null);
        }
    }

    public Task<ApiResult> Get(string path, string? token) => Call(HttpMethod.Get, path, token);
    public Task<ApiResult> Post(string path, string? token, object? body = null) => Call(HttpMethod.Post, path, token, body ?? new { });
    public Task<ApiResult> Delete(string path, string? token) => Call(HttpMethod.Delete, path, token);

    public void Dispose() => _http.Dispose();
}

/// <summary>WSS client that records every binary byte (with frame timestamps) and every JSON text control message.</summary>
internal sealed class WsClient : IAsyncDisposable
{
    private readonly ClientWebSocket _ws;
    private readonly object _lk = new();
    private readonly MemoryStream _data = new();
    private readonly SemaphoreSlim _sendLock = new(1, 1);
    private readonly List<(long Off, int Len, long Ts)> _frames = new();
    private readonly List<(long Off, string Text, JsonNode? Json)> _texts = new();
    private long _lastFrameTs = Stopwatch.GetTimestamp();
    private Task? _loop;

    public string Url { get; }
    public long ReplayEndOffset { get; private set; } = -1;
    public bool Closed { get; private set; }
    public int? CloseCode { get; private set; }
    public string? CloseDesc { get; private set; }

    private WsClient(ClientWebSocket ws, string url)
    {
        _ws = ws;
        Url = url;
    }

    public static async Task<(WsClient? Client, int HttpStatus, string Error)> TryConnect(string url, string fp, int timeoutMs = 10000)
    {
        var ws = new ClientWebSocket();
        ws.Options.RemoteCertificateValidationCallback = Pin.Callback(fp);
        ws.Options.CollectHttpResponseDetails = true;
        using var cts = new CancellationTokenSource(timeoutMs);
        try
        {
            await ws.ConnectAsync(new Uri(url), cts.Token);
        }
        catch (Exception e) when (e is WebSocketException or OperationCanceledException or HttpRequestException or IOException)
        {
            int status = (int)ws.HttpStatusCode;
            ws.Dispose();
            return (null, status, $"{e.GetType().Name}: {Util.AllMessages(e)}");
        }
        var c = new WsClient(ws, url);
        c._loop = Task.Run(c.ReceiveLoop);
        return (c, 101, "");
    }

    public static async Task<WsClient> Connect(string url, string fp, int timeoutMs = 10000)
    {
        var (c, status, err) = await TryConnect(url, fp, timeoutMs);
        if (c == null) throw new InvalidOperationException($"WS connect failed (HTTP {status}) {Util.RedactToken(url)}: {err}");
        return c;
    }

    private async Task ReceiveLoop()
    {
        var tmp = new byte[65536];
        var msg = new MemoryStream();
        try
        {
            while (true)
            {
                var r = await _ws.ReceiveAsync(new ArraySegment<byte>(tmp), CancellationToken.None);
                if (r.MessageType == WebSocketMessageType.Close)
                {
                    CloseCode = (int?)r.CloseStatus ?? (int?)_ws.CloseStatus;
                    CloseDesc = r.CloseStatusDescription;
                    try
                    {
                        using var cts = new CancellationTokenSource(2000);
                        await _ws.CloseOutputAsync(WebSocketCloseStatus.NormalClosure, "", cts.Token);
                    }
                    catch (Exception e) when (e is WebSocketException or OperationCanceledException or InvalidOperationException) { }
                    break;
                }
                msg.Write(tmp, 0, r.Count);
                if (!r.EndOfMessage) continue;
                var payload = msg.ToArray();
                msg.SetLength(0);
                long ts = Stopwatch.GetTimestamp();
                lock (_lk)
                {
                    if (r.MessageType == WebSocketMessageType.Binary)
                    {
                        _frames.Add((_data.Length, payload.Length, ts));
                        _data.Write(payload, 0, payload.Length);
                        _lastFrameTs = ts;
                    }
                    else
                    {
                        var text = Encoding.UTF8.GetString(payload);
                        JsonNode? json = null;
                        try { json = JsonNode.Parse(text); }
                        catch (JsonException) { json = null; }
                        _texts.Add((_data.Length, text, json));
                        if (ReplayEndOffset < 0 && json?["type"]?.ToString() == "replay-end") ReplayEndOffset = _data.Length;
                    }
                }
            }
        }
        catch (Exception e) when (e is WebSocketException or ObjectDisposedException or OperationCanceledException or IOException or InvalidOperationException)
        {
            CloseDesc ??= e.Message;
        }
        finally
        {
            Closed = true;
        }
    }

    public long Length { get { lock (_lk) return _data.Length; } }
    public int TextCount { get { lock (_lk) return _texts.Count; } }

    public byte[] Snapshot()
    {
        lock (_lk) return _data.ToArray();
    }

    public byte[] Slice(long from, long to = -1)
    {
        lock (_lk)
        {
            long end = to < 0 ? _data.Length : Math.Min(to, _data.Length);
            if (from >= end) return Array.Empty<byte>();
            return _data.GetBuffer().AsSpan((int)from, (int)(end - from)).ToArray();
        }
    }

    public long IndexOf(byte[] needle, long from = 0)
    {
        lock (_lk)
        {
            if (from >= _data.Length) return -1;
            int i = _data.GetBuffer().AsSpan((int)from, (int)(_data.Length - from)).IndexOf(needle);
            return i < 0 ? -1 : from + i;
        }
    }

    public long IndexOf(string s, long from = 0) => IndexOf(Encoding.UTF8.GetBytes(s), from);

    /// <summary>Timestamp (Stopwatch ticks) of the frame that contains byte offset <paramref name="off"/>.</summary>
    public long FrameTsAt(long off)
    {
        lock (_lk)
        {
            foreach (var f in _frames)
                if (off >= f.Off && off < f.Off + f.Len) return f.Ts;
            return -1;
        }
    }

    public List<JsonNode> TextsOfType(string type, int fromIndex = 0)
    {
        lock (_lk)
            return _texts.Skip(fromIndex).Where(t => t.Json?["type"]?.ToString() == type).Select(t => t.Json!).ToList();
    }

    public async Task<long> WaitIndex(string s, int ms, long from = 0)
    {
        var needle = Encoding.UTF8.GetBytes(s);
        var sw = Stopwatch.StartNew();
        while (true)
        {
            long i = IndexOf(needle, from);
            if (i >= 0) return i;
            if (sw.ElapsedMilliseconds >= ms) return -1;
            await Task.Delay(10);
        }
    }

    public async Task<bool> WaitContains(string s, int ms, long from = 0) => await WaitIndex(s, ms, from) >= 0;

    public async Task<JsonNode?> WaitText(string type, int ms, int fromIndex = 0, Func<JsonNode, bool>? pred = null)
    {
        var sw = Stopwatch.StartNew();
        while (true)
        {
            var hit = TextsOfType(type, fromIndex).FirstOrDefault(j => pred == null || pred(j));
            if (hit != null) return hit;
            if (sw.ElapsedMilliseconds >= ms) return null;
            await Task.Delay(20);
        }
    }

    public async Task<bool> WaitReplayEnd(int ms)
    {
        var sw = Stopwatch.StartNew();
        while (ReplayEndOffset < 0)
        {
            if (sw.ElapsedMilliseconds >= ms || Closed) return ReplayEndOffset >= 0;
            await Task.Delay(10);
        }
        return true;
    }

    /// <summary>Waits until no binary frame has arrived for <paramref name="quietMs"/> (bounded by <paramref name="maxMs"/>).</summary>
    public async Task WaitQuiet(int quietMs, int maxMs)
    {
        var sw = Stopwatch.StartNew();
        long start = Stopwatch.GetTimestamp();
        while (sw.ElapsedMilliseconds < maxMs)
        {
            long last;
            lock (_lk) last = Math.Max(_lastFrameTs, start);
            if ((Stopwatch.GetTimestamp() - last) * 1000 / Stopwatch.Frequency >= quietMs) return;
            await Task.Delay(20);
        }
    }

    public async Task<bool> WaitClosed(int ms)
    {
        var sw = Stopwatch.StartNew();
        while (!Closed)
        {
            if (sw.ElapsedMilliseconds >= ms) return false;
            await Task.Delay(20);
        }
        return true;
    }

    public Task Send(string input) => SendRaw(Encoding.UTF8.GetBytes(input), WebSocketMessageType.Binary);

    public Task SendJson(object o) => SendRaw(Encoding.UTF8.GetBytes(JsonSerializer.Serialize(o)), WebSocketMessageType.Text);

    private async Task SendRaw(byte[] data, WebSocketMessageType type)
    {
        await _sendLock.WaitAsync();
        try
        {
            using var cts = new CancellationTokenSource(5000);
            await _ws.SendAsync(new ArraySegment<byte>(data), type, true, cts.Token);
        }
        finally
        {
            _sendLock.Release();
        }
    }

    public async ValueTask DisposeAsync()
    {
        try
        {
            if (_ws.State == WebSocketState.Open)
            {
                using var cts = new CancellationTokenSource(2000);
                await _ws.CloseOutputAsync(WebSocketCloseStatus.NormalClosure, "qa done", cts.Token);
            }
            // Let the receive loop observe the server's close reply so the server sees a clean disconnect.
            if (_loop != null) await Task.WhenAny(_loop, Task.Delay(2000));
        }
        catch (Exception e) when (e is WebSocketException or OperationCanceledException or ObjectDisposedException or InvalidOperationException) { }
        _ws.Abort();
        if (_loop != null) await Task.WhenAny(_loop, Task.Delay(1000));
        _ws.Dispose();
    }
}
