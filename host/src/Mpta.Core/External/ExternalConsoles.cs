using System.Buffers.Binary;
using System.Collections.Concurrent;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using Mpta.Core.Native;
using Mpta.Core.Sessions;
using Mpta.Core.Util;

namespace Mpta.Core.External;

public sealed record ProcEntry(int Pid, int ParentPid, string Exe);

/// <summary>Enumerates external (not host-owned) shell consoles and bridges them through helper processes.</summary>
public sealed class ExternalConsoles : IDisposable
{
    private static readonly HashSet<string> ShellExes = new(StringComparer.OrdinalIgnoreCase) { "cmd.exe", "powershell.exe", "pwsh.exe" };

    private readonly string _helperExe;
    private readonly ConcurrentDictionary<int, ExternalBridge> _bridges = new();
    /// <summary>Per console pid; outlives bridges so a phone reopening a console gets the earlier lines.</summary>
    private readonly ConcurrentDictionary<int, ConsoleScrollback> _scrollbacks = new();
    private readonly object _cacheGate = new();
    private DateTime _cacheTime = DateTime.MinValue;
    private List<SessionDto> _cache = new();

    public ExternalConsoles(string helperExe) { _helperExe = helperExe; }

    /// <summary>Tab names per Windows Terminal window handle (set by the server; reading WT's UI needs UI Automation).</summary>
    public Func<IReadOnlyDictionary<long, IReadOnlyList<string>>>? TabNames { get; set; }

    /// <summary>MPTA_EXTERNAL_ALL=1 lists every top-level shell console (used by QA, whose test console has no launcher parent).</summary>
    private static readonly bool IncludeAll = Environment.GetEnvironmentVariable("MPTA_EXTERNAL_ALL") == "1";

    public static List<ProcEntry> SnapshotProcesses()
    {
        var list = new List<ProcEntry>();
        IntPtr snap = Win32.CreateToolhelp32Snapshot(Win32.TH32CS_SNAPPROCESS, 0);
        if (snap == IntPtr.Zero || snap == new IntPtr(-1))
        {
            Log.Error(Win32.LastError("CreateToolhelp32Snapshot").Message);
            return list;
        }
        try
        {
            var pe = new Win32.PROCESSENTRY32W { dwSize = (uint)Marshal.SizeOf<Win32.PROCESSENTRY32W>() };
            if (!Win32.Process32First(snap, ref pe)) return list;
            do list.Add(new ProcEntry((int)pe.th32ProcessID, (int)pe.th32ParentProcessID, pe.szExeFile));
            while (Win32.Process32Next(snap, ref pe));
        }
        finally { Win32.CloseHandle(snap); }
        return list;
    }

    /// <summary>Parents that mean "a person opened this terminal": terminal apps, Explorer/Run, IDE terminals.</summary>
    private static readonly HashSet<string> TerminalLaunchers = new(StringComparer.OrdinalIgnoreCase)
    {
        "WindowsTerminal.exe", "OpenConsole.exe", "explorer.exe", "Code.exe", "Cursor.exe",
        "idea64.exe", "rider64.exe", "pycharm64.exe", "wezterm-gui.exe", "alacritty.exe", "Tabby.exe",
    };

    /// <summary>AI coding CLIs shown with a tag; a shell running one is listed whatever started it.</summary>
    private static readonly Dictionary<string, string> AgentExes = new(StringComparer.OrdinalIgnoreCase)
    {
        ["claude.exe"] = "claude",
        ["codex.exe"] = "gpt",
    };

    public sealed record Candidate(ProcEntry Proc, string? Agent);

    /// <summary>
    /// Top-level shells (parent is not a shell), not descended from this process (host sessions, helpers), not self.
    /// Unless <paramref name="includeAll"/>, only shells a person opened (parent is a terminal launcher) or that run an
    /// AI CLI are kept — this drops background shells spawned by tools (MCP servers, agents' tool shells, native hosts).
    /// </summary>
    public static List<Candidate> FindCandidates(IReadOnlyList<ProcEntry> procs, int selfPid, bool includeAll = false)
    {
        var byPid = new Dictionary<int, ProcEntry>();
        var children = new Dictionary<int, List<ProcEntry>>();
        foreach (var p in procs)
        {
            byPid[p.Pid] = p;
            if (!children.TryGetValue(p.ParentPid, out var l)) children[p.ParentPid] = l = new();
            l.Add(p);
        }
        var result = new List<Candidate>();
        foreach (var p in procs)
        {
            if (!ShellExes.Contains(p.Exe) || p.Pid == selfPid) continue;
            byPid.TryGetValue(p.ParentPid, out var parent);
            if (parent != null && ShellExes.Contains(parent.Exe)) continue;
            if (IsDescendantOf(p, selfPid, byPid)) continue;
            var agent = FindAgent(p.Pid, children);
            bool launchedByPerson = parent != null && TerminalLaunchers.Contains(parent.Exe);
            if (includeAll || launchedByPerson || agent != null) result.Add(new Candidate(p, agent));
        }
        return result;
    }

    /// <summary>AI CLI ("claude" | "gpt") running under <paramref name="pid"/> in <paramref name="procs"/>, else null.</summary>
    public static string? AgentUnder(int pid, IReadOnlyList<ProcEntry> procs)
    {
        var children = new Dictionary<int, List<ProcEntry>>();
        foreach (var p in procs)
        {
            if (!children.TryGetValue(p.ParentPid, out var l)) children[p.ParentPid] = l = new();
            l.Add(p);
        }
        return FindAgent(pid, children);
    }

    /// <summary>First AI CLI found among the descendants of <paramref name="pid"/> (breadth-first).</summary>
    private static string? FindAgent(int pid, Dictionary<int, List<ProcEntry>> children)
    {
        var queue = new Queue<int>();
        var seen = new HashSet<int> { pid };
        queue.Enqueue(pid);
        while (queue.Count > 0)
        {
            if (!children.TryGetValue(queue.Dequeue(), out var kids)) continue;
            foreach (var k in kids)
            {
                if (AgentExes.TryGetValue(k.Exe, out var agent)) return agent;
                if (seen.Add(k.Pid)) queue.Enqueue(k.Pid);
            }
        }
        return null;
    }

    private static bool IsDescendantOf(ProcEntry p, int ancestor, Dictionary<int, ProcEntry> byPid)
    {
        var seen = new HashSet<int>();
        int cur = p.ParentPid;
        while (cur != 0 && seen.Add(cur))
        {
            if (cur == ancestor) return true;
            if (!byPid.TryGetValue(cur, out var e)) return false;
            cur = e.ParentPid;
        }
        return false;
    }

    public static string ShellKind(string exe) => exe.ToLowerInvariant() switch
    {
        "cmd.exe" => "cmd",
        "pwsh.exe" => "pwsh",
        "powershell.exe" => "powershell",
        _ => Path.GetFileNameWithoutExtension(exe),
    };

    private Dictionary<int, string> TabTitlesFor(List<ConsoleInfo> infos)
    {
        var source = TabNames;
        if (source == null || !infos.Any(i => i.OwnerWindow != 0)) return new Dictionary<int, string>();
        return TerminalTabTitles.Assign(infos, source());
    }

    /// <summary>
    /// Light snapshot for <see cref="RecentExternals"/>: candidate consoles with folder, shell and AI CLI only. No
    /// console-info helper and no bridges, so it is cheap to poll; titles are empty.
    /// </summary>
    public static List<SessionDto> Peek()
    {
        var candidates = FindCandidates(SnapshotProcesses(), Environment.ProcessId, IncludeAll);
        return candidates.Select(c => new SessionDto
        {
            Id = "ext-" + c.Proc.Pid,
            Kind = "external",
            Shell = ShellKind(c.Proc.Exe),
            Cwd = ProcessCwd.TryGet(c.Proc.Pid) ?? "",
            Pid = c.Proc.Pid,
            Agent = c.Agent,
        }).ToList();
    }

    /// <summary>External sessions (kind=external, id=ext-pid). Cached for 2 seconds.</summary>
    public List<SessionDto> List()
    {
        lock (_cacheGate)
        {
            if (DateTime.UtcNow - _cacheTime < TimeSpan.FromSeconds(2)) return _cache.Select(s => WithClients(s.Clone())).ToList();
        }
        var procs = SnapshotProcesses();
        var candidates = FindCandidates(procs, Environment.ProcessId, IncludeAll);
        var infos = candidates.Count == 0 ? new List<ConsoleInfo>() : QueryConsoleInfo(candidates.Select(c => c.Proc.Pid));
        var now = Ids.IsoNow();
        var tabTitles = TabTitlesFor(infos);
        var list = new List<SessionDto>();
        foreach (var info in infos)
        {
            var cand = candidates.FirstOrDefault(c => c.Proc.Pid == info.Pid);
            if (cand == null) continue;
            var proc = cand.Proc;
            list.Add(new SessionDto
            {
                Id = "ext-" + info.Pid,
                Kind = "external",
                Title = tabTitles.TryGetValue(info.Pid, out var tab) && !string.IsNullOrWhiteSpace(tab) ? tab : info.Title,
                Shell = ShellKind(proc.Exe),
                Cwd = ProcessCwd.TryGet(info.Pid) ?? "",
                State = "running",
                CreatedAt = TryStartTime(info.Pid) ?? now,
                LastActivity = now,
                Cols = info.Cols,
                Rows = info.Rows,
                Pid = info.Pid,
                Agent = cand.Agent,
            });
        }
        lock (_cacheGate)
        {
            _cache = list;
            _cacheTime = DateTime.UtcNow;
        }
        WatchAgents(list);
        return list.Select(s => WithClients(s.Clone())).ToList();
    }

    /// <summary>
    /// Consoles running Claude/GPT are read even with no client attached so their scrollback (ConsoleScrollback) is
    /// complete when a phone opens them; scrollbacks of consoles that are gone are dropped.
    /// </summary>
    private void WatchAgents(List<SessionDto> list)
    {
        var alive = list.Where(s => s.Pid != null).Select(s => s.Pid!.Value).ToHashSet();
        foreach (var pid in _scrollbacks.Keys)
            if (!alive.Contains(pid)) _scrollbacks.TryRemove(pid, out _);
        foreach (var s in list)
        {
            if (s.Agent == null || s.Pid is not int pid) continue;
            _bridges.GetOrAdd(pid, p => NewBridge(p, s)).Watch();
        }
    }

    private ExternalBridge NewBridge(int pid, SessionDto info) =>
        new(_helperExe, pid, info, _scrollbacks.GetOrAdd(pid, _ => new ConsoleScrollback()), () => _bridges.TryRemove(pid, out _));

    public SessionDto? Get(int pid)
    {
        var s = List().FirstOrDefault(x => x.Pid == pid);
        if (s != null) return s;
        // Not in cache yet (e.g. just started): query directly.
        lock (_cacheGate) _cacheTime = DateTime.MinValue;
        return List().FirstOrDefault(x => x.Pid == pid);
    }

    private SessionDto WithClients(SessionDto s)
    {
        if (s.Pid is int pid && _bridges.TryGetValue(pid, out var b)) s.Clients = b.Clients();
        return s;
    }

    private static string? TryStartTime(int pid)
    {
        try
        {
            using var p = Process.GetProcessById(pid);
            return Ids.IsoUtc(new DateTimeOffset(p.StartTime.ToUniversalTime(), TimeSpan.Zero));
        }
        catch (ArgumentException) { return null; }
        catch (InvalidOperationException) { return null; }
        catch (System.ComponentModel.Win32Exception) { return null; }
    }

    private List<ConsoleInfo> QueryConsoleInfo(IEnumerable<int> pids)
    {
        var psi = new ProcessStartInfo(_helperExe)
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            StandardOutputEncoding = Encoding.UTF8,
        };
        psi.ArgumentList.Add("console-info");
        foreach (var pid in pids) psi.ArgumentList.Add(pid.ToString(System.Globalization.CultureInfo.InvariantCulture));
        try
        {
            using var p = Process.Start(psi) ?? throw new InvalidOperationException("console-info helper failed to start");
            var stderrTask = p.StandardError.ReadToEndAsync();
            var stdoutTask = p.StandardOutput.ReadToEndAsync();
            if (!p.WaitForExit(10000))
            {
                Log.Warn("console-info helper timed out");
                try { p.Kill(); } catch (InvalidOperationException) { }
                return new();
            }
            var json = stdoutTask.GetAwaiter().GetResult();
            var err = stderrTask.GetAwaiter().GetResult();
            if (p.ExitCode != 0) Log.Warn($"console-info exit {p.ExitCode}: {err.Trim()}");
            return JsonSerializer.Deserialize<List<ConsoleInfo>>(json, Json.Options) ?? new();
        }
        catch (Exception ex) when (ex is JsonException or System.ComponentModel.Win32Exception or InvalidOperationException)
        {
            Log.Error($"console-info failed: {ex.Message}");
            return new();
        }
    }

    public ExternalBridge Bridge(int pid, SessionDto info)
    {
        while (true)
        {
            var b = _bridges.GetOrAdd(pid, p => NewBridge(p, info));
            if (b.TryAcquire()) return b;
            _bridges.TryRemove(new KeyValuePair<int, ExternalBridge>(pid, b)); // stopping; create a fresh one
        }
    }

    public void Dispose()
    {
        foreach (var b in _bridges.Values) b.Stop();
    }
}

/// <summary>One `mpta console-reader &lt;pid&gt;` helper shared by all WS clients of ext-&lt;pid&gt;.</summary>
public sealed class ExternalBridge
{
    private readonly string _helperExe;
    private readonly int _pid;
    private readonly Action _onStopped;
    private readonly OutputHub _hub = new(null);
    private readonly List<ClientDto> _clients = new();
    private readonly object _inputLock = new();
    private Process? _proc;
    private byte[]? _lastFrame;
    private (int Cols, int Rows)? _size;
    private int? _exitCode;
    private int _refs;
    private bool _stopped;
    /// <summary>Keep reading with no client (agent consoles, for the scrollback); stops only when the helper exits.</summary>
    private bool _keepAlive;
    private readonly ConsoleScrollback _scrollback;

    public SessionDto Info { get; }

    public ExternalBridge(string helperExe, int pid, SessionDto info, ConsoleScrollback scrollback, Action onStopped)
    {
        _helperExe = helperExe;
        _pid = pid;
        Info = info;
        _scrollback = scrollback;
        _onStopped = onStopped;
    }

    /// <summary>Starts reading without a client and keeps the helper running after the last client leaves.</summary>
    internal void Watch()
    {
        lock (_hub.SyncRoot)
        {
            if (_stopped || _keepAlive) return;
            _keepAlive = true;
            if (_proc == null && _exitCode == null) StartHelper();
        }
    }

    internal bool TryAcquire()
    {
        lock (_hub.SyncRoot)
        {
            if (_stopped) return false;
            _refs++;
            if (_proc == null && _exitCode == null) StartHelper();
            return true;
        }
    }

    private void StartHelper()
    {
        var psi = new ProcessStartInfo(_helperExe)
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardInput = true,
            RedirectStandardError = true,
        };
        psi.ArgumentList.Add("console-reader");
        psi.ArgumentList.Add(_pid.ToString(System.Globalization.CultureInfo.InvariantCulture));
        try
        {
            _proc = Process.Start(psi) ?? throw new InvalidOperationException("console-reader failed to start");
        }
        catch (Exception ex) when (ex is System.ComponentModel.Win32Exception or InvalidOperationException)
        {
            Log.Error($"ext-{_pid}: {ex.Message}");
            _exitCode = -1;
            _hub.PublishText(ControlMessages.Exit(-1));
            return;
        }
        Log.Info($"ext-{_pid}: console-reader helper pid={_proc.Id}");
        var proc = _proc;
        _ = Task.Run(async () =>
        {
            string err = await proc.StandardError.ReadToEndAsync();
            if (err.Length > 0) Log.Warn($"ext-{_pid} helper stderr: {err.Trim()}");
        });
        Task.Factory.StartNew(() => ReadLoop(proc), TaskCreationOptions.LongRunning);
    }

    private void ReadLoop(Process proc)
    {
        var stream = proc.StandardOutput.BaseStream;
        var lenBuf = new byte[4];
        try
        {
            while (ReadExact(stream, lenBuf))
            {
                int len = BinaryPrimitives.ReadInt32LittleEndian(lenBuf);
                if (len < 0 || len > 16 << 20) { Log.Error($"ext-{_pid}: bad frame length {len}"); break; }
                var payload = new byte[len];
                if (!ReadExact(stream, payload)) break;
                if (!ReaderFrame.TryDecode(payload, out int cols, out int rows, out var frame))
                {
                    Log.Error($"ext-{_pid}: bad frame header ({len} bytes)");
                    break;
                }
                _scrollback.Accept(frame);
                lock (_hub.SyncRoot)
                {
                    // Clients render at the console's size (their own size cannot resize it): announce changes before the frame.
                    if (_size != (cols, rows))
                    {
                        _size = (cols, rows);
                        _hub.PublishText(ControlMessages.Size(cols, rows));
                    }
                    _lastFrame = frame;
                    _hub.Publish(frame);
                }
            }
        }
        catch (IOException ex) { Log.Info($"ext-{_pid}: helper stream closed: {ex.Message}"); }
        int code = -1;
        try { proc.WaitForExit(3000); if (proc.HasExited) code = proc.ExitCode; }
        catch (InvalidOperationException) { }
        bool unused;
        lock (_hub.SyncRoot)
        {
            _exitCode = code;
            _hub.PublishText(ControlMessages.Exit(code));
            unused = _refs <= 0;
        }
        Log.Info($"ext-{_pid}: helper exited with code {code}");
        if (unused) Stop(); // a watched console went away: drop the bridge so a later attach starts fresh
    }

    /// <param name="Size">Console window size of <paramref name="Replay"/>; null until the first frame arrives.</param>
    /// <param name="History">Lines that scrolled off the window so far (oldest first), sent before the replay.</param>
    public sealed record Attachment(byte[] Replay, (int Cols, int Rows)? Size, List<string> History, Subscriber Subscriber, ClientDto Client);

    public Attachment Attach(string clientType, int cols, int rows)
    {
        lock (_hub.SyncRoot)
        {
            var client = new ClientDto { Id = Ids.NewHexId(8), Type = clientType, Cols = cols, Rows = rows };
            _clients.Add(client);
            var (_, sub) = _hub.Subscribe();
            if (_exitCode is int code) sub.Enqueue(OutMessage.Text(ControlMessages.Exit(code)));
            return new Attachment(_lastFrame ?? Array.Empty<byte>(), _size, _scrollback.Snapshot(), sub, client);
        }
    }

    public List<ClientDto> Clients()
    {
        lock (_hub.SyncRoot) return _clients.Select(c => new ClientDto { Id = c.Id, Type = c.Type, Cols = c.Cols, Rows = c.Rows }).ToList();
    }

    public void Detach(Attachment a)
    {
        bool stop;
        lock (_hub.SyncRoot)
        {
            _hub.Unsubscribe(a.Subscriber);
            _clients.Remove(a.Client);
            _refs--;
            stop = _refs <= 0 && (!_keepAlive || _exitCode != null);
        }
        if (stop) Stop();
    }

    public void WriteInput(ReadOnlySpan<byte> data)
    {
        var p = _proc;
        if (p == null || data.Length == 0) return;
        var len = new byte[4];
        BinaryPrimitives.WriteInt32LittleEndian(len, data.Length);
        try
        {
            lock (_inputLock)
            {
                var s = p.StandardInput.BaseStream;
                s.Write(len);
                s.Write(data);
                s.Flush();
            }
        }
        catch (IOException ex) { Log.Warn($"ext-{_pid}: input write failed: {ex.Message}"); }
        catch (InvalidOperationException) { }
    }

    public void Stop()
    {
        Process? p;
        lock (_hub.SyncRoot)
        {
            if (_stopped) return;
            _stopped = true;
            p = _proc;
            _hub.CompleteAll();
        }
        _onStopped();
        if (p == null) return;
        try
        {
            p.StandardInput.Close(); // helper exits on stdin EOF
            if (!p.WaitForExit(2000)) p.Kill();
        }
        catch (Exception ex) when (ex is InvalidOperationException or IOException or System.ComponentModel.Win32Exception)
        {
            Log.Warn($"ext-{_pid}: stop helper: {ex.Message}");
        }
        finally { p.Dispose(); }
    }

    private static bool ReadExact(Stream s, byte[] buf)
    {
        int off = 0;
        while (off < buf.Length)
        {
            int n = s.Read(buf, off, buf.Length - off);
            if (n <= 0) return false;
            off += n;
        }
        return true;
    }
}

/// <summary>Best-effort current directory of another (64-bit) process via its PEB.</summary>
public static class ProcessCwd
{
    // x64 offsets: PEB.ProcessParameters @0x20 (winternl.h layout);
    // RTL_USER_PROCESS_PARAMETERS.CurrentDirectory.DosPath (UNICODE_STRING) @0x38: Length(u16) @0x38, Buffer(ptr) @0x40.
    public static string? TryGet(int pid)
    {
        if (!Environment.Is64BitProcess) return null;
        IntPtr h = Win32.OpenProcess(Win32.PROCESS_QUERY_INFORMATION | Win32.PROCESS_VM_READ, false, pid);
        if (h == IntPtr.Zero) return null;
        try
        {
            var pbi = new Win32.PROCESS_BASIC_INFORMATION();
            int status = Win32.NtQueryInformationProcess(h, 0, ref pbi, Marshal.SizeOf<Win32.PROCESS_BASIC_INFORMATION>(), out _);
            if (status != 0 || pbi.PebBaseAddress == IntPtr.Zero) return null;
            var ptr = new byte[8];
            if (!Win32.ReadProcessMemory(h, pbi.PebBaseAddress + 0x20, ptr, 8, out _)) return null;
            var paramsAddr = new IntPtr(BitConverter.ToInt64(ptr, 0));
            var us = new byte[16];
            if (!Win32.ReadProcessMemory(h, paramsAddr + 0x38, us, 16, out _)) return null;
            int len = BitConverter.ToUInt16(us, 0);
            var buf = new IntPtr(BitConverter.ToInt64(us, 8));
            if (len == 0 || len > 4096 || buf == IntPtr.Zero) return null;
            var str = new byte[len];
            if (!Win32.ReadProcessMemory(h, buf, str, len, out _)) return null;
            var path = Encoding.Unicode.GetString(str);
            if (path.Length > 3 && path.EndsWith('\\')) path = path.TrimEnd('\\');
            return Directory.Exists(path) ? path : null;
        }
        finally { Win32.CloseHandle(h); }
    }
}
