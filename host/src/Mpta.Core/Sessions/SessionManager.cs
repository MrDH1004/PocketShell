using System.Collections.Concurrent;
using System.Text;
using Mpta.Core.Util;

namespace Mpta.Core.Sessions;

public sealed class SessionManager : IDisposable
{
    public const int MaxHistory = 50;
    public const int DefaultCols = 120;
    public const int DefaultRows = 30;

    private readonly ConcurrentDictionary<string, HostSession> _sessions = new(StringComparer.Ordinal);
    private readonly string _root;
    private readonly Timer _flushTimer;
    private readonly object _historyGate = new();

    public SessionManager(string home)
    {
        _root = MptaPaths.SessionsDir(home);
        Directory.CreateDirectory(_root);
        LoadHistory();
        _flushTimer = new Timer(_ => FlushAll(), null, TimeSpan.FromSeconds(5), TimeSpan.FromSeconds(5));
    }

    private void LoadHistory()
    {
        foreach (var dir in Directory.EnumerateDirectories(_root))
        {
            var s = HostSession.LoadHistory(dir);
            if (s != null) _sessions[s.Id] = s;
        }
        EnforceHistoryCap();
        Log.Info($"loaded {_sessions.Count} history session(s) from {_root}");
    }

    public IEnumerable<HostSession> All => _sessions.Values;

    public HostSession? Get(string id) => _sessions.TryGetValue(id, out var s) ? s : null;

    public IReadOnlyCollection<int> RunningShellPids =>
        _sessions.Values.Where(s => s.IsRunning).Select(s => s.ShellPid).OfType<int>().ToList();

    public HostSession Create(CreateSessionRequest req) => Create(req, null, null);

    private HostSession Create(CreateSessionRequest req, string? resumedFrom, byte[]? initialOutput)
    {
        var launch = ShellLauncher.Resolve(req.Shell, req.Cwd);
        int cols = req.Cols is > 0 ? SizePolicy.Clamp(req.Cols.Value) : DefaultCols;
        int rows = req.Rows is > 0 ? SizePolicy.Clamp(req.Rows.Value) : DefaultRows;
        var title = string.IsNullOrWhiteSpace(req.Title) ? launch.Shell : req.Title!;
        var s = HostSession.Start(_root, launch, title, cols, rows, resumedFrom, initialOutput);
        s.Exited += OnSessionExited;
        _sessions[s.Id] = s;
        return s;
    }

    private void OnSessionExited(HostSession s) => EnforceHistoryCap();

    /// <summary>history → new host session with the same shell/cwd/title; old output replayed first.</summary>
    public HostSession Resume(string historyId)
    {
        var old = Get(historyId) ?? throw new KeyNotFoundException(historyId);
        if (old.IsRunning) throw new InvalidOperationException("session is running; only history sessions can be resumed");
        var meta = old.Snapshot();
        var prev = old.ReadScrollback();
        var marker = Encoding.UTF8.GetBytes($"\r\n\x1b[0m\x1b[2m── resumed {Ids.IsoNow()} ──\x1b[0m\r\n");
        var initial = new byte[prev.Length + marker.Length];
        Buffer.BlockCopy(prev, 0, initial, 0, prev.Length);
        Buffer.BlockCopy(marker, 0, initial, prev.Length, marker.Length);
        var s = Create(new CreateSessionRequest { Shell = meta.Shell, Cwd = meta.Cwd, Title = meta.Title, Cols = meta.Cols, Rows = meta.Rows }, historyId, initial);
        DeleteHistory(old);
        return s;
    }

    /// <summary>running → kill; history → delete. Returns false if not found.</summary>
    public bool Delete(string id)
    {
        var s = Get(id);
        if (s == null) return false;
        if (s.IsRunning) { s.Kill(); return true; }
        DeleteHistory(s);
        return true;
    }

    private void DeleteHistory(HostSession s)
    {
        _sessions.TryRemove(s.Id, out _);
        s.Dispose();
        try { Directory.Delete(s.Dir, true); }
        catch (IOException ex) { Log.Warn($"failed to delete {s.Dir}: {ex.Message}"); }
        catch (UnauthorizedAccessException ex) { Log.Warn($"failed to delete {s.Dir}: {ex.Message}"); }
    }

    private void EnforceHistoryCap()
    {
        lock (_historyGate)
        {
            var history = _sessions.Values.Where(s => !s.IsRunning)
                .Select(s => (s, last: s.Snapshot().LastActivity))
                .OrderByDescending(x => x.last, StringComparer.Ordinal)
                .ToList();
            foreach (var (s, _) in history.Skip(MaxHistory))
            {
                Log.Info($"history cap: removing {s.Id}");
                DeleteHistory(s);
            }
        }
    }

    private void FlushAll()
    {
        foreach (var s in _sessions.Values)
        {
            try { s.FlushMetaIfDirty(); }
            catch (Exception ex) { Log.Error($"meta flush {s.Id} failed: {ex.Message}"); }
        }
    }

    private bool _disposed;

    /// <summary>Terminates running shells (they become history) and closes every session's files (output.vt), so another
    /// process — e.g. the new host after a self-update handoff — can open the sessions directory.</summary>
    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        _flushTimer.Dispose();
        // Terminate running shells so they become history entries with a clean meta.json.
        var running = _sessions.Values.Where(s => s.IsRunning).ToList();
        foreach (var s in running) s.Kill();
        var deadline = DateTime.UtcNow.AddSeconds(5);
        while (running.Any(s => s.IsRunning) && DateTime.UtcNow < deadline) Thread.Sleep(50);
        FlushAll();
        foreach (var s in _sessions.Values) s.Dispose();
    }
}
