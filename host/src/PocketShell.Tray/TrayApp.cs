using System.Diagnostics;
using System.Reflection;
using Mpta.Core.Util;

namespace PocketShell.Tray;

/// <summary>
/// Tray icon: keeps `psh serve` running in the background (start on launch, restart if it dies), with start/stop,
/// phone pairing (PIN), data folder and sign-in auto start in the menu.
/// </summary>
internal sealed class TrayApp : ApplicationContext
{
    private readonly string _home;
    private readonly ServerControl _server;
    private readonly NotifyIcon _icon;
    private readonly Icon _runningIcon;
    private readonly Icon _stoppedIcon;
    private readonly ToolStripMenuItem _status;
    private readonly ToolStripMenuItem _start;
    private readonly ToolStripMenuItem _stop;
    private readonly ToolStripMenuItem _autoStart;
    private readonly System.Windows.Forms.Timer _timer;
    private readonly string _version;
    /// <summary>Full informational version of this build (incl. +commit), compared with the installed PocketShell.dll.</summary>
    private readonly string _buildVersion;
    /// <summary>Hint set by a new `psh serve` after a committed update; a restart still requires a newer installed build.</summary>
    private readonly EventWaitHandle _restartEvent;
    /// <summary>Since when the installed build differs from this one (fallback restart when the hint was missed).</summary>
    private DateTime? _installedDiffersSince;
    private DateTime _nextRestartTry = DateTime.MinValue;
    private static readonly TimeSpan RestartWithoutHintAfter = TimeSpan.FromSeconds(60);
    private static readonly TimeSpan RestartRetryDelay = TimeSpan.FromSeconds(60);

    /// <summary>The user wants the server up (cleared by "서버 중지"); drives the watchdog restart.</summary>
    private bool _wantRunning = true;
    private bool _busy;
    private bool? _lastRunning;
    private int _downTicks;
    private readonly Queue<DateTime> _restarts = new();
    private PinForm? _pinForm;

    public TrayApp(string home)
    {
        _home = home;
        _server = new ServerControl(home);
        _buildVersion = typeof(TrayApp).Assembly.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion ?? "";
        _version = _buildVersion.Split('+')[0];
        _runningIcon = LoadIcon("tray-running.ico");
        _stoppedIcon = LoadIcon("tray-stopped.ico");

        _status = new ToolStripMenuItem { Enabled = false };
        _start = new ToolStripMenuItem("서버 시작", null, async (_, _) => await StartAsync(userAction: true));
        _stop = new ToolStripMenuItem("서버 중지", null, async (_, _) => await StopAsync());
        var pair = new ToolStripMenuItem("휴대폰 연결 (PIN)…", null, (_, _) => ShowPin()) { Font = new Font(SystemFonts.MenuFont ?? Control.DefaultFont, FontStyle.Bold) };
        _autoStart = new ToolStripMenuItem("Windows 시작 시 자동 실행", null, (_, _) => ToggleAutoStart());
        var menu = new ContextMenuStrip();
        menu.Items.AddRange(new ToolStripItem[]
        {
            _status, new ToolStripSeparator(),
            pair, new ToolStripMenuItem("프로젝트 폴더…", null, (_, _) => ShowProjects()), _start, _stop, new ToolStripSeparator(),
            new ToolStripMenuItem("데이터 폴더 열기", null, (_, _) => OpenFolder(_home)),
            new ToolStripMenuItem("로그 폴더 열기", null, (_, _) => OpenFolder(MptaPaths.LogsDir(_home))),
            _autoStart, new ToolStripSeparator(),
            new ToolStripMenuItem("종료 (서버도 중지)", null, async (_, _) => await ExitAsync()),
        });
        menu.Opening += (_, _) => Refresh();

        _icon = new NotifyIcon { Icon = _stoppedIcon, ContextMenuStrip = menu, Visible = true, Text = Brand.Name };
        _icon.DoubleClick += (_, _) => ShowPin();

        FirstRunAutoStart();
        _restartEvent = new EventWaitHandle(false, EventResetMode.AutoReset, Brand.TrayRestartEventName(_server.Port));
        _timer =new System.Windows.Forms.Timer { Interval = 2000 };
        _timer.Tick += async (_, _) => await TickAsync();
        _timer.Start();
        _ = StartAsync(userAction: false);
    }

    private static Icon LoadIcon(string name)
    {
        using var s = typeof(TrayApp).Assembly.GetManifestResourceStream(name)
            ?? throw new InvalidOperationException($"embedded icon {name} missing");
        return new Icon(s, SystemInformation.SmallIconSize);
    }

    /// <summary>Auto start defaults to on: enabled once on the first launch, afterwards the menu toggle decides.</summary>
    private void FirstRunAutoStart()
    {
        var flag = Path.Combine(_home, "tray-autostart-initialized");
        if (File.Exists(flag)) return;
        try
        {
            AutoStart.Set(true);
            File.WriteAllText(flag, DateTime.Now.ToString("O"));
        }
        catch (UnauthorizedAccessException ex) { TrayLog.Write(_home, "auto start init failed: " + ex.Message); }
        catch (IOException ex) { TrayLog.Write(_home, "auto start init failed: " + ex.Message); }
    }

    private void Refresh()
    {
        var running = _server.IsRunning;
        _status.Text = (running ? "● 서버 실행 중" : "○ 서버 중지됨") + $"  ·  포트 {_server.Port}" + (_version.Length > 0 ? $"  ·  v{_version}" : "");
        _start.Enabled = !running && !_busy;
        _stop.Enabled = running && !_busy;
        _autoStart.Checked = AutoStart.IsEnabled;
        _icon.Icon = running ? _runningIcon : _stoppedIcon;
        _icon.Text = $"{Brand.Name} — {(running ? "실행 중" : "중지됨")}";
        _lastRunning = running;
    }

    private async Task TickAsync()
    {
        if (_busy) return;
        if (ShouldRestartForUpdate(hinted: _restartEvent.WaitOne(0)))
        {
            RestartForUpdate();
            return;
        }
        var running = _server.IsRunning;
        if (running != _lastRunning) Refresh();
        if (running || !_wantRunning) { _downTicks = 0; return; }
        // Down while wanted (crash, or an update handoff that failed): restart after ~4 s, at most 3 times in 5 min.
        if (++_downTicks < 2) return;
        _downTicks = 0;
        while (_restarts.Count > 0 && DateTime.Now - _restarts.Peek() > TimeSpan.FromMinutes(5)) _restarts.Dequeue();
        if (_restarts.Count >= 3)
        {
            _wantRunning = false;
            _icon.ShowBalloonTip(5000, Brand.Name, "서버가 반복해서 종료되어 자동 재시작을 멈췄습니다. 로그 폴더를 확인하세요.", ToolTipIcon.Error);
            TrayLog.Write(_home, "watchdog gave up after 3 restarts in 5 min");
            return;
        }
        _restarts.Enqueue(DateTime.Now);
        TrayLog.Write(_home, "server not running: watchdog restart");
        await StartAsync(userAction: false);
    }

    private async Task StartAsync(bool userAction)
    {
        if (_busy) return;
        _busy = true;
        _wantRunning = true;
        try
        {
            var err = _server.Start();
            if (err != null)
            {
                TrayLog.Write(_home, "start failed: " + err);
                _icon.ShowBalloonTip(5000, Brand.Name, "서버를 시작하지 못했습니다: " + err, ToolTipIcon.Error);
                return;
            }
            for (int i = 0; i < 40 && !_server.IsRunning; i++) await Task.Delay(250);
            if (userAction && _server.IsRunning) _icon.ShowBalloonTip(2000, Brand.Name, "서버가 실행 중입니다.", ToolTipIcon.Info);
        }
        finally
        {
            _busy = false;
            Refresh();
        }
    }

    private async Task StopAsync()
    {
        if (_busy) return;
        _busy = true;
        _wantRunning = false;
        try
        {
            if (!await _server.StopAsync(TimeSpan.FromSeconds(10)))
                _icon.ShowBalloonTip(5000, Brand.Name, "서버가 10초 안에 종료되지 않았습니다.", ToolTipIcon.Warning);
        }
        finally
        {
            _busy = false;
            Refresh();
        }
    }

    private void ShowPin()
    {
        if (!_server.IsRunning)
        {
            _icon.ShowBalloonTip(3000, Brand.Name, "서버가 중지되어 있습니다. 먼저 서버를 시작하세요.", ToolTipIcon.Warning);
            return;
        }
        if (_pinForm is { IsDisposed: false })
        {
            _pinForm.Activate();
            return;
        }
        _pinForm = new PinForm(_home, _server.Port);
        _pinForm.FormClosed += (_, _) => _pinForm = null;
        _pinForm.Show();
        _pinForm.Activate();
    }

    private ProjectsForm? _projectsForm;

    private void ShowProjects()
    {
        if (_projectsForm is { IsDisposed: false })
        {
            _projectsForm.Activate();
            return;
        }
        _projectsForm = new ProjectsForm(_home);
        _projectsForm.FormClosed += (_, _) => _projectsForm = null;
        _projectsForm.Show();
        _projectsForm.Activate();
    }

    private void ToggleAutoStart()
    {
        try { AutoStart.Set(!AutoStart.IsEnabled); }
        catch (UnauthorizedAccessException ex) { _icon.ShowBalloonTip(3000, Brand.Name, ex.Message, ToolTipIcon.Error); }
        Refresh();
    }

    private static void OpenFolder(string dir)
    {
        Directory.CreateDirectory(dir);
        Process.Start(new ProcessStartInfo("explorer.exe", $"\"{dir}\"") { UseShellExecute = true })?.Dispose();
    }

    /// <summary>
    /// True when the installed PocketShell.dll is a different build than this process and either the server hinted that
    /// its update is committed or the difference has lasted <see cref="RestartWithoutHintAfter"/> (hint missed, port
    /// changed). A hint alone never restarts the tray, so a stray signal is harmless.
    /// </summary>
    private bool ShouldRestartForUpdate(bool hinted)
    {
        var installed = InstalledBuildVersion();
        if (installed == null || _buildVersion.Length == 0 || string.Equals(installed, _buildVersion, StringComparison.Ordinal))
        {
            if (hinted) TrayLog.Write(_home, $"restart hint ignored: installed={installed ?? "(none)"} running={_buildVersion}");
            _installedDiffersSince = null;
            return false;
        }
        var now = DateTime.UtcNow;
        if (_installedDiffersSince == null) TrayLog.Write(_home, $"installed build {installed} differs from running {_buildVersion} (hinted={hinted})");
        _installedDiffersSince ??= now;
        if (now < _nextRestartTry) return false;
        return hinted || now - _installedDiffersSince.Value >= RestartWithoutHintAfter;
    }

    /// <summary>
    /// Version of the installed psh.dll (same release, same informational version as the tray). Not PocketShell.dll:
    /// for a file loaded in this process, FileVersionInfo keeps returning the loaded (pre-update) image's version.
    /// </summary>
    private static string? InstalledBuildVersion()
    {
        var dll = Path.Combine(AppContext.BaseDirectory, Path.ChangeExtension(Brand.CliExe, ".dll"));
        try
        {
            var v = FileVersionInfo.GetVersionInfo(dll).ProductVersion;
            return string.IsNullOrEmpty(v) ? null : v;
        }
        catch (FileNotFoundException) { return null; } // mid-swap
    }

    /// <summary>
    /// The install dir now holds a newer build while this process still runs (and locks) the previous one: start the
    /// new PocketShell.exe, which waits for this process to exit, and quit without stopping the server.
    /// On failure this tray keeps running and tries again after <see cref="RestartRetryDelay"/>.
    /// </summary>
    private void RestartForUpdate()
    {
        _nextRestartTry = DateTime.UtcNow + RestartRetryDelay;
        var exe = Path.Combine(AppContext.BaseDirectory, Brand.TrayExe);
        var psi = new ProcessStartInfo(exe) { UseShellExecute = false, WorkingDirectory = AppContext.BaseDirectory };
        psi.ArgumentList.Add(Program.WaitPidArg);
        psi.ArgumentList.Add(Environment.ProcessId.ToString());
        try
        {
            using var p = Process.Start(psi);
            if (p == null) { TrayLog.Write(_home, "update restart: Process.Start returned null; keeping this tray"); return; }
            TrayLog.Write(_home, $"update installed: restarting tray (v{_version} -> new pid {p.Id})");
        }
        catch (System.ComponentModel.Win32Exception ex)
        {
            TrayLog.Write(_home, $"update restart: cannot start {exe}: {ex.Message}; keeping this tray");
            return;
        }
        _timer.Stop();
        _icon.Visible = false;
        _icon.Dispose();
        _pinForm?.Close();
        _projectsForm?.Close();
        _restartEvent.Dispose();
        ExitThread();
    }

    private async Task ExitAsync()
    {
        _timer.Stop();
        _icon.Text = $"{Brand.Name} — 종료 중…";
        await _server.StopAsync(TimeSpan.FromSeconds(10));
        _icon.Visible = false;
        _icon.Dispose();
        _pinForm?.Close();
        _projectsForm?.Close();
        ExitThread();
    }
}
