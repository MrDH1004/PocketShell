using System.Collections.Concurrent;
using System.Diagnostics;
using System.Security.Cryptography.X509Certificates;
using System.Net;
using System.Net.NetworkInformation;
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
using Pkshell.Core.Auth;
using Pkshell.Core.External;
using Pkshell.Core.Files;
using Pkshell.Core.Net;
using Pkshell.Core.Projects;
using Pkshell.Core.Sessions;
using Pkshell.Core.Update;
using Pkshell.Core.Util;

namespace Pkshell.Server;

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

public sealed class HostServer : IDisposable
{
    /// <summary>Set by the updating (old) process on the new `pkshell serve` it launches (DESIGN §11.3 handoff).</summary>
    public const string UpdateParentPidEnv = "PKSHELL_UPDATE_PARENT_PID";

    private readonly string _home;
    private readonly int _port;
    /// <summary>Replaced (never mutated) when the interface addresses change, see WatchAddressesAsync.</summary>
    private volatile List<IPAddress> _binds;
    /// <summary>No --bind given: follow the interface addresses (e.g. Tailscale coming up after the host started).</summary>
    private readonly bool _autoBinds;
    private volatile bool _rebindPending;
    private volatile List<IPAddress>? _rebindFrom;
    private readonly SemaphoreSlim _addressChanged = new(0, 1);
    private readonly IReadOnlyList<string> _serveArgs;
    private readonly int _discoveryPort;
    private readonly ConcurrentDictionary<WsConn, byte> _conns = new();

    private SessionManager _sessions = null!;
    private ExternalConsoles _externals = null!;
    private RecentExternals _recent = null!;
    private readonly AgentEventLog _events = new();
    private readonly CancellationTokenSource _bgCts = new();
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
    private bool _disposed;

    /// <param name="serveArgs">Arguments after `serve`, replayed verbatim to the new process on update.</param>
    public HostServer(string home, int port, List<IPAddress> binds, IReadOnlyList<string> serveArgs, int discoveryPort, bool autoBinds = false)
    {
        _home = home;
        _port = port;
        _binds = binds;
        _autoBinds = autoBinds;
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
        _auth = new TokenAuthenticator(_localToken, new DeviceStore(PkshellPaths.DevicesFile(_home)));
        _pairing = new PairingManager();
        _pins = new PinPairing();
        _claimLimiter = new ClaimRateLimiter();
        _sessions = new SessionManager(_home);
        _exe = Environment.ProcessPath ?? throw new InvalidOperationException("cannot determine psh.exe path");
        _installDir = Path.GetDirectoryName(_exe)!;
        _externals = new ExternalConsoles(_exe, Path.Combine(_home, "external-scrollback")) { TabNames = TerminalTabs.Read };
        _recent = new RecentExternals(RecentExternals.FileIn(_home));
        _ = Task.Run(() => WatchRecentAsync(_bgCts.Token));
        _ = Task.Run(() => WatchAgentsAsync(_bgCts.Token));
        var attachArgs = new List<string> { "--port", _port.ToString(System.Globalization.CultureInfo.InvariantCulture) };
        if (!string.IsNullOrWhiteSpace(Pkshell.Core.Util.EnvVars.Get("HOME"))) { attachArgs.Add("--home"); attachArgs.Add(_home); }
        _wt = new WtTabLauncher(_exe, attachArgs);

        // Launched by an updating predecessor: the update is not committed until that process has exited (it may still
        // roll back and needs every *.old), so clean only after it is gone; tolerate a briefly busy port.
        int? parentPid = int.TryParse(Pkshell.Core.Util.EnvVars.Get("UPDATE_PARENT_PID"), out var pp) ? pp : null;
        Environment.SetEnvironmentVariable(UpdateParentPidEnv, null); // not inherited by shells / later children
        if (parentPid == null) CleanupOldInstallFiles("startup");
        else _ = Task.Run(() => CleanupAfterParentExit(parentPid.Value));
        SyncInstalledVersion();

        var cfg = PkshellConfig.LoadOrCreate(_home);
        var envUrl = Pkshell.Core.Util.EnvVars.Get("UPDATE_URL");
        var feed = !string.IsNullOrWhiteSpace(envUrl) ? envUrl.Trim() : !string.IsNullOrWhiteSpace(cfg.UpdateUrl) ? cfg.UpdateUrl.Trim() : ReleaseFeed.DefaultUrl;
        var interval = int.TryParse(Pkshell.Core.Util.EnvVars.Get("UPDATE_INTERVAL_SEC"), out var isec) && isec > 0
            ? TimeSpan.FromSeconds(isec) : TimeSpan.FromHours(3);
        var current = SemVer.TryParse(Version, out var cv) ? cv : SemVer.Parse("0.0.0");
        _update = new UpdateService(_home, current, feed, cfg.AutoUpdate, interval, RunningSessions);
        _update.AutoApply = () => { TryBeginApply(force: false, killWorking: false); return Task.CompletedTask; };

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
            if (_autoBinds)
            {
                NetworkChange.NetworkAddressChanged += OnNetworkAddressChanged;
                _ = Task.Run(() => WatchAddressesAsync(_bgCts.Token));
            }
            // Normally one iteration. An update handoff stops Kestrel (releasing the port) and, if the new process
            // fails, rolls the files back and loops to re-open the listeners in this same process (sessions kept).
            // An address change (WatchAddressesAsync) also loops here to re-open the listeners on the new set.
            bool retryBind = parentPid != null;
            List<IPAddress>? previousBinds = null;
            while (true)
            {
                var app = BuildApp();
                var startErr = await StartWithRetry(app, retryBind ? TimeSpan.FromSeconds(previousBinds != null ? 5 : 20) : TimeSpan.Zero);
                if (startErr != null && previousBinds != null)
                {
                    Log.Warn($"cannot listen on the new addresses ({startErr}); keeping {string.Join(", ", previousBinds)}");
                    await app.DisposeAsync();
                    _binds = previousBinds;
                    previousBinds = null;
                    continue;
                }
                previousBinds = null;
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
                if (_stopRequested) break;
                if (_rebindPending && !_handoffPending)
                {
                    _rebindPending = false;
                    previousBinds = _rebindFrom;
                    retryBind = true;
                    continue;
                }
                if (!_handoffPending) break;
                _handoffPending = false;
                if (await HandoffAsync()) break;
                retryBind = true; // rolled back: re-open listeners (port was just released by us)
            }
        }
        finally
        {
            stopWait.Unregister(null);
            Log.Info("shutting down: terminating running sessions");
            Dispose();
        }
        return exitCode;
    }

    /// <summary>Stops discovery and releases the update service, external consoles and sessions. Idempotent.</summary>
    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        if (_autoBinds) NetworkChange.NetworkAddressChanged -= OnNetworkAddressChanged;
        StopDiscovery();
        _bgCts.Cancel();
        _bgCts.Dispose();
        // Null when RunAsync never got far enough to create them.
        _recent?.Flush();
        _update?.Dispose();
        _externals?.Dispose();
        _sessions?.Dispose();
    }

    private void OnNetworkAddressChanged(object? sender, EventArgs e)
    {
        try { _addressChanged.Release(); }
        catch (SemaphoreFullException) { /* a check is already queued */ }
    }

    /// <summary>
    /// Listeners are bound per address, so an interface that comes up after start (Tailscale connecting after login,
    /// Wi-Fi changing) is unreachable until they are re-opened. On an address change (or every 30 s as a fallback),
    /// re-resolve the bind set and, if it differs, stop Kestrel so RunAsync re-opens it on the new set. Sessions keep
    /// running; phones reconnect.
    /// </summary>
    private async Task WatchAddressesAsync(CancellationToken ct)
    {
        try
        {
            while (true)
            {
                await _addressChanged.WaitAsync(TimeSpan.FromSeconds(30), ct);
                await Task.Delay(TimeSpan.FromSeconds(3), ct); // let DHCP / Tailscale settle
                var app = _app;
                if (app == null || _handoffPending || _rebindPending || _stopRequested) continue;
                var current = _binds;
                List<IPAddress> fresh;
                try { fresh = HostAddresses.ResolveBindAddresses(Array.Empty<string>()); }
                catch (NetworkInformationException ex) { Log.Warn($"address check failed: {ex.Message}"); continue; }
                if (HostAddresses.SameSet(current, fresh)) continue;
                Log.Info($"network addresses changed: {string.Join(", ", current)} -> {string.Join(", ", fresh)}; re-opening listeners");
                _rebindFrom = current;
                _binds = fresh;
                _rebindPending = true;
                await Task.WhenAll(_conns.Keys.ToList().Select(c => c.CloseAsync(1012, "host network changed")));
                app.Lifetime.StopApplication();
            }
        }
        catch (OperationCanceledException) { /* shutting down */ }
        catch (ObjectDisposedException) { /* shutting down */ }
    }

    /// <summary>
    /// Follows what the AI CLIs are doing (DESIGN §14): once a second re-evaluates every AI session and records an event
    /// when one stops (waiting on a question, or done), which phones pick up from GET /api/events.
    /// </summary>
    private async Task WatchAgentsAsync(CancellationToken ct)
    {
        using var timer = new PeriodicTimer(TimeSpan.FromSeconds(1));
        int tick = 0;
        try
        {
            while (await timer.WaitForNextTickAsync(ct))
            {
                try
                {
                    if (tick++ % 10 == 0) _externals.WatchNewAgents();
                    var now = DateTime.UtcNow;
                    foreach (var s in _sessions.All)
                    {
                        var stopped = s.EvaluateAgent(now);
                        if (stopped != null) RecordAgentEvent(stopped);
                    }
                    foreach (var stopped in _externals.EvaluateAgents(now)) RecordAgentEvent(stopped);
                }
                catch (Exception ex) when (ex is System.ComponentModel.Win32Exception or InvalidOperationException or IOException)
                {
                    Log.Error($"agent watch: {ex.Message}");
                }
            }
        }
        catch (OperationCanceledException) { /* shutting down */ }
    }

    private void RecordAgentEvent(SessionDto s)
    {
        if (s.AgentState == null) return;
        _events.Add(new AgentEventDto
        {
            SessionId = s.Id, Title = s.Title, Agent = s.Agent, State = s.AgentState, Prompt = s.Prompt, Preview = s.AgentPreview,
            Time = Ids.IsoNow(),
        });
        Log.Info($"agent {s.Id} ({s.Agent}): {s.AgentState}{(s.Prompt != null ? $" [{s.Prompt.Options.Count} choices]" : "")}");
    }

    /// <summary>How many AI sessions are working / waiting / done right now.</summary>
    private object AgentSummary()
    {
        var states = _sessions.All.Select(s => s.Snapshot().AgentState).Concat(_externals.AgentStates()).ToList();
        return new
        {
            working = states.Count(s => s == AgentStates.Working),
            waiting = states.Count(s => s == AgentStates.Waiting),
            done = states.Count(s => s == AgentStates.Done),
        };
    }

    /// <summary>Keeps the 최근 list current while no phone is polling: a light console snapshot every 20 s.</summary>
    private async Task WatchRecentAsync(CancellationToken ct)
    {
        using var timer = new PeriodicTimer(TimeSpan.FromSeconds(20));
        try
        {
            do
            {
                try
                {
                    _recent.Observe(ExternalConsoles.Peek());
                    _sessions.NoteRunningAgents();
                }
                catch (Exception ex) when (ex is System.ComponentModel.Win32Exception or InvalidOperationException or IOException)
                {
                    Log.Error($"recent consoles: {ex.Message}");
                }
            }
            while (await timer.WaitForNextTickAsync(ct));
        }
        catch (OperationCanceledException) { /* shutting down */ }
    }

    /// <summary>최근 PC console → new host session in its folder, continuing the AI conversation it ran.</summary>
    private IResult ResumeRecent(string id)
    {
        var e = _recent.Get(id);
        if (e == null) return Error(404, "session not found");
        if (!Directory.Exists(e.Cwd))
        {
            _recent.Remove(id);
            return Error(400, $"폴더가 없습니다: {e.Cwd}");
        }
        try
        {
            var s = _sessions.Create(new CreateSessionRequest { Shell = e.Shell.Length > 0 ? e.Shell : null, Cwd = e.Cwd, Title = e.Title });
            var cmd = RecentExternals.ResumeCommand(e.Agent);
            if (cmd != null) s.WriteInput(Encoding.UTF8.GetBytes(cmd + "\r"));
            _recent.Remove(id);
            Log.Info($"recent: reopened {e.Cwd} ({e.Agent ?? "shell"}) as session {s.Id}");
            return Json(s.Snapshot());
        }
        catch (Exception ex) when (ex is InvalidOperationException or System.ComponentModel.Win32Exception)
        {
            Log.Error($"reopen {id} failed: {ex.Message}");
            return Error(400, ex.Message);
        }
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

    /// <summary>
    /// Installed with PocketShell-Setup: Windows' app list shows the version the installer wrote. Auto-updates replace
    /// the files without the installer, so keep that entry's version current (only when it is this install folder).
    /// </summary>
    private void SyncInstalledVersion()
    {
        const string key = @"Software\Microsoft\Windows\CurrentVersion\Uninstall\{6B0E4C1F-6A63-4C8B-9B53-5C2E1E7A9D41}_is1"; // AppId in installer\PocketShell.iss
        try
        {
            using var k = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(key, writable: true);
            if (k?.GetValue("InstallLocation") is not string location) return;
            if (!string.Equals(location.TrimEnd('\\'), _installDir.TrimEnd('\\'), StringComparison.OrdinalIgnoreCase)) return;
            if (k.GetValue("DisplayVersion") as string == Version) return;
            k.SetValue("DisplayVersion", Version);
            Log.Info($"installed apps entry: version set to {Version}");
        }
        catch (Exception ex) when (ex is System.Security.SecurityException or UnauthorizedAccessException or IOException)
        {
            Log.Warn("installed apps entry not updated: " + ex.Message);
        }
    }

    /// <summary>Returns how many backups are still locked.</summary>
    private int CleanupOldInstallFiles(string when)
    {
        var (deleted, remaining) = InstallTransaction.CleanupOld(_installDir);
        if (deleted + remaining > 0) Log.Info($"update cleanup ({when}): deleted {deleted} *.old file(s), {remaining} still locked");
        try
        {
            int obsolete = InstallTransaction.RemoveObsolete(_installDir);
            if (obsolete > 0) Log.Info($"update cleanup ({when}): deleted {obsolete} file(s) this version no longer ships");
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { Log.Warn($"update cleanup ({when}): {ex.Message}"); }
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
    /// <remarks>
    /// Applying ends the running host sessions, so it is refused (409, with the sessions it would end) unless
    /// <paramref name="force"/> is set; and when an AI CLI is in the middle of a turn in one of them, unless
    /// <paramref name="killWorking"/> is set too: a second, separate yes.
    /// </remarks>
    private (int Status, object Body) TryBeginApply(bool force, bool killWorking)
    {
        if (!_update.HasNewer) return (400, new { error = "no update available", update = _update.Status });
        var running = _sessions.All.Where(s => s.IsRunning).Select(s => s.Snapshot()).ToList();
        int working = running.Count(s => s.AgentState == AgentStates.Working);
        object Refused(string error) => new
        {
            error,
            runningSessions = running.Count,
            workingAgents = working,
            sessions = running.Select(s => new { id = s.Id, title = s.Title, agent = s.Agent, agentState = s.AgentState }),
        };
        if (running.Count > 0 && !force) return (409, Refused($"{running.Count} host session(s) running; close them or use force"));
        if (working > 0 && !killWorking) return (409, Refused($"{working} AI session(s) are in the middle of a turn; wait for them or confirm with killWorking"));
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
    /// Runs with Kestrel stopped (port free): swap install files (old → *.old), launch the new `pkshell serve` with the same
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
                psi.Environment[UpdateParentPidEnv] = Environment.ProcessId.ToString(System.Globalization.CultureInfo.InvariantCulture);
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

    private static IResult Json(object o, int status = 200) => Results.Json(o, Pkshell.Core.Util.Json.Options, statusCode: status);
    private static IResult Error(int status, string msg) => Results.Json(new { error = msg }, Pkshell.Core.Util.Json.Options, statusCode: status);

    private void MapApi(WebApplication app)
    {
        app.MapGet("/api/info", () => Json(new { name = Environment.MachineName, version = Version, fp = _fp, port = _port, hosts = PublicHosts(), update = _update.Status, agents = AgentSummary() }));

        // Long poll: events after `after` (a seq from an earlier reply), waiting up to `wait` seconds for the first one.
        // A different `epoch` than the caller remembers means the host restarted and seq started over.
        app.MapGet("/api/events", async (HttpContext ctx) =>
        {
            long after = long.TryParse(ctx.Request.Query["after"].ToString(), out var a) ? a : -1;
            int wait = int.TryParse(ctx.Request.Query["wait"].ToString(), out var w) ? Math.Clamp(w, 0, 50) : 0;
            // First call (after < 0): only tell where the log is now, never replay old events.
            var events = after < 0 ? new List<AgentEventDto>() : await _events.WaitAfterAsync(after, TimeSpan.FromSeconds(wait), ctx.RequestAborted);
            return Json(new { epoch = _events.Epoch, seq = _events.LastSeq, events });
        });

        app.MapGet("/api/update", () => Json(_update.Status));
        app.MapPost("/api/update/check", async () => Json(await _update.CheckAsync()));
        app.MapPost("/api/update/apply", async (HttpContext ctx) =>
        {
            var req = await ReadBody<ApplyRequest>(ctx);
            var auth = AuthOf(ctx);
            // Who asked matters afterwards: a forced apply ends the running sessions.
            Log.Info($"update: apply requested by {(auth.IsLocal ? "this PC (tray / psh update)" : $"device {auth.DeviceId}")} (force={req?.Force == true}, killWorking={req?.KillWorking == true})");
            var (status, body) = TryBeginApply(req?.Force == true, req?.KillWorking == true);
            return Json(body, status);
        });

        app.MapGet("/api/sessions", () =>
        {
            var list = _sessions.All.Select(s => s.Snapshot())
                .OrderByDescending(s => s.State == "running").ThenByDescending(s => s.LastActivity, StringComparer.Ordinal)
                .ToList();
            var externals = _externals.List();
            _recent.Observe(externals);
            list.AddRange(externals);
            list.AddRange(_recent.Closed());
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

        // The screen as the host reads it for the agent state: plain text rows (to check what a prompt looked like).
        app.MapGet("/api/sessions/{id}/screen", (string id) =>
        {
            string? text = TryExtPid(id, out var pid) ? _externals.ScreenText(pid) : _sessions.Get(id)?.ScreenText();
            return text != null ? Results.Text(text, "text/plain", Encoding.UTF8) : Error(404, "session not found (or a console that is not being read)");
        });

        // Quick reply without opening the terminal (notification buttons): text typed into the session, optional Enter.
        app.MapPost("/api/sessions/{id}/input", async (HttpContext ctx, string id) =>
        {
            var req = await ReadBody<InputRequest>(ctx);
            var text = req?.Text ?? "";
            bool enter = req?.Enter == true;
            if ((text.Length == 0 && !enter) || text.Length > 4000) return Error(400, "text must be 0..4000 characters (or enter)");
            Func<byte[], bool> write;
            if (TryExtPid(id, out var pid)) write = d => _externals.TryWriteInput(pid, d);
            else
            {
                var s = _sessions.Get(id);
                if (s == null || !s.IsRunning) return Error(404, "session not found");
                write = d => { s.WriteInput(d); return true; };
            }
            if (text.Length > 0 && !write(Encoding.UTF8.GetBytes(text))) return Error(404, "session not found");
            if (enter)
            {
                // Enter goes separately, a moment later: a TUI reading "text + CR" in one chunk takes it for a paste and
                // keeps the line break instead of submitting.
                if (text.Length > 0) await Task.Delay(150);
                if (!write(Encoding.UTF8.GetBytes("\r"))) return Error(404, "session not found");
            }
            return Results.NoContent();
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
            if (id.StartsWith(RecentExternals.IdPrefix, StringComparison.Ordinal)) return ResumeRecent(id);
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

        app.MapDelete("/api/sessions/{id}", async (HttpContext ctx, string id) =>
        {
            if (TryExtPid(id, out _)) return Error(400, "external sessions cannot be deleted");
            var by = AuthOf(ctx).DeviceId ?? "local";
            if (id.StartsWith(RecentExternals.IdPrefix, StringComparison.Ordinal))
            {
                if (!_recent.Remove(id)) return Error(404, "session not found");
                Log.Info($"recent {id} deleted by {by}");
                return Results.NoContent();
            }
            // A running session is terminated and its history removed (waits for the shell to exit).
            if (!await Task.Run(() => _sessions.Delete(id))) return Error(404, "session not found");
            Log.Info($"session {id} deleted by {by}");
            return Results.NoContent();
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

        // Phone app update over the LAN (DESIGN.md §11.4): the APK shipped next to psh.exe in the release zip.
        app.MapGet("/api/app/android", () =>
        {
            var apk = Pkshell.Core.Update.AndroidApk.Find(AppContext.BaseDirectory);
            if (apk == null) return Error(404, "이 PC 서버에는 폰 앱 설치 파일이 없습니다. PC 서버를 업데이트하세요");
            return Json(new { version = apk.Version, name = apk.Name, size = apk.Size, sha256 = apk.Sha256 });
        });
        app.MapGet("/api/app/android/apk", () =>
        {
            var apk = Pkshell.Core.Update.AndroidApk.Find(AppContext.BaseDirectory);
            if (apk == null) return Error(404, "이 PC 서버에는 폰 앱 설치 파일이 없습니다. PC 서버를 업데이트하세요");
            return Results.File(apk.Path, "application/vnd.android.package-archive", apk.Name, enableRangeProcessing: true);
        });

        // Phone project file browser (DESIGN.md §4): list / download / upload by absolute PC path.
        app.MapGet("/api/files", (string? path) =>
        {
            var dir = ProjectFiles.Normalize(path);
            if (dir == null) return Error(400, "절대 경로가 필요합니다");
            try { return Json(ProjectFiles.List(dir)); }
            catch (DirectoryNotFoundException) { return Error(404, $"폴더가 없습니다: {dir}"); }
            catch (UnauthorizedAccessException) { return Error(403, $"접근 권한이 없습니다: {dir}"); }
            catch (IOException ex) { return Error(400, ex.Message); }
        });

        app.MapGet("/api/files/content", (string? path) =>
        {
            var file = ProjectFiles.Normalize(path);
            if (file == null) return Error(400, "절대 경로가 필요합니다");
            if (!File.Exists(file)) return Error(404, $"파일이 없습니다: {file}");
            return Results.File(file, "application/octet-stream", Path.GetFileName(file), enableRangeProcessing: true);
        });

        app.MapPost("/api/files/upload", async (HttpContext ctx, string? dir, string? name, string? overwrite) =>
        {
            var folder = ProjectFiles.Normalize(dir);
            if (folder == null) return Error(400, "절대 경로가 필요합니다");
            var invalid = ProjectFiles.ValidateName(name);
            if (invalid != null || name == null) return Error(400, invalid ?? "파일 이름이 없습니다");
            var limit = ctx.Features.Get<Microsoft.AspNetCore.Http.Features.IHttpMaxRequestBodySizeFeature>();
            if (limit is { IsReadOnly: false }) limit.MaxRequestBodySize = ProjectFiles.MaxUploadBytes;
            try
            {
                var saved = await ProjectFiles.SaveAsync(folder, name, ctx.Request.Body, overwrite == "1", ctx.RequestAborted);
                var size = new FileInfo(saved).Length;
                Log.Info($"files: uploaded {saved} ({size} bytes) by {AuthOf(ctx).DeviceId ?? "local"}");
                return Json(new { path = saved, size });
            }
            catch (FileExistsException ex) { return Error(409, ex.Message); }
            catch (DirectoryNotFoundException) { return Error(404, $"폴더가 없습니다: {folder}"); }
            catch (InvalidDataException ex) { return Error(413, ex.Message); }
            catch (UnauthorizedAccessException) { return Error(403, $"쓰기 권한이 없습니다: {folder}"); }
            catch (IOException ex) { return Error(400, ex.Message); }
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
            // Code mode: no UI uses it any more (phones pair by PIN), but local tooling (QA E2E) mints device tokens with it.
            var (code, exp) = _pairing.Start();
            Log.Info($"pairing code issued, expires {Ids.IsoUtc(exp)}");
            return Json(new { code, expiresAt = Ids.IsoUtc(exp) });
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
        /// <summary>With <see cref="Force"/>: also end sessions whose AI CLI is working right now.</summary>
        public bool KillWorking { get; set; }
    }

    private sealed class InputRequest
    {
        public string? Text { get; set; }
        public bool Enter { get; set; }
    }

    private static async Task<T?> ReadBody<T>(HttpContext ctx) where T : class
    {
        try
        {
            using var reader = new StreamReader(ctx.Request.Body, Encoding.UTF8);
            var text = await reader.ReadToEndAsync();
            if (string.IsNullOrWhiteSpace(text)) return null;
            return JsonSerializer.Deserialize<T>(text, Pkshell.Core.Util.Json.Options);
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
            await ReceiveLoop(conn,
                data => s.WriteInput(data),
                (nc, nr) => s.ResizeClient(a.Client, nc, nr),
                cts.Token);
            cts.Cancel();
            try { await sender; } catch (OperationCanceledException) { }
        }
        finally
        {
            s.Detach(a);
            Log.Info($"ws detach {s.Id}: client={clientType}/{a.Client.Id}");
        }
    }

    private static async Task RunExternalWs(WsConn conn, ExternalBridge b, SessionDto info, string clientType, int cols, int rows, CancellationTokenSource cts)
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
            await ReceiveLoop(conn, data => b.WriteInput(data), (_, _) => { /* size policy not applied to external consoles */ }, cts.Token);
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

    /// <summary>PKSHELL_LOG_INPUT=1 logs every inbound input frame (hex + text) — for diagnosing client input problems only.</summary>
    private static readonly bool LogInput = Pkshell.Core.Util.EnvVars.Get("LOG_INPUT") == "1";

    private static async Task ReceiveLoop(WsConn conn, Action<byte[]> onInput, Action<int, int> onResize, CancellationToken ct)
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
