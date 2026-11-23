using System.Collections.Concurrent;
using System.Net;
using System.Net.Http.Headers;
using System.Net.Security;
using System.Net.Sockets;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Http.Features;
using Microsoft.AspNetCore.Server.Kestrel.Core;
using Microsoft.AspNetCore.Server.Kestrel.Https;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Pkshell.Core.Util;

namespace Pkshell.Server;

/// <summary>
/// Web view for the browser client (0.2.132+): a dev server on this PC's localhost, opened from a phone's browser.
/// A browser page cannot route a frame through a proxy as the phone app's WebView does, so each localhost port gets
/// its own HTTPS listener here (same addresses and certificate as the host; port = 20000 + the dev server's port when
/// free) that forwards every request to http://localhost:port. A whole origin per dev server keeps absolute paths,
/// cookies and the dev server's live reload (WebSocket) working without rewriting pages. The Host and Origin headers
/// are set to localhost:port, as dev servers only accept those.
///
/// Access: POST /api/web/open (device token) sets an HttpOnly cookie on the host name; cookies are not bound to a
/// port, so the forwarder sees it too. A request without a valid cookie of an active device gets 401.
/// </summary>
public sealed class WebForward : IAsyncDisposable
{
    public const string CookieName = "pkshell_web";
    const int PortOffset = 20000;

    readonly Func<IReadOnlyList<IPAddress>> _binds;
    readonly Func<string?, SslStreamCertificateContext> _cert;
    readonly Func<string, bool> _deviceActive;
    readonly int _hostPort;
    readonly ConcurrentDictionary<string, string> _secrets = new(StringComparer.Ordinal); // cookie value → device id
    readonly ConcurrentDictionary<int, Forwarder> _open = new();
    readonly SemaphoreSlim _gate = new(1, 1);
    readonly HttpClient _http = new(new SocketsHttpHandler
    {
        UseProxy = false,
        UseCookies = false,
        AllowAutoRedirect = false,
        AutomaticDecompression = DecompressionMethods.None,
        ConnectTimeout = TimeSpan.FromSeconds(5),
        ConnectCallback = async (c, ct) => new NetworkStream(await ConnectLocalAsync(c.DnsEndPoint.Port, ct), ownsSocket: true),
        PooledConnectionIdleTimeout = TimeSpan.FromSeconds(30),
    }) { Timeout = Timeout.InfiniteTimeSpan };

    sealed record Forwarder(int Target, int Port, WebApplication App, List<IPAddress> Binds);

    public WebForward(Func<IReadOnlyList<IPAddress>> binds, Func<string?, SslStreamCertificateContext> cert,
        Func<string, bool> deviceActive, int hostPort)
    {
        _binds = binds;
        _cert = cert;
        _deviceActive = deviceActive;
        _hostPort = hostPort;
    }

    /// <summary>The cookie value for a device (one per device per host run).</summary>
    public string SecretFor(string deviceId)
    {
        foreach (var kv in _secrets)
            if (kv.Value == deviceId) return kv.Key;
        var s = Convert.ToBase64String(RandomNumberGenerator.GetBytes(32)).TrimEnd('=').Replace('+', '-').Replace('/', '_');
        _secrets[s] = deviceId;
        return s;
    }

    /// <summary>Starts (or reuses) the listener for localhost:<paramref name="target"/>; returns its port.</summary>
    public async Task<int> OpenAsync(int target)
    {
        await _gate.WaitAsync();
        try
        {
            var binds = _binds().ToList();
            if (_open.TryGetValue(target, out var f))
            {
                if (SameSet(f.Binds, binds)) return f.Port;
                await StopAsync(f); // the PC's addresses changed: listen on the new ones
            }
            int want = target + PortOffset;
            var candidates = new List<int>();
            if (want <= 65535 && want != _hostPort) candidates.Add(want);
            candidates.Add(0);
            Exception? last = null;
            foreach (var c in candidates)
            {
                int port = c == 0 ? FreePort() : c;
                var app = Build(target, port, binds);
                try
                {
                    await app.StartAsync();
                    _open[target] = new Forwarder(target, port, app, binds);
                    Log.Info($"web view: localhost:{target} on port {port}");
                    return port;
                }
                catch (IOException ex)
                {
                    last = ex;
                    await app.DisposeAsync();
                }
            }
            throw new IOException($"no port for localhost:{target}: {last?.Message}");
        }
        finally { _gate.Release(); }
    }

    public async Task StopAllAsync()
    {
        await _gate.WaitAsync();
        try
        {
            foreach (var f in _open.Values.ToList()) await StopAsync(f);
        }
        finally { _gate.Release(); }
    }

    async Task StopAsync(Forwarder f)
    {
        _open.TryRemove(f.Target, out _);
        try { await f.App.StopAsync(TimeSpan.FromSeconds(2)); }
        catch (Exception ex) when (ex is OperationCanceledException or ObjectDisposedException) { /* stopping */ }
        await f.App.DisposeAsync();
    }

    public async ValueTask DisposeAsync()
    {
        await StopAllAsync();
        _http.Dispose();
    }

    static bool SameSet(List<IPAddress> a, List<IPAddress> b) => a.Count == b.Count && a.All(b.Contains);

    static int FreePort()
    {
        var l = new TcpListener(IPAddress.Any, 0);
        l.Start();
        int p = ((IPEndPoint)l.LocalEndpoint).Port;
        l.Stop();
        return p;
    }

    WebApplication Build(int target, int port, List<IPAddress> binds)
    {
        var builder = WebApplication.CreateSlimBuilder(new WebApplicationOptions { Args = Array.Empty<string>(), ContentRootPath = AppContext.BaseDirectory });
        builder.Logging.ClearProviders();
        builder.WebHost.ConfigureKestrel(k =>
        {
            k.Limits.MaxRequestBodySize = null; // uploads to the dev server
            foreach (var ip in binds)
                k.Listen(ip, port, lo =>
                {
                    lo.Protocols = HttpProtocols.Http1AndHttp2; // a dev server's hundreds of module files: one connection
                    lo.UseHttps(new TlsHandshakeCallbackOptions
                    {
                        OnConnection = c => ValueTask.FromResult(new SslServerAuthenticationOptions
                        {
                            ServerCertificateContext = _cert(c.ClientHelloInfo.ServerName),
                            ApplicationProtocols = new List<SslApplicationProtocol> { SslApplicationProtocol.Http2, SslApplicationProtocol.Http11 },
                        }),
                    });
                });
        });
        var app = builder.Build();
        app.UseWebSockets(new WebSocketOptions { KeepAliveInterval = TimeSpan.FromSeconds(20) });
        app.Run(ctx => Handle(ctx, target));
        return app;
    }

    bool Allowed(HttpContext ctx)
    {
        var v = ctx.Request.Cookies[CookieName];
        return v != null && _secrets.TryGetValue(v, out var device) && _deviceActive(device);
    }

    async Task Handle(HttpContext ctx, int target)
    {
        if (!Allowed(ctx))
        {
            await Page(ctx, 401, "PocketShell 웹 보기에서 다시 열기", "이 주소는 PocketShell 웹 앱의 '웹 보기'에서 열어야 함.");
            return;
        }
        var raw = ctx.Features.Get<IHttpRequestFeature>()?.RawTarget;
        if (string.IsNullOrEmpty(raw) || raw[0] != '/') raw = ctx.Request.Path.Value + ctx.Request.QueryString.Value;
        if (ctx.WebSockets.IsWebSocketRequest)
        {
            await RelayWebSocket(ctx, target, raw);
            return;
        }
        var origin = $"http://localhost:{target}";
        using var req = new HttpRequestMessage(new HttpMethod(ctx.Request.Method), origin + raw);
        bool body = ctx.Features.Get<IHttpRequestBodyDetectionFeature>()?.CanHaveBody ?? ctx.Request.ContentLength > 0;
        if (body)
        {
            req.Content = new StreamContent(ctx.Request.Body);
            if (ctx.Request.ContentLength is long len) req.Content.Headers.ContentLength = len;
        }
        foreach (var h in ctx.Request.Headers)
        {
            if (SkipRequest(h.Key)) continue;
            string[] values = h.Value.Where(v => v != null).Select(v => v!).ToArray();
            if (h.Key.Equals("Cookie", StringComparison.OrdinalIgnoreCase)) values = new[] { WithoutOurCookie(string.Join("; ", values)) };
            if (h.Key.Equals("Origin", StringComparison.OrdinalIgnoreCase)) values = new[] { origin };
            if (h.Key.Equals("Referer", StringComparison.OrdinalIgnoreCase)) values = values.Select(v => ToLocal(v, target)).ToArray();
            if (values.Length == 0 || (values.Length == 1 && values[0].Length == 0)) continue;
            if (!req.Headers.TryAddWithoutValidation(h.Key, values) && req.Content != null)
                req.Content.Headers.TryAddWithoutValidation(h.Key, values);
        }
        req.Headers.Host = $"localhost:{target}";
        HttpResponseMessage resp;
        try
        {
            resp = await _http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, ctx.RequestAborted);
        }
        catch (HttpRequestException ex)
        {
            await Page(ctx, 502, $"localhost:{target} 응답 없음", $"PC에서 이 포트로 실행 중인 개발 서버가 없음. 서버를 켠 뒤 새로 고침. ({ex.Message})");
            return;
        }
        catch (OperationCanceledException) { return; } // the browser went away
        using (resp)
        {
            ctx.Response.StatusCode = (int)resp.StatusCode;
            CopyResponseHeaders(resp.Headers, ctx, target);
            CopyResponseHeaders(resp.Content.Headers, ctx, target);
            try
            {
                await using var s = await resp.Content.ReadAsStreamAsync(ctx.RequestAborted);
                await s.CopyToAsync(ctx.Response.Body, ctx.RequestAborted);
            }
            catch (Exception ex) when (ex is IOException or OperationCanceledException or HttpRequestException) { /* either side closed */ }
        }
    }

    static readonly HashSet<string> HopByHop = new(StringComparer.OrdinalIgnoreCase)
    {
        "Host", "Connection", "Keep-Alive", "Transfer-Encoding", "TE", "Trailer", "Upgrade", "Proxy-Connection",
        "Proxy-Authorization", "Proxy-Authenticate", "Content-Length",
    };

    static bool SkipRequest(string name) => HopByHop.Contains(name) || name.StartsWith(':');

    void CopyResponseHeaders(HttpHeaders headers, HttpContext ctx, int target)
    {
        foreach (var h in headers)
        {
            var name = h.Key;
            if (name.Equals("Content-Length", StringComparison.OrdinalIgnoreCase))
            {
                if (long.TryParse(h.Value.FirstOrDefault(), out var len)) ctx.Response.ContentLength = len;
                continue;
            }
            if (HopByHop.Contains(name)) continue;
            if (name.Equals("X-Frame-Options", StringComparison.OrdinalIgnoreCase)) continue; // shown inside the web app
            var values = h.Value.ToArray();
            if (name.Equals("Content-Security-Policy", StringComparison.OrdinalIgnoreCase))
                values = values.Select(WithoutFrameAncestors).Where(v => v.Length > 0).ToArray();
            else if (name.Equals("Location", StringComparison.OrdinalIgnoreCase) || name.Equals("Content-Location", StringComparison.OrdinalIgnoreCase))
                values = values.Select(v => ToRelative(v, target)).ToArray();
            if (values.Length > 0) ctx.Response.Headers.Append(name, values);
        }
    }

    async Task RelayWebSocket(HttpContext ctx, int target, string raw)
    {
        // The dev server side: an HTTP/1.1 upgrade written here (ClientWebSocket rejected some servers' handshakes),
        // then WebSocket framing over that stream. The browser side may be HTTP/1.1 or HTTP/2 (Kestrel either way).
        Socket? sock = null;
        Stream? stream = null;
        string? protocol = null;
        try
        {
            using var cts = CancellationTokenSource.CreateLinkedTokenSource(ctx.RequestAborted);
            cts.CancelAfter(TimeSpan.FromSeconds(10));
            sock = await ConnectLocalAsync(target, cts.Token);
            stream = new NetworkStream(sock, ownsSocket: true);
            var key = Convert.ToBase64String(RandomNumberGenerator.GetBytes(16));
            var head = new StringBuilder();
            head.Append("GET ").Append(raw).Append(" HTTP/1.1\r\n");
            head.Append("Host: localhost:").Append(target).Append("\r\n");
            head.Append("Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\n");
            head.Append("Sec-WebSocket-Key: ").Append(key).Append("\r\n");
            head.Append("Origin: http://localhost:").Append(target).Append("\r\n");
            var asked = ctx.WebSockets.WebSocketRequestedProtocols;
            if (asked.Count > 0) head.Append("Sec-WebSocket-Protocol: ").Append(string.Join(", ", asked)).Append("\r\n");
            var cookie = WithoutOurCookie(ctx.Request.Headers.Cookie.ToString());
            if (cookie.Length > 0) head.Append("Cookie: ").Append(cookie).Append("\r\n");
            var ua = ctx.Request.Headers.UserAgent.ToString();
            if (ua.Length > 0) head.Append("User-Agent: ").Append(ua).Append("\r\n");
            head.Append("\r\n");
            await stream.WriteAsync(Encoding.ASCII.GetBytes(head.ToString()), cts.Token);
            var response = await ReadHeadAsync(stream, cts.Token);
            var lines = response.Split("\r\n");
            if (lines.Length == 0 || !lines[0].Contains(" 101", StringComparison.Ordinal))
                throw new IOException("dev server answered " + (lines.Length > 0 ? lines[0] : "nothing"));
            using var sha = SHA1.Create();
            var want = Convert.ToBase64String(sha.ComputeHash(Encoding.ASCII.GetBytes(key + "258EAFA5-E914-47DA-95CA-C5AB0DC11B85")));
            string? accept = null;
            foreach (var l in lines.Skip(1))
            {
                int c = l.IndexOf(':');
                if (c <= 0) continue;
                var name = l[..c].Trim();
                var value = l[(c + 1)..].Trim();
                if (name.Equals("Sec-WebSocket-Accept", StringComparison.OrdinalIgnoreCase)) accept = value;
                else if (name.Equals("Sec-WebSocket-Protocol", StringComparison.OrdinalIgnoreCase)) protocol = value;
            }
            // Seen on a PC: a Vite dev server answered 101 with the accept value of a key this code never sent (the
            // handshake was changed on the way; the frames still passed). The connection is to this PC's own
            // localhost and answered 101, so a mismatch is logged, not refused.
            if (accept != want && WarnedAccept.TryAdd(target, 0))
                Log.Info($"web view: localhost:{target} WebSocket answered for another key; relaying anyway");
        }
        catch (Exception ex) when (ex is IOException or SocketException or OperationCanceledException)
        {
            Log.Info($"web view: WebSocket localhost:{target}{raw} failed: {ex.Message}");
            if (stream != null) await stream.DisposeAsync(); else sock?.Dispose();
            ctx.Response.StatusCode = 502;
            return;
        }
        await using var owned = stream;
        using var server = WebSocket.CreateFromStream(stream, new WebSocketCreationOptions
        {
            IsServer = false, SubProtocol = protocol, KeepAliveInterval = TimeSpan.Zero,
        });
        using var ws = await ctx.WebSockets.AcceptWebSocketAsync(protocol);
        using var done = CancellationTokenSource.CreateLinkedTokenSource(ctx.RequestAborted);
        var a = Pump(ws, server, done.Token);
        var b = Pump(server, ws, done.Token);
        await Task.WhenAny(a, b);
        done.Cancel();
        await Task.WhenAll(Quiet(a), Quiet(b));
    }

    static readonly ConcurrentDictionary<int, IPAddress> LastWorked = new();
    static readonly ConcurrentDictionary<int, byte> WarnedAccept = new();

    /// <summary>
    /// A socket to localhost:<paramref name="port"/>. Dev servers listen on 127.0.0.1 or on ::1, and a refused connect
    /// takes Windows about 2 s, so the address that answered last for this port is tried first.
    /// </summary>
    static async Task<Socket> ConnectLocalAsync(int port, CancellationToken ct)
    {
        var first = LastWorked.TryGetValue(port, out var known) ? known : IPAddress.Loopback;
        var second = first.Equals(IPAddress.Loopback) ? IPAddress.IPv6Loopback : IPAddress.Loopback;
        SocketException? last = null;
        foreach (var ip in new[] { first, second })
        {
            var sock = new Socket(ip.AddressFamily, SocketType.Stream, ProtocolType.Tcp) { NoDelay = true };
            try
            {
                await sock.ConnectAsync(new IPEndPoint(ip, port), ct);
                LastWorked[port] = ip;
                return sock;
            }
            catch (SocketException ex) { last = ex; sock.Dispose(); }
            catch { sock.Dispose(); throw; }
        }
        throw last!;
    }

    /// <summary>Reads an HTTP response head (up to the blank line), byte by byte so nothing after it is consumed.</summary>
    static async Task<string> ReadHeadAsync(Stream s, CancellationToken ct)
    {
        var buf = new List<byte>(512);
        var one = new byte[1];
        while (buf.Count < 16384)
        {
            if (await s.ReadAsync(one, ct) == 0) break;
            buf.Add(one[0]);
            int n = buf.Count;
            if (n >= 4 && buf[n - 4] == 13 && buf[n - 3] == 10 && buf[n - 2] == 13 && buf[n - 1] == 10) break;
        }
        return Encoding.ASCII.GetString(buf.ToArray());
    }

    static async Task Quiet(Task t)
    {
        try { await t; }
        catch (Exception ex) when (ex is WebSocketException or OperationCanceledException or ObjectDisposedException or IOException) { }
    }

    static async Task Pump(WebSocket from, WebSocket to, CancellationToken ct)
    {
        var buf = new byte[64 * 1024];
        while (true)
        {
            var r = await from.ReceiveAsync(buf, ct);
            if (r.MessageType == WebSocketMessageType.Close)
            {
                if (to.State is WebSocketState.Open or WebSocketState.CloseReceived)
                    await to.CloseOutputAsync(r.CloseStatus ?? WebSocketCloseStatus.NormalClosure, r.CloseStatusDescription, ct);
                return;
            }
            await to.SendAsync(new ArraySegment<byte>(buf, 0, r.Count), r.MessageType, r.EndOfMessage, ct);
        }
    }

    static string WithoutOurCookie(string header) =>
        string.Join("; ", header.Split(';').Select(p => p.Trim())
            .Where(p => p.Length > 0 && !p.StartsWith(CookieName + "=", StringComparison.Ordinal)));

    static string WithoutFrameAncestors(string csp) =>
        string.Join("; ", csp.Split(';').Select(p => p.Trim())
            .Where(p => p.Length > 0 && !p.StartsWith("frame-ancestors", StringComparison.OrdinalIgnoreCase)));

    /// <summary>A redirect to the dev server itself (http://localhost:port/x) stays on this origin (/x).</summary>
    public static string ToRelative(string url, int target)
    {
        foreach (var h in new[] { "localhost", "127.0.0.1", "[::1]" })
        {
            var prefix = $"http://{h}:{target}";
            if (url.StartsWith(prefix, StringComparison.OrdinalIgnoreCase))
            {
                var rest = url.Substring(prefix.Length);
                if (rest.Length == 0) return "/";
                if (rest[0] is '/' or '?' or '#') return rest;
            }
        }
        return url;
    }

    /// <summary>A Referer on this origin as the dev server would see it (http://localhost:port/x).</summary>
    static string ToLocal(string url, int target)
    {
        if (!Uri.TryCreate(url, UriKind.Absolute, out var u) || u.Scheme != "https") return url;
        return $"http://localhost:{target}{u.PathAndQuery}";
    }

    static async Task Page(HttpContext ctx, int status, string title, string text)
    {
        ctx.Response.StatusCode = status;
        ctx.Response.ContentType = "text/html; charset=utf-8";
        ctx.Response.Headers.CacheControl = "no-store";
        var html = "<!doctype html><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\">"
            + "<title>" + WebUtility.HtmlEncode(title) + "</title>"
            + "<body style=\"font:16px/1.5 system-ui,sans-serif;background:#111;color:#ddd;padding:24px\">"
            + "<h1 style=\"font-size:20px\">" + WebUtility.HtmlEncode(title) + "</h1><p>" + WebUtility.HtmlEncode(text) + "</p>";
        await ctx.Response.WriteAsync(html, Encoding.UTF8);
    }
}
