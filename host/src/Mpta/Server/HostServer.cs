using System.Collections.Concurrent;
using System.Net;
using System.Net.WebSockets;
using System.Reflection;
using System.Text;
using System.Text.Json;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Server.Kestrel.Core;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Mpta.Core.Auth;
using Mpta.Core.External;
using Mpta.Core.Net;
using Mpta.Core.Sessions;
using Mpta.Core.Util;

namespace Mpta.Server;

/// <summary>A WebSocket connection with serialized sends; tracked so device revocation can close it with 4401.</summary>
internal sealed class WsConn
{
    private readonly SemaphoreSlim _send = new(1, 1);
    public WebSocket Ws { get; }
    public string? DeviceId { get; }

    public WsConn(WebSocket ws, string? deviceId) { Ws = ws; DeviceId = deviceId; }

    public async Task SendAsync(ReadOnlyMemory<byte> data, bool text, CancellationToken ct)
    {
        await _send.WaitAsync(ct);
        try
        {
            if (Ws.State != WebSocketState.Open) return;
            await Ws.SendAsync(data, text ? WebSocketMessageType.Text : WebSocketMessageType.Binary, true, ct);
        }
        finally { _send.Release(); }
    }

    public Task SendTextAsync(string s, CancellationToken ct) => SendAsync(Encoding.UTF8.GetBytes(s), true, ct);

    public async Task SendChunkedAsync(byte[] data, CancellationToken ct)
    {
        const int chunk = 64 * 1024;
        for (int off = 0; off < data.Length; off += chunk)
            await SendAsync(data.AsMemory(off, Math.Min(chunk, data.Length - off)), false, ct);
    }

    public async Task CloseAsync(int code, string reason)
    {
        // Abort if the peer does not complete the close handshake promptly. Scheduled first so a send
        // blocked on a stalled peer (holding _send) is faulted and cannot hang the close.
        _ = Task.Delay(2000).ContinueWith(_ => { if (Ws.State != WebSocketState.Closed) Ws.Abort(); });
        try
        {
            await _send.WaitAsync();
            try
            {
                if (Ws.State is WebSocketState.Open or WebSocketState.CloseReceived)
                    await Ws.CloseOutputAsync((WebSocketCloseStatus)code, reason, CancellationToken.None);
            }
            finally { _send.Release(); }
        }
        catch (Exception ex) when (ex is WebSocketException or ObjectDisposedException or OperationCanceledException) { }
    }
}

public sealed class HostServer
{
    private readonly string _home;
    private readonly int _port;
    private readonly List<IPAddress> _binds;
    private readonly ConcurrentDictionary<WsConn, byte> _conns = new();

    private SessionManager _sessions = null!;
    private ExternalConsoles _externals = null!;
    private TokenAuthenticator _auth = null!;
    private PairingManager _pairing = null!;
    private WtTabLauncher _wt = null!;
    private string _fp = "";

    public HostServer(string home, int port, List<IPAddress> binds)
    {
        _home = home;
        _port = port;
        _binds = binds;
    }

    private static string Version => typeof(HostServer).Assembly.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion.Split('+')[0] ?? "0.0.0";

    private List<string> PublicHosts() => _binds.Where(ip => !IPAddress.IsLoopback(ip)).Select(ip => ip.ToString()).ToList();

    public async Task<int> RunAsync()
    {
        Directory.CreateDirectory(_home);
        Log.InitFile(_home);
        var (cert, fp) = CertManager.LoadOrCreate(_home);
        _fp = fp;
        var localToken = TokenAuthenticator.LoadOrCreateLocalToken(_home);
        _auth = new TokenAuthenticator(localToken, new DeviceStore(MptaPaths.DevicesFile(_home)));
        _pairing = new PairingManager();
        _sessions = new SessionManager(_home);
        var exe = Environment.ProcessPath ?? throw new InvalidOperationException("cannot determine mpta.exe path");
        _externals = new ExternalConsoles(exe);
        var attachArgs = new List<string> { "--port", _port.ToString() };
        if (!string.IsNullOrWhiteSpace(Environment.GetEnvironmentVariable("MPTA_HOME"))) { attachArgs.Add("--home"); attachArgs.Add(_home); }
        _wt = new WtTabLauncher(exe, attachArgs);

        var builder = WebApplication.CreateSlimBuilder(new WebApplicationOptions { Args = Array.Empty<string>(), ContentRootPath = AppContext.BaseDirectory });
        builder.Logging.ClearProviders();
        builder.Logging.AddSimpleConsole(o => o.SingleLine = true);
        builder.Logging.SetMinimumLevel(LogLevel.Warning);
        builder.WebHost.ConfigureKestrel(k =>
        {
            foreach (var ip in _binds)
                k.Listen(ip, _port, lo =>
                {
                    lo.Protocols = HttpProtocols.Http1;
                    lo.UseHttps(cert);
                });
        });

        var app = builder.Build();
        app.UseWebSockets(new WebSocketOptions { KeepAliveInterval = TimeSpan.FromSeconds(20) });
        app.Use(AuthMiddleware);
        MapApi(app);
        app.Map("/ws/sessions/{id}", HandleWs);

        Log.Info($"mpta {Version} serving https/wss on port {_port}: {string.Join(", ", _binds)}");
        Log.Info($"data dir: {_home}");
        Log.Info($"cert fingerprint (sha256): {fp}");
        try
        {
            await app.RunAsync();
        }
        finally
        {
            Log.Info("shutting down: terminating running sessions");
            _externals.Dispose();
            _sessions.Dispose();
        }
        return 0;
    }

    private async Task AuthMiddleware(HttpContext ctx, Func<Task> next)
    {
        var path = ctx.Request.Path;
        if (path.StartsWithSegments("/api/pair/claim")) { await next(); return; }
        bool isApi = path.StartsWithSegments("/api");
        bool isWs = path.StartsWithSegments("/ws");
        if (!isApi && !isWs) { ctx.Response.StatusCode = 404; return; }
        string? token = TokenAuthenticator.ParseBearer(ctx.Request.Headers.Authorization.ToString());
        if (token == null && isWs) token = ctx.Request.Query["token"].ToString();
        var auth = _auth.Authenticate(token, ctx.Connection.RemoteIpAddress);
        if (auth == null)
        {
            ctx.Response.StatusCode = 401;
            await ctx.Response.WriteAsJsonAsync(new { error = "unauthorized" });
            return;
        }
        ctx.Items["auth"] = auth;
        await next();
    }

    private static AuthResult AuthOf(HttpContext ctx) => (AuthResult)ctx.Items["auth"]!;

    private static IResult Json(object o, int status = 200) => Results.Json(o, Mpta.Core.Util.Json.Options, statusCode: status);
    private static IResult Error(int status, string msg) => Results.Json(new { error = msg }, Mpta.Core.Util.Json.Options, statusCode: status);

    private void MapApi(WebApplication app)
    {
        app.MapGet("/api/info", () => Json(new { name = Environment.MachineName, version = Version, fp = _fp, port = _port, hosts = PublicHosts() }));

        app.MapGet("/api/sessions", () =>
        {
            var list = _sessions.All.Select(s => s.Snapshot())
                .OrderByDescending(s => s.State == "running").ThenByDescending(s => s.LastActivity, StringComparer.Ordinal)
                .ToList();
            list.AddRange(_externals.List());
            return Json(new { sessions = list });
        });

        app.MapGet("/api/sessions/{id}", (string id) =>
        {
            if (TryExtPid(id, out var pid))
            {
                var ext = _externals.Get(pid);
                return ext != null ? Json(ext) : Error(404, "session not found");
            }
            var s = _sessions.Get(id);
            return s != null ? Json(s.Snapshot()) : Error(404, "session not found");
        });

        app.MapPost("/api/sessions", async (HttpContext ctx) =>
        {
            var req = await ReadBody<CreateSessionRequest>(ctx) ?? new CreateSessionRequest();
            try { return Json(_sessions.Create(req).Snapshot()); }
            catch (Exception ex) when (ex is InvalidOperationException or System.ComponentModel.Win32Exception)
            {
                Log.Error($"create session failed: {ex.Message}");
                return Error(400, ex.Message);
            }
        });

        app.MapPost("/api/sessions/{id}/resume", (string id) =>
        {
            var s = _sessions.Get(id);
            if (s == null) return Error(404, "session not found");
            if (s.IsRunning) return Error(409, "session is running; only history sessions can be resumed");
            try { return Json(_sessions.Resume(id).Snapshot()); }
            catch (Exception ex) when (ex is InvalidOperationException or System.ComponentModel.Win32Exception)
            {
                Log.Error($"resume {id} failed: {ex.Message}");
                return Error(400, ex.Message);
            }
        });

        app.MapDelete("/api/sessions/{id}", (string id) =>
        {
            if (TryExtPid(id, out _)) return Error(400, "external sessions cannot be deleted");
            return _sessions.Delete(id) ? Results.NoContent() : Error(404, "session not found");
        });

        app.MapPost("/api/external/{pid:int}/adopt", (int pid) =>
        {
            var ext = _externals.Get(pid);
            if (ext == null) return Error(404, "external console not found");
            var cwd = ProcessCwd.TryGet(pid);
            try
            {
                var s = _sessions.Create(new CreateSessionRequest
                {
                    Shell = ext.Shell,
                    Cwd = cwd ?? ShellLauncher.UserHome,
                    Title = string.IsNullOrWhiteSpace(ext.Title) ? ext.Shell : ext.Title,
                    Cols = ext.Cols > 0 ? ext.Cols : null,
                    Rows = ext.Rows > 0 ? ext.Rows : null,
                });
                return Json(s.Snapshot());
            }
            catch (Exception ex) when (ex is InvalidOperationException or System.ComponentModel.Win32Exception)
            {
                return Error(400, ex.Message);
            }
        });

        app.MapPost("/api/pair/start", (HttpContext ctx) =>
        {
            if (!AuthOf(ctx).IsLocal) return Error(401, "local token required");
            var (code, exp) = _pairing.Start();
            var payload = new QrPayload { Name = Environment.MachineName, Hosts = PublicHosts(), Port = _port, Fp = _fp, Code = code }.ToJson();
            var (ascii, png) = PairingQr.Render(payload);
            var pngPath = MptaPaths.PairQrPng(_home);
            File.WriteAllBytes(pngPath, png);
            Log.Info($"pairing code issued, expires {Ids.IsoUtc(exp)}");
            return Json(new { code, expiresAt = Ids.IsoUtc(exp), qrPayload = payload, qrAscii = ascii, qrPngPath = pngPath });
        });

        app.MapPost("/api/pair/claim", async (HttpContext ctx) =>
        {
            var body = await ReadBody<ClaimRequest>(ctx);
            if (body == null || !_pairing.TryConsume(body.Code)) return Error(401, "invalid or expired pairing code");
            var (dev, token) = _auth.Devices.Add(body.DeviceName ?? "device");
            Log.Info($"device paired: {dev.Id} ({dev.Name})");
            return Json(new { deviceId = dev.Id, token });
        });

        app.MapGet("/api/devices", (HttpContext ctx) =>
        {
            if (!AuthOf(ctx).IsLocal) return Error(401, "local token required");
            var list = _auth.Devices.List().Select(d => new { d.Id, d.Name, d.CreatedAt, d.Revoked }).ToList();
            return Json(new { devices = list });
        });

        app.MapDelete("/api/devices/{id}", async (HttpContext ctx, string id) =>
        {
            if (!AuthOf(ctx).IsLocal) return Error(401, "local token required");
            if (!_auth.Devices.Revoke(id)) return Error(404, "device not found");
            var victims = _conns.Keys.Where(c => c.DeviceId == id).ToList();
            await Task.WhenAll(victims.Select(c => c.CloseAsync(4401, "device revoked")));
            Log.Info($"device revoked: {id} (closed {victims.Count} connection(s))");
            return Results.NoContent();
        });
    }

    private sealed class ClaimRequest
    {
        public string? Code { get; set; }
        public string? DeviceName { get; set; }
    }

    private static async Task<T?> ReadBody<T>(HttpContext ctx) where T : class
    {
        try
        {
            using var reader = new StreamReader(ctx.Request.Body, Encoding.UTF8);
            var text = await reader.ReadToEndAsync();
            if (string.IsNullOrWhiteSpace(text)) return null;
            return JsonSerializer.Deserialize<T>(text, Mpta.Core.Util.Json.Options);
        }
        catch (JsonException) { return null; }
    }

    private static bool TryExtPid(string id, out int pid)
    {
        pid = 0;
        return id.StartsWith("ext-", StringComparison.Ordinal) && int.TryParse(id.AsSpan(4), out pid);
    }

    /// <summary>Positive query dimension clamped to SizePolicy bounds; 0 when absent/invalid (= size not reported).</summary>
    private static int QueryDim(HttpContext ctx, string key) => int.TryParse(ctx.Request.Query[key].ToString(), out var v) && v > 0 ? SizePolicy.Clamp(v) : 0;

    private async Task HandleWs(HttpContext ctx, string id)
    {
        if (!ctx.WebSockets.IsWebSocketRequest) { ctx.Response.StatusCode = 400; return; }
        var clientType = ctx.Request.Query["client"].ToString();
        if (clientType is not ("android" or "cli" or "qa")) clientType = "qa";
        int cols = QueryDim(ctx, "cols"), rows = QueryDim(ctx, "rows");
        bool openPcTab = ctx.Request.Query["openPcTab"].ToString() != "0";

        HostSession? host = null;
        ExternalBridge? bridge = null;
        SessionDto? extInfo = null;
        if (TryExtPid(id, out var pid))
        {
            extInfo = _externals.Get(pid);
            if (extInfo == null) { ctx.Response.StatusCode = 404; return; }
        }
        else
        {
            host = _sessions.Get(id);
            if (host == null) { ctx.Response.StatusCode = 404; return; }
        }

        using var ws = await ctx.WebSockets.AcceptWebSocketAsync();
        var conn = new WsConn(ws, AuthOf(ctx).DeviceId);
        _conns[conn] = 0;
        // Revoke may have happened between AuthMiddleware and registration; the revoke endpoint only closes
        // connections already in _conns, so re-check now that this one is registered.
        if (conn.DeviceId != null && !_auth.Devices.IsActive(conn.DeviceId))
        {
            _conns.TryRemove(conn, out _);
            await conn.CloseAsync(4401, "device revoked");
            return;
        }
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(ctx.RequestAborted);
        try
        {
            if (host != null) await RunHostWs(conn, host, clientType, cols, rows, openPcTab, cts);
            else
            {
                bridge = _externals.Bridge(pid, extInfo!);
                await RunExternalWs(conn, bridge, extInfo!, clientType, cols, rows, cts);
            }
        }
        catch (Exception ex) when (ex is WebSocketException or OperationCanceledException or IOException)
        {
            Log.Info($"ws {id} ({clientType}) ended: {ex.GetType().Name}: {ex.Message}");
        }
        finally
        {
            _conns.TryRemove(conn, out _);
            cts.Cancel();
        }
    }

    private async Task RunHostWs(WsConn conn, HostSession s, string clientType, int cols, int rows, bool openPcTab, CancellationTokenSource cts)
    {
        var a = s.Attach(clientType, cols, rows);
        Log.Info($"ws attach {s.Id}: client={clientType}/{a.Client.Id} {cols}x{rows}");
        try
        {
            if (clientType == "cli") _wt.ClearPending(s.Id);
            else if (clientType == "android" && openPcTab && s.IsRunning)
                _wt.MaybeOpen(s.Id, a.Hello.Title, s.HasClientOfType("cli"));

            await conn.SendTextAsync(ControlMessages.Hello(a.Hello), cts.Token);
            await conn.SendChunkedAsync(a.Replay, cts.Token);
            await conn.SendTextAsync(ControlMessages.ReplayEnd, cts.Token);
            var (c, r) = s.CurrentSize;
            await conn.SendTextAsync(ControlMessages.Size(c, r), cts.Token);

            var sender = PumpAsync(conn, a.Subscriber, cts.Token);
            await ReceiveLoop(conn, cts.Token,
                data => s.WriteInput(data),
                (nc, nr) => s.ResizeClient(a.Client, nc, nr));
            cts.Cancel();
            try { await sender; } catch (OperationCanceledException) { }
        }
        finally
        {
            s.Detach(a);
            Log.Info($"ws detach {s.Id}: client={clientType}/{a.Client.Id}");
        }
    }

    private async Task RunExternalWs(WsConn conn, ExternalBridge b, SessionDto info, string clientType, int cols, int rows, CancellationTokenSource cts)
    {
        var a = b.Attach(clientType, cols, rows);
        try
        {
            var hello = info.Clone();
            hello.Clients = b.Clients();
            await conn.SendTextAsync(ControlMessages.Hello(hello), cts.Token);
            if (a.Replay.Length > 0) await conn.SendChunkedAsync(a.Replay, cts.Token);
            await conn.SendTextAsync(ControlMessages.ReplayEnd, cts.Token);
            var sender = PumpAsync(conn, a.Subscriber, cts.Token);
            await ReceiveLoop(conn, cts.Token, data => b.WriteInput(data), (_, _) => { /* size policy not applied to external consoles */ });
            cts.Cancel();
            try { await sender; } catch (OperationCanceledException) { }
        }
        finally { b.Detach(a); }
    }

    private static async Task PumpAsync(WsConn conn, Subscriber sub, CancellationToken ct)
    {
        // Slow consumer: the output queue overflowed. Drop the socket so the client reconnects and gets replay.
        _ = sub.Overflowed.ContinueWith(_ =>
        {
            Log.Warn("ws: subscriber queue overflow; closing with 4408 (slow consumer)");
            return conn.CloseAsync(4408, "slow consumer");
        }, CancellationToken.None, TaskContinuationOptions.OnlyOnRanToCompletion, TaskScheduler.Default);
        await foreach (var m in sub.Reader.ReadAllAsync(ct))
            await conn.SendAsync(m.Data, m.IsText, ct);
    }

    /// <summary>Largest accepted inbound WS message; larger ones close the socket with 1009 (message too big).</summary>
    internal const int MaxInboundMessageBytes = 1024 * 1024;

    private static async Task ReceiveLoop(WsConn conn, CancellationToken ct, Action<byte[]> onInput, Action<int, int> onResize)
    {
        var buf = new byte[64 * 1024];
        using var msg = new MemoryStream();
        while (!ct.IsCancellationRequested)
        {
            var res = await conn.Ws.ReceiveAsync(buf, ct);
            if (res.MessageType == WebSocketMessageType.Close)
            {
                await conn.CloseAsync((int)WebSocketCloseStatus.NormalClosure, "bye");
                return;
            }
            if (msg.Length + res.Count > MaxInboundMessageBytes)
            {
                Log.Warn($"ws: inbound message exceeds {MaxInboundMessageBytes} bytes; closing with 1009");
                await conn.CloseAsync((int)WebSocketCloseStatus.MessageTooBig, "message too big");
                return;
            }
            msg.Write(buf, 0, res.Count);
            if (!res.EndOfMessage) continue;
            var data = msg.ToArray();
            msg.SetLength(0);
            if (res.MessageType == WebSocketMessageType.Binary)
            {
                if (data.Length > 0) onInput(data);
                continue;
            }
            try
            {
                using var doc = JsonDocument.Parse(data);
                var root = doc.RootElement;
                if (root.TryGetProperty("type", out var t) && t.GetString() == "resize"
                    && root.TryGetProperty("cols", out var c) && root.TryGetProperty("rows", out var r)
                    && c.TryGetInt32(out var cols) && r.TryGetInt32(out var rows) && cols > 0 && rows > 0)
                    onResize(SizePolicy.Clamp(cols), SizePolicy.Clamp(rows));
            }
            catch (JsonException) { Log.Warn("ws: ignoring malformed text message"); }
        }
    }
}
