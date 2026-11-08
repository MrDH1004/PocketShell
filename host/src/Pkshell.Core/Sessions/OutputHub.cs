using System.Text;
using System.Threading.Channels;

namespace Pkshell.Core.Sessions;

/// <summary>Append-only output log (output.vt) with front trimming at a newline boundary.</summary>
public sealed class ScrollbackFile : IDisposable
{
    public const long DefaultMaxBytes = 8L * 1024 * 1024;
    public const long DefaultKeepBytes = 4L * 1024 * 1024;

    private readonly FileStream _fs;
    private readonly long _max;
    private readonly long _keep;

    public string Path { get; }
    public long Length => _fs.Length;

    public ScrollbackFile(string path, long maxBytes = DefaultMaxBytes, long keepBytes = DefaultKeepBytes)
    {
        Path = path;
        _max = maxBytes;
        _keep = keepBytes;
        Directory.CreateDirectory(System.IO.Path.GetDirectoryName(path)!);
        _fs = new FileStream(path, FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.Read, 1, FileOptions.None);
        _fs.Seek(0, SeekOrigin.End);
    }

    public void Append(ReadOnlySpan<byte> data)
    {
        _fs.Write(data);
        _fs.Flush();
        if (_fs.Length > _max) Trim();
    }

    public byte[] ReadAll()
    {
        _fs.Flush();
        var len = _fs.Length;
        var buf = new byte[len];
        int read = 0;
        while (read < len)
        {
            int n = RandomAccess.Read(_fs.SafeFileHandle, buf.AsSpan(read), read);
            if (n <= 0) break;
            read += n;
        }
        return read == len ? buf : buf[..read];
    }

    private void Trim()
    {
        var all = ReadAll();
        int off = FindTrimOffset(all, _keep);
        if (off <= 0) return;
        _fs.SetLength(0);
        _fs.Seek(0, SeekOrigin.Begin);
        _fs.Write(all, off, all.Length - off);
        _fs.Flush();
    }

    /// <summary>
    /// Offset from which to keep data so that at most <paramref name="keep"/> bytes remain,
    /// starting right after the first '\n' in the kept window (or at the window start if none).
    /// </summary>
    public static int FindTrimOffset(ReadOnlySpan<byte> data, long keep)
    {
        if (data.Length <= keep) return 0;
        int start = (int)(data.Length - keep);
        int idx = data[start..].IndexOf((byte)'\n');
        return idx < 0 ? start : start + idx + 1;
    }

    public void Dispose() => _fs.Dispose();
}

public readonly record struct OutMessage(bool IsText, byte[] Data)
{
    public static OutMessage Binary(byte[] data) => new(false, data);
    public static OutMessage Text(string json) => new(true, Encoding.UTF8.GetBytes(json));
}

/// <summary>
/// Bounded per-subscriber output queue. When a consumer falls <see cref="Capacity"/> messages behind,
/// the subscriber is marked overflowed and its channel completed; the transport should then drop the
/// connection (WS close 4408) so the client reconnects and recovers via replay.
/// </summary>
public sealed class Subscriber
{
    public const int Capacity = 4096;

    private readonly Channel<OutMessage> _ch = Channel.CreateBounded<OutMessage>(
        new BoundedChannelOptions(Capacity) { SingleReader = true, FullMode = BoundedChannelFullMode.Wait });
    private readonly TaskCompletionSource _overflowed = new(TaskCreationOptions.RunContinuationsAsynchronously);

    public ChannelReader<OutMessage> Reader => _ch.Reader;
    internal ChannelWriter<OutMessage> Writer => _ch.Writer;

    /// <summary>Completes when the queue overflowed (slow consumer).</summary>
    public Task Overflowed => _overflowed.Task;
    public bool IsOverflowed => _overflowed.Task.IsCompleted;

    public void Enqueue(OutMessage m) => TryPush(m);

    /// <summary>Queues a message; on a full queue marks the subscriber overflowed and returns false.</summary>
    internal bool TryPush(OutMessage m)
    {
        if (_ch.Writer.TryWrite(m)) return true;
        if (_overflowed.TrySetResult()) _ch.Writer.TryComplete();
        return false;
    }
}

/// <summary>
/// Fan-out of output bytes to subscribers plus scrollback persistence.
/// Snapshot + subscribe happen atomically under <see cref="SyncRoot"/> so a new subscriber
/// sees every byte exactly once (snapshot, then live).
/// </summary>
public sealed class OutputHub
{
    private readonly List<Subscriber> _subs = new();
    private readonly ScrollbackFile? _store;

    public object SyncRoot { get; } = new();

    public OutputHub(ScrollbackFile? store) { _store = store; }

    public void Publish(byte[] chunk)
    {
        lock (SyncRoot)
        {
            _store?.Append(chunk);
            Fanout(OutMessage.Binary(chunk));
        }
    }

    public void PublishText(string json)
    {
        lock (SyncRoot)
        {
            Fanout(OutMessage.Text(json));
        }
    }

    /// <summary>Caller holds <see cref="SyncRoot"/>. Overflowed subscribers are dropped from the fan-out.</summary>
    private void Fanout(OutMessage m) => _subs.RemoveAll(s => !s.TryPush(m));

    public (byte[] Snapshot, Subscriber Subscriber) Subscribe()
    {
        lock (SyncRoot)
        {
            var snap = _store?.ReadAll() ?? Array.Empty<byte>();
            var sub = new Subscriber();
            _subs.Add(sub);
            return (snap, sub);
        }
    }

    public void Unsubscribe(Subscriber sub)
    {
        lock (SyncRoot)
        {
            _subs.Remove(sub);
            sub.Writer.TryComplete();
        }
    }

    public int Count { get { lock (SyncRoot) return _subs.Count; } }

    public void CompleteAll()
    {
        lock (SyncRoot)
        {
            foreach (var s in _subs) s.Writer.TryComplete();
            _subs.Clear();
        }
    }
}
