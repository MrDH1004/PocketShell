using System.Text;
using System.Text.Json;
using Mpta.Core.Pty;
using Mpta.Core.Util;

namespace Mpta.Core.Sessions;

/// <summary>A ConPTY-backed session (running) or its persisted remains (history).</summary>
public sealed class HostSession : IDisposable
{
    private readonly SessionDto _meta;          // guarded by Hub.SyncRoot
    private readonly List<ClientDto> _clients = new();
    private readonly OscCwdParser _cwdParser = new();
    private readonly ScrollbackFile _scrollback;
    private PseudoConsoleProcess? _pty;
    private int? _exitCode;
    private volatile bool _dirty;

    public string Id { get; }
    public string Dir { get; }
    public OutputHub Hub { get; }
    public event Action<HostSession>? Exited;

    public bool IsRunning { get { lock (Hub.SyncRoot) return _meta.State == "running"; } }
    public int? ShellPid => _pty?.Pid;

    private HostSession(string dir, SessionDto meta)
    {
        Dir = dir;
        Id = meta.Id;
        _meta = meta;
        _scrollback = new ScrollbackFile(Path.Combine(dir, "output.vt"));
        Hub = new OutputHub(_scrollback);
    }

    public static string MetaPath(string dir) => Path.Combine(dir, "meta.json");

    /// <summary>Loads a persisted session from disk as history (any non-exited state is converted to exited).</summary>
    public static HostSession? LoadHistory(string dir)
    {
        var metaPath = MetaPath(dir);
        if (!File.Exists(metaPath)) return null;
        SessionDto? meta;
        try { meta = JsonSerializer.Deserialize<SessionDto>(File.ReadAllText(metaPath, Encoding.UTF8), Json.Options); }
        catch (JsonException ex) { Log.Warn($"skip session {dir}: bad meta.json ({ex.Message})"); return null; }
        if (meta == null || string.IsNullOrEmpty(meta.Id)) return null;
        meta.State = "exited";
        meta.Kind = "history";
        meta.Clients = new();
        meta.Pid = null;
        var s = new HostSession(dir, meta);
        s.SaveMeta();
        return s;
    }

    /// <summary>Creates and starts a new ConPTY session.</summary>
    public static HostSession Start(string sessionsRoot, ShellLaunch launch, string title, int cols, int rows, string? resumedFrom, byte[]? initialOutput)
    {
        var id = Ids.NewHexId(12);
        var dir = Path.Combine(sessionsRoot, id);
        Directory.CreateDirectory(dir);
        var now = Ids.IsoNow();
        var meta = new SessionDto
        {
            Id = id, Kind = "host", Title = title, Shell = launch.Shell, Cwd = launch.WorkingDirectory,
            State = "running", CreatedAt = now, LastActivity = now, Cols = cols, Rows = rows,
            ResumedFrom = resumedFrom,
        };
        var s = new HostSession(dir, meta);
        if (initialOutput is { Length: > 0 }) s._scrollback.Append(initialOutput);
        s.SaveMeta();
        try
        {
            s._pty = PseudoConsoleProcess.Start(launch.CommandLine, launch.WorkingDirectory, launch.Env, cols, rows, s.OnOutput, s.OnExit);
        }
        catch
        {
            s.Dispose();
            try { Directory.Delete(dir, true); } catch (IOException) { } catch (UnauthorizedAccessException) { }
            throw;
        }
        Log.Info($"session {id} started: shell={launch.Shell} pid={s._pty.Pid} cwd={launch.WorkingDirectory} size={cols}x{rows} cmd={launch.CommandLine}");
        return s;
    }

    private void OnOutput(byte[] chunk)
    {
        lock (Hub.SyncRoot)
        {
            Hub.Publish(chunk);
            _meta.LastActivity = Ids.IsoNow();
            var cwd = _cwdParser.Feed(chunk);
            if (cwd != null && cwd != _meta.Cwd) _meta.Cwd = cwd;
            _dirty = true;
        }
    }

    private void OnExit(int code)
    {
        lock (Hub.SyncRoot)
        {
            _exitCode = code;
            _meta.State = "exited";
            _meta.Kind = "history";
            _meta.Pid = null;
            _meta.LastActivity = Ids.IsoNow();
            _clients.Clear();
            _meta.Clients = new();
            Hub.PublishText(ControlMessages.Exit(code));
        }
        SaveMeta();
        Log.Info($"session {Id} exited with code {code}");
        _pty?.Dispose();
        try { Exited?.Invoke(this); }
        catch (Exception ex) { Log.Error($"session {Id} exit handler failed: {ex}"); }
    }

    public SessionDto Snapshot()
    {
        lock (Hub.SyncRoot)
        {
            var dto = _meta.Clone();
            dto.Kind = dto.State == "running" ? "host" : "history";
            dto.Clients = _clients.Select(c => new ClientDto { Id = c.Id, Type = c.Type, Cols = c.Cols, Rows = c.Rows }).ToList();
            return dto;
        }
    }

    public sealed record Attachment(SessionDto Hello, byte[] Replay, Subscriber Subscriber, ClientDto Client, int? ExitCode);

    /// <summary>Atomically registers the client, recomputes PTY size, snapshots scrollback and subscribes to live output.</summary>
    public Attachment Attach(string clientType, int cols, int rows)
    {
        lock (Hub.SyncRoot)
        {
            var client = new ClientDto { Id = Ids.NewHexId(8), Type = clientType, Cols = cols, Rows = rows };
            bool running = _meta.State == "running";
            if (running)
            {
                _clients.Add(client);
                ApplySizePolicyLocked();
            }
            var (replay, sub) = Hub.Subscribe();
            var hello = Snapshot();
            // Size notice for the new client follows replay-end (queued behind nothing: queue is fresh).
            if (_exitCode is int code) sub.Enqueue(OutMessage.Text(ControlMessages.Exit(code)));
            return new Attachment(hello, replay, sub, client, _exitCode);
        }
    }

    public void Detach(Attachment a)
    {
        lock (Hub.SyncRoot)
        {
            Hub.Unsubscribe(a.Subscriber);
            if (_clients.Remove(a.Client) && _meta.State == "running") ApplySizePolicyLocked();
        }
    }

    public void ResizeClient(ClientDto client, int cols, int rows)
    {
        lock (Hub.SyncRoot)
        {
            client.Cols = cols;
            client.Rows = rows;
            if (_meta.State == "running" && _clients.Contains(client)) ApplySizePolicyLocked();
        }
    }

    public (int Cols, int Rows) CurrentSize { get { lock (Hub.SyncRoot) return (_meta.Cols, _meta.Rows); } }

    private void ApplySizePolicyLocked()
    {
        var (c, r) = SizePolicy.Compute(_clients.Select(x => (x.Cols, x.Rows)), (_meta.Cols, _meta.Rows));
        if (c == _meta.Cols && r == _meta.Rows) return;
        _meta.Cols = c;
        _meta.Rows = r;
        _dirty = true;
        _pty?.Resize(c, r);
        Hub.PublishText(ControlMessages.Size(c, r));
    }

    public bool HasClientOfType(string type)
    {
        lock (Hub.SyncRoot) return _clients.Any(c => c.Type == type);
    }

    public void WriteInput(ReadOnlySpan<byte> data)
    {
        var pty = _pty;
        if (pty == null || !IsRunning) return;
        pty.Write(data);
        lock (Hub.SyncRoot) { _meta.LastActivity = Ids.IsoNow(); _dirty = true; }
    }

    public void Kill() => _pty?.Kill();

    public byte[] ReadScrollback()
    {
        lock (Hub.SyncRoot) return _scrollback.ReadAll();
    }

    public void FlushMetaIfDirty()
    {
        if (_dirty) SaveMeta();
    }

    public void SaveMeta()
    {
        string json;
        lock (Hub.SyncRoot)
        {
            _dirty = false;
            var dto = _meta.Clone();
            dto.Clients = new();
            json = JsonSerializer.Serialize(dto, Json.Indented);
        }
        try { AtomicFile.WriteAllText(MetaPath(Dir), json); }
        catch (IOException ex) { Log.Error($"session {Id}: failed to write meta.json: {ex.Message}"); }
        catch (UnauthorizedAccessException ex) { Log.Error($"session {Id}: failed to write meta.json: {ex.Message}"); }
    }

    public void Dispose()
    {
        Hub.CompleteAll();
        _pty?.Dispose();
        lock (Hub.SyncRoot) _scrollback.Dispose();
    }
}
