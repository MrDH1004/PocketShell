using System.Collections.Concurrent;
using System.Diagnostics;
using System.Security.Cryptography.X509Certificates;
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
using Mpta.Core.Projects;
using Mpta.Core.Sessions;
using Mpta.Core.Update;
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
    /// <summary>Set by the updating (old) process on the new `mpta serve` it launches (DESIGN §11.3 handoff).</summary>
    public const string UpdateParentPidEnv = "MPTA_UPDATE_PARENT_PID";

    private readonly string _home;
    private readonly int _port;
    private readonly List<IPAddress> _binds;
    private readonly IReadOnlyList<string> _serveArgs;
    private readonly int _discoveryPort;
    private readonly ConcurrentDictionary<WsConn, byte> _conns = new();

    private SessionManager _sessions = null!;
    private ExternalConsoles _externals = null!;
    private TokenAuthenticator _auth = null!;
    private PairingManager _pairing = null!;
    private PinPairing _pins = null!;
    private ClaimRateLimiter _claimLimiter = null!;
    private WtTabLauncher _wt = null!;
    private UpdateService _update = null!;
    private DiscoveryResponder? _discovery;
    private X509Certificate2 _cert = null!;
    private string _fp = "";
    private string _fpShort = "";
    private string _installDir = "";
    private string _exe = "";
    private string _localToken = "";

    /// <summary>Set when the tray signalled the stop event (no update handoff after that).</summary>
    private volatile bool _stopRequested;
    // Update handoff state (see RunAsync / HandoffAsync).
    private WebApplication? _app;
    private int _applyBusy;
    private volatile bool _handoffPending;
    private string? _handoffStaging;
    private bool _handoffForce;

    /// <param name="serveArgs">Arguments after `serve`, replayed verbatim to the new process on update.</param>
    public HostServer(string home, int port, List<IPAddress> binds, IReadOnlyList<string> serveArgs, int discoveryPort)
    {
        _home = home;
        _port = port;
        _binds = binds;
        _serveArgs = serveArgs;
        _discoveryPort = discoveryPort;
    }

    public static string Version => typeof(HostServer).Assembly.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion.Split('+')[0] ?? "0.0.0";

    private List<string> PublicHosts() => _binds.Where(ip => !IPAddress.IsLoopback(ip)).Select(ip => ip.ToString()).ToList();

    private int RunningSessions() => _sessions.All.Count(s => s.IsRunning);

    public async Task<int> RunAsync()
    {
        Directory.CreateDirectory(_home);
        Log.InitFile(_home);
        var (cert, fp) = CertManager.LoadOrCreate(_home);
        _cert = cert;
        _fp = fp;
        _fpShort = PinPairing.FpShort(fp);
        _localToken = TokenAuthenticator.LoadOrCreateLocalToken(_home);
        _auth = new TokenAuthenticator(_localToken, new DeviceStore(MptaPaths.DevicesFile(_home)));
        _pairing = new PairingManager();
        _pins = new PinPairing();
        _claimLimiter = new ClaimRateLimiter();
        _sessions = new SessionManager(_home);
        _exe = Environment.ProcessPath ?? throw new InvalidOperationException("cannot determine psh.exe path");
        _installDir = Path.GetDirectoryName(_exe)!;
        _externals = new ExternalConsoles(_exe) { TabNames = TerminalTabs.Read };
        var attachArgs = new List<string> { "--port", _port.ToString() };
        if (!string.IsNullOrWhiteSpace(Environment.GetEnvironmentVariable("MPTA_HOME"))) { attachArgs.Add("--home"); attachArgs.Add(_home); }
        _wt = new WtTabLauncher(_exe, attachArgs);

        // Launched by an updating predecessor: the update is not committed until that process has exited (it may still
        // roll back and needs every *.old), so clean only after it is gone; tolerate a briefly busy port.
        int? parentPid = int.TryParse(Environment.GetEnvironmentVariable(UpdateParentPidEnv), out var pp) ? pp : null;
        Environment.SetEnvironmentVariable(UpdateParentPidEnv, null); // not inherited by shells / later children
        if (parentPid == null) CleanupOldInstallFiles("startup");
        else _ = Task.Run(() => CleanupAfterParentExit(parentPid.Value));

        var cfg = MptaConfig.LoadOrCreate(_home);
        var envUrl = Environment.GetEnvironmentVariable("MPTA_UPDATE_URL");
        var feed = !string.IsNullOrWhiteSpace(envUrl) ? envUrl.Trim() : !string.IsNullOrWhiteSpace(cfg.UpdateUrl) ? cfg.UpdateUrl.Trim() : ReleaseFeed.DefaultUrl;
        var interval = int.TryParse(Environment.GetEnvironmentVariable("MPTA_UPDATE_INTERVAL_SEC"), out var isec) && isec > 0
            ? TimeSpan.FromSeconds(isec) : TimeSpan.FromHours(6);
        var current = SemVer.TryParse(Version, out var cv) ? cv : SemVer.Parse("0.0.0");
        _update = new UpdateService(_home, current, feed, cfg.AutoUpdate, interval, RunningSessions);
        _update.AutoApply = () => { TryBeginApply(force: false); return Task.CompletedTask; };

        Log.Info($"{Brand.Name} {Version} data dir: {_home}");
        Log.Info($"cert fingerprint (sha256): {fp} (short {_fpShort})");
        int exitCode = 0;
        // The tray sees "running" while this mutex exists and asks for a graceful stop through the event.
        using var serveMutex = new Mutex(false, Brand.ServeMutexName(_port));
        using var stopEvent = new EventWaitHandle(false, EventResetMode.AutoReset, Brand.StopEventName(_port));
        var stopWait = ThreadPool.RegisterWaitForSingleObject(stopEvent, (_, _) =>
        {
            Log.Info("stop requested (tray)");
            _stopRequested = true;
            _app?.Lifetime.StopApplication();
        }, null, Timeout.Infinite, executeOnlyOnce: true);
        try
        {
            _update.Start();
            // Normally one iteration. An update handoff stops Kestrel (releasing the port) and, if the new process
            // fails, rolls the files back and loops to re-open the listeners in this same process (sessions kept).
            bool retryBind = parentPid != null;
            while (true)
            {
                var app = BuildApp();
                var startErr = await StartWithRetry(app, retryBind ? TimeSpan.FromSeconds(20) : TimeSpan.Zero);
                if (startErr != null)
                {
                    Log.Error($"cannot listen on port {_port}: {startErr}");
                    await app.DisposeAsync();
                    exitCode = 1;
                    break;
                }
                _app = app;
                if (_stopRequested) app.Lifetime.StopApplication(); // stop arrived while the listeners were starting
                StartDiscovery();
                Log.Info($"{Brand.Name} {Version} serving https/wss on port {_port}: {string.Join(", ", _binds)}");
                await app.WaitForShutdownAsync();
                StopDiscovery();
                _app = null;
                await app.DisposeAsync();
                if (!_handoffPending || _stopRequested) break;
                _handoffPending = false;
                if (await HandoffAsync()) break;
                retryBind = true; // rolled back: re-open listeners (port was just released by us)
            }
        }
        finally
        {
            stopWait.Unregister(null);
            Log.Info("shutting down: terminating running sessions");
            StopDiscovery();
            _update.Dispose();
            _externals.Dispose();
            _sessions.Dispose();
        }
        return exitCode;
    }

    private WebApplication BuildApp()
    {
        var builder = WebApplication.CreateSlimBuilder(new WebApplicationOptions { Args = Array.Empty<string>(), ContentRootPath = AppContext.BaseDirectory });
        builder.Logging.ClearProviders();
        builder.Logging.AddSimpleConsole(o => o.SingleLine = true);
        builder.Logging.SetMinimumLevel(LogLevel.Warning);
        builder.Services.Configure<HostOptions>(o => o.ShutdownTimeout = TimeSpan.FromSeconds(5));
        builder.WebHost.ConfigureKestrel(k =>
        {
            foreach (var ip in _binds)
                k.Listen(ip, _port, lo =>
                {
                    lo.Protocols = HttpProtocols.Http1;
                    lo.UseHttps(_cert);
                });
        });

        var app = builder.Build();
        app.UseWebSockets(new WebSocketOptions { KeepAliveInterval = TimeSpan.FromSeconds(20) });
        app.Use(AuthMiddleware);
        MapApi(app);
        app.Map("/ws/sessions/{id}", HandleWs);
        return app;
    }

    /// <summary>Starts Kestrel; on "address in use" retries until <paramref name="retryFor"/> elapses. Returns null or the error.</summary>
    private static async Task<string?> StartWithRetry(WebApplication app, TimeSpan retryFor)
    {
        var deadline = DateTime.UtcNow + retryFor;
        while (true)
        {
            try
            {
                await app.StartAsync();
                return null;
            }
            catch (IOException ex)
            {
                if (DateTime.UtcNow >= deadline) return ex.Message;
                Log.Warn($"bind failed ({ex.Message}); retrying");
                await Task.Delay(500);
            }
        }
    }

    private void StartDiscovery()
    {
        if (_discoveryPort <= 0) return;
        var addrs = DiscoveryResponder.SelectAddresses(_binds);
        if (addrs.Count == 0) return;
        _discovery = new DiscoveryResponder(DiscoveryResponder.BuildReply(Environment.MachineName, PublicHosts(), _port, _fpShort));
        _discovery.Start(addrs, _discoveryPort);
    }

    private void StopDiscovery()
    {
        _discovery?.Dispose();
        _discovery = null;
    }

    /// <summary>Returns how many backups are still locked.</summary>
    private int CleanupOldInstallFiles(string when)
    {
        var (deleted, remaining) = InstallTransaction.CleanupOld(_installDir);
        if (deleted + remaining > 0) Log.Info($"update cleanup ({when}): deleted {deleted} *.old file(s), {remaining} still locked");
        return remaining;
    }

    private async Task CleanupAfterParentExit(int pid)
    {
        try
        {
            using var p = Process.GetProcessById(pid);
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(60));
            await p.WaitForExitAsync(cts.Token);
        }
        catch (ArgumentException) { /* already gone */ }
        catch (InvalidOperationException) { }
        catch (OperationCanceledException)
        {
            // Still alive = handoff not committed (it could roll back); leave the backups for the next start.
            Log.Warn($"update: previous host pid {pid} still running after 60s; keeping *.old backups");
            return;
        }
        await Task.Delay(300);
        // The update is committed now. The tray still runs the previous build and holds its *.old files: hint it to
        // restart on the new files, then clean up. Retry for a few minutes: without the hint (older tray, other port)
        // the tray restarts on its own once the new build has been installed for 60 s.
        SignalTrayRestart();
        await Task.Delay(TimeSpan.FromSeconds(5));
        if (CleanupOldInstallFiles("after tray restart") == 0) return;
        foreach (var wait in new[] { 15, 30, 60, 90 })
        {
            await Task.Delay(TimeSpan.FromSeconds(wait));
            if (CleanupOldInstallFiles("retry") == 0) return;
        }
    }

    private void SignalTrayRestart()
    {
        if (!EventWaitHandle.TryOpenExisting(Brand.TrayRestartEventName(_port), out var ev))
        {
            Log.Info("update: no tray restart listener (tray not running, older than 0.2.4, or on another port)");
            return;
        }
        using (ev) ev.Set();
        Log.Info("update: asked the tray to restart on the new version");
    }

    // ------------------------------------------------------------------ update apply / handoff (DESIGN §11.3)

    /// <summary>Validates and starts an apply in the background. Returns the HTTP status + body for POST /api/update/apply.</summary>
    private (int Status, object Body) TryBeginApply(bool force)
    {
        if (!_update.HasNewer) return (400, new { error = "no update available", update = _update.Status });
        int running = RunningSessions();
        if (running > 0 && !force)
            return (409, new { error = $"{running} host session(s) running; close them or use force", runningSessions = running });
        if (Interlocked.CompareExchange(ref _applyBusy, 1, 0) != 0) return (200, _update.Status);
        _ = Task.Run(() => ApplyFlowAsync(force));
        return (200, _update.Status);
    }

    private async Task ApplyFlowAsync(bool force)
    {
        bool handedToLoop = false;
        try
        {
            var staging = await _update.EnsureStagedAsync();
            if (staging == null) return; // state=error (or nothing newer) already recorded
            if (!force && RunningSessions() > 0)
            {
                Log.Info("update: a session started before apply; waiting for idle");
                _update.MarkReady();
                return;
            }
            _update.MarkApplying();
            Log.Info($"update: applying {_update.Status.Latest} (force={force}); releasing port {_port}");
            await Task.Delay(300); // let the apply HTTP response flush
            _handoffStaging = staging;
            _handoffForce = force;
            _handoffPending = true;
            handedToLoop = true;
            // Close WebSockets first so Kestrel's graceful stop does not wait for long-lived connections.
            await Task.WhenAll(_conns.Keys.ToList().Select(c => c.CloseAsync(1012, "host updating")));
            var app = _app;
            if (app != null) app.Lifetime.StopApplication();
        }
        catch (Exception ex)
        {
            _update.MarkError($"apply failed: {ex.Message}");
        }
        finally
        {
            if (!handedToLoop) Interlocked.Exchange(ref _applyBusy, 0);
        }
    }

    /// <summary>
    /// Runs with Kestrel stopped (port free): swap install files (old → *.old), launch the new `mpta serve` with the same
    /// arguments, and wait until it answers /api/info with the new version. Returns true when this process should exit.
    /// On failure the new process is killed, files are restored and false is returned so listeners re-open here.
    /// </summary>
    private async Task<bool> HandoffAsync()
    {
        var staging = _handoffStaging!;
        var expected = _update.Status.Latest ?? "";
        bool success = false, released = false;
        try
        {
            // Kestrel is stopped now, so no session can start any more; re-check (one may have started during the stop).
            int running = RunningSessions();
            if (running > 0 && !_handoffForce)
            {
                Log.Info("update: a session started while stopping; re-opening listeners and waiting for idle");
                _update.MarkReady();
                return false;
            }
            // Release session files (output.vt is held open) so the new process can load the sessions directory.
            // With force, running shells are terminated here.
            _sessions.Dispose();
            released = true;
            if (running > 0) Log.Info($"update: force -> terminated {running} session(s)");

            InstallTransaction tx;
            try { tx = InstallTransaction.Apply(staging, _installDir); }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                _update.MarkError($"install failed ({ex.Message}); previous files restored");
                return false;
            }
            Log.Info($"update: replaced {tx.Targets.Count} file(s) in {_installDir}; starting new host");

            Process? child = null;
            string? err;
            try
            {
                var psi = new ProcessStartInfo(_exe) { UseShellExecute = false, WorkingDirectory = Environment.CurrentDirectory };
                psi.ArgumentList.Add("serve");
                foreach (var a in _serveArgs) psi.ArgumentList.Add(a);
                psi.Environment[UpdateParentPidEnv] = Environment.ProcessId.ToString();
                child = Process.Start(psi);
                err = child == null ? "Process.Start returned null" : await WaitForNewHost(child, expected, TimeSpan.FromSeconds(30));
            }
            catch (Exception ex) when (ex is System.ComponentModel.Win32Exception or InvalidOperationException)
            {
                err = "launch failed: " + ex.Message;
            }
            if (err == null)
            {
                Log.Info($"update: new host {expected} (pid {child!.Id}) is serving on port {_port}; exiting old process {Version}");
                child.Dispose();
                success = true;
                return true;
            }

            if (child != null)
            {
                try
                {
                    if (!child.HasExited) child.Kill(entireProcessTree: true);
                    child.WaitForExit(5000);
                }
                catch (Exception ex) when (ex is InvalidOperationException or System.ComponentModel.Win32Exception) { }
                child.Dispose();
            }
            var failures = tx.Rollback();
            _update.MarkApplyFailed(expected);
            _update.MarkError($"new version {expected} failed to start ({err}); rolled back to {Version}" +
                              (failures.Count > 0 ? $"; restore problems: {string.Join("; ", failures)}" : ""));
            return false;
        }
        finally
        {
            if (!success && released) _sessions = new SessionManager(_home); // keep serving: reload sessions (history) from disk
            Interlocked.Exchange(ref _applyBusy, 0);
        }
    }

    /// <summary>Polls the new process's /api/info (pinned TLS, local token). Null = serving the expected version.</summary>
    private async Task<string?> WaitForNewHost(Process child, string expected, TimeSpan timeout)
    {
        var probe = _binds.FirstOrDefault(IPAddress.IsLoopback) ?? _binds[0];
        var handler = new SocketsHttpHandler();
        handler.SslOptions.RemoteCertificateValidationCallback = (_, c, _, _) => c != null && CertManager.Fingerprint(c) == _fp;
        using var http = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(3) };
        http.DefaultRequestHeaders.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", _localToken);
        var host = probe.AddressFamily == System.Net.Sockets.AddressFamily.InterNetworkV6 ? $"[{probe}]" : probe.ToString();
        var url = $"https://{host}:{_port}/api/info";
        var deadline = DateTime.UtcNow + timeout;
        string last = "no response";
        while (DateTime.UtcNow < deadline)
        {
            if (child.HasExited) return $"new process exited with code {child.ExitCode}";
            try
            {
                using var resp = await http.GetAsync(url);
                var body = await resp.Content.ReadAsStringAsync();
                if (resp.IsSuccessStatusCode)
                {
                    using var doc = JsonDocument.Parse(body);
                    var v = doc.RootElement.TryGetProperty("version", out var ve) ? ve.GetString() ?? "" : "";
                    if (SemVer.TryParse(v, out var got) && SemVer.TryParse(expected, out var want) && got.Equals(want)) return null;
                    return $"new process reports version '{v}', expected '{expected}'";
                }
                last = $"HTTP {(int)resp.StatusCode}";
            }
            catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or IOException or JsonException)
            {
                last = ex.Message;
            }
            await Task.Delay(300);
        }
        return $"no /api/info from new process within {timeout.TotalSeconds:0}s ({last})";
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
        app.MapGet("/api/info", () => Json(new { name = Environment.MachineName, version = Version, fp = _fp, port = _port, hosts = PublicHosts(), update = _update.Status }));

        app.MapGet("/api/update", () => Json(_update.Status));
        app.MapPost("/api/update/check", async () => Json(await _update.CheckAsync()));
        app.MapPost("/api/update/apply", async (HttpContext ctx) =>
        {
            var req = await ReadBody<ApplyRequest>(ctx);
            var (status, body) = TryBeginApply(req?.Force == true);
            return Json(body, status);
        });

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

        app.MapGet("/api/projects", () =>
        {
            var settings = ProjectStore.Load(_home);
            return Json(new
            {
                roots = settings.Roots,
                agents = ProjectStore.DetectAgents().Select(a => new { id = a.Id, name = a.Name }),
            });
        });

        // New project: <root>\<name> folder (created if missing) + a host session there running the chosen AI CLI.
        app.MapPost("/api/projects", async (HttpContext ctx) =>
        {
            var req = await ReadBody<CreateProjectRequest>(ctx) ?? new CreateProjectRequest();
            var settings = ProjectStore.Load(_home);
            if (settings.Roots.Count == 0) return Error(400, "PC 앱(트레이)에서 프로젝트 폴더를 먼저 추가하세요");
            var root = ProjectStore.FindRoot(settings, req.Root);
            if (root == null) return Error(400, "등록된 프로젝트 폴더가 아닙니다");
            if (!Directory.Exists(root)) return Error(400, $"프로젝트 폴더가 없습니다: {root}");
            var name = req.Name ?? "";
            var invalid = ProjectStore.ValidateName(name);
            if (invalid != null) return Error(400, invalid);
            var agent = ProjectStore.DetectAgents().FirstOrDefault(a => string.Equals(a.Id, req.Agent, StringComparison.OrdinalIgnoreCase));
            if (agent == null) return Error(400, "PC에서 선택한 AI CLI를 찾을 수 없습니다");

            var dir = Path.Combine(root, name);
            bool existed = Directory.Exists(dir);
            try
            {
                Directory.CreateDirectory(dir);
                var s = _sessions.Create(new CreateSessionRequest { Cwd = dir, Title = name, Cols = req.Cols, Rows = req.Rows });
                s.WriteInput(Encoding.UTF8.GetBytes(agent.Command + "\r"));
                Log.Info($"project: {(existed ? "opened" : "created")} {dir} with {agent.Id} (session {s.Id}) by {AuthOf(ctx).DeviceId ?? "local"}");
                return Json(s.Snapshot());
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or InvalidOperationException or System.ComponentModel.Win32Exception)
            {
                Log.Error($"project create failed ({dir}): {ex.Message}");
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

        // Phone → PC clipboard image; the phone then sends Alt+V (ESC v) on the session so Claude Code attaches it.
        app.MapPost("/api/clipboard/image", async (HttpContext ctx) =>
        {
            var image = await ClipboardImage.ReadBodyAsync(ctx.Request.Body, ctx.RequestAborted);
            if (image == null) return Error(413, $"image larger than {ClipboardImage.MaxBytes >> 20} MB");
            if (image.Length == 0) return Error(400, "empty body");
            var err = await Task.Run(() => ClipboardImage.Set(image));
            if (err != null) return Error(err.StartsWith("not a supported", StringComparison.Ordinal) ? 400 : 503, err);
            Log.Info($"clipboard: image set ({image.Length} bytes) by {AuthOf(ctx).DeviceId ?? "local"}");
            return Json(new { ok = true, bytes = image.Length });
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

        app.MapPost("/api/pair/start", async (HttpContext ctx) =>
        {
            if (!AuthOf(ctx).IsLocal) return Error(401, "local token required");
            var req = await ReadBody<PairStartRequest>(ctx);
            if (string.Equals(req?.Mode, "pin", StringComparison.OrdinalIgnoreCase))
            {
                var (pin, pexp) = _pins.Start();
                Log.Info($"pairing PIN issued (fp {_fpShort}), expires {Ids.IsoUtc(pexp)}");
                return Json(new { pin, expiresAt = Ids.IsoUtc(pexp), fpShort = _fpShort, hosts = PublicHosts(), port = _port });
            }
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
            var remote = ctx.Connection.RemoteIpAddress;
            if (remote is { IsIPv4MappedToIPv6: true }) remote = remote.MapToIPv4();
            var ip = remote?.ToString() ?? "?";
            if (_claimLimiter.IsLimited(ip))
            {
                Log.Warn($"pairing claim from {ip} rejected: too many failures (429)");
                return Error(429, "too many failed pairing attempts; try again later");
            }
            var body = await ReadBody<ClaimRequest>(ctx);
            bool byPin = !string.IsNullOrEmpty(body?.Pin);
            bool ok = body != null && (byPin ? _pins.TryConsume(body.Pin) == PinClaimResult.Ok : _pairing.TryConsume(body.Code));
            if (!ok)
            {
                _claimLimiter.RecordFailure(ip);
                Log.Warn($"pairing claim failed from {ip} ({(byPin ? "pin" : "code")})");
                return Error(401, byPin ? "invalid or expired PIN" : "invalid or expired pairing code");
            }
            var (dev, token) = _auth.Devices.Add(body!.DeviceName ?? "device");
            Log.Info($"device paired: {dev.Id} ({dev.Name}) via {(byPin ? "pin" : "code")} from {ip}");
            return Json(new { deviceId = dev.Id, token, fp = _fp });
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
        public string? Pin { get; set; }
        public string? DeviceName { get; set; }
    }

    private sealed class PairStartRequest
    {
        public string? Mode { get; set; }
    }

    private sealed class ApplyRequest
    {
        public bool Force { get; set; }
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
            if (a.Size is var (sc, sr)) await conn.SendTextAsync(ControlMessages.Size(sc, sr), cts.Token);
            if (a.History.Count > 0) await conn.SendTextAsync(ControlMessages.History(a.History), cts.Token);
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

    /// <summary>MPTA_LOG_INPUT=1 logs every inbound input frame (hex + text) — for diagnosing client input problems only.</summary>
    private static readonly bool LogInput = Environment.GetEnvironmentVariable("MPTA_LOG_INPUT") == "1";

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
                if (LogInput && data.Length > 0)
                    Log.Info($"input ({conn.DeviceId ?? "local"}): {Convert.ToHexString(data)} {System.Text.Json.JsonSerializer.Serialize(Encoding.UTF8.GetString(data))}");
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
