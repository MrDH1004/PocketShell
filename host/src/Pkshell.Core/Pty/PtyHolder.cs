using System.Buffers.Binary;
using System.IO.Pipes;
using System.Text;
using System.Text.Json;
using Pkshell.Core.Util;

namespace Pkshell.Core.Pty;

/// <summary>A pseudo console the host talks to: in this process, or in a holder process that outlives the host.</summary>
public interface IPty : IDisposable
{
    int Pid { get; }
    void Write(ReadOnlySpan<byte> data);
    void Resize(int cols, int rows);
    void Kill();
    /// <summary>True when the shell keeps running after this process exits (held by a <c>psh pty-hold</c> process).</summary>
    bool SurvivesHost { get; }
}

/// <summary>What a holder starts (pty.json in the session folder; the host reads the pipe name back to reattach).</summary>
public sealed class PtySpec
{
    public const string FileName = "pty.json";
    public string Pipe { get; set; } = "";
    public string CommandLine { get; set; } = "";
    public string? Cwd { get; set; }
    public Dictionary<string, string>? Env { get; set; }
    public int Cols { get; set; }
    public int Rows { get; set; }

    public static string PathIn(string sessionDir) => Path.Combine(sessionDir, FileName);

    public static PtySpec? Read(string path)
    {
        try
        {
            var s = JsonSerializer.Deserialize<PtySpec>(File.ReadAllText(path, Encoding.UTF8), Json.Options);
            return s == null || string.IsNullOrEmpty(s.Pipe) ? null : s;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or JsonException) { return null; }
    }
}

/// <summary>
/// Frames on the host ↔ holder pipe: type (1 byte) + payload length (int32 LE) + payload.
/// Holder → host: <see cref="Hello"/> (protocol version, shell pid), <see cref="Output"/>, <see cref="Exit"/> (code),
/// <see cref="Failed"/> (UTF-8 reason). Host → holder: <see cref="Input"/>, <see cref="Resize"/> (cols, rows as int32), <see cref="KillShell"/>.
/// </summary>
public static class PtyFrames
{
    public const int Version = 1;
    public const byte Hello = (byte)'H', Output = (byte)'O', Exit = (byte)'X', Failed = (byte)'F';
    public const byte Input = (byte)'I', Resize = (byte)'R', KillShell = (byte)'K';
    private const int MaxPayload = 16 * 1024 * 1024;

    public static void Write(Stream s, byte type, ReadOnlySpan<byte> payload)
    {
        var frame = new byte[5 + payload.Length];
        frame[0] = type;
        BinaryPrimitives.WriteInt32LittleEndian(frame.AsSpan(1), payload.Length);
        payload.CopyTo(frame.AsSpan(5));
        s.Write(frame);
        s.Flush();
    }

    public static byte[] Ints(params int[] values)
    {
        var b = new byte[values.Length * 4];
        for (int i = 0; i < values.Length; i++) BinaryPrimitives.WriteInt32LittleEndian(b.AsSpan(i * 4), values[i]);
        return b;
    }

    public static int Int(byte[] payload, int index) =>
        payload.Length >= (index + 1) * 4 ? BinaryPrimitives.ReadInt32LittleEndian(payload.AsSpan(index * 4)) : 0;

    /// <summary>Next frame, or null at end of stream (also for a frame cut off by the other end going away).</summary>
    public static (byte Type, byte[] Payload)? Read(Stream s)
    {
        var head = new byte[5];
        if (!ReadExactly(s, head)) return null;
        int len = BinaryPrimitives.ReadInt32LittleEndian(head.AsSpan(1));
        if (len < 0 || len > MaxPayload) throw new InvalidDataException($"pty frame length {len}");
        var payload = new byte[len];
        if (len > 0 && !ReadExactly(s, payload)) return null;
        return (head[0], payload);
    }

    private static bool ReadExactly(Stream s, byte[] buf)
    {
        int got = 0;
        while (got < buf.Length)
        {
            int n = s.Read(buf, got, buf.Length - got);
            if (n <= 0) return false;
            got += n;
        }
        return true;
    }
}

/// <summary>
/// <c>psh pty-hold &lt;pty.json&gt;</c>: owns one session's pseudo console so the shell (and the AI CLI in it) keeps running
/// while the host restarts for an update. Serves one host at a time on a current-user-only named pipe; output made while
/// no host is connected is kept (newest <see cref="MaxPending"/> bytes) and sent when the next one connects. Exits once
/// the shell has exited and a host was told, or 10 minutes after the shell exited with no host connecting.
/// </summary>
public sealed class PtyHolder
{
    public const int MaxPending = 4 * 1024 * 1024;
    public static TimeSpan ExitedWait { get; set; } = TimeSpan.FromMinutes(10);

    private readonly PtySpec _spec;
    private readonly object _gate = new();
    private readonly LinkedList<byte[]> _pending = new();
    private readonly CancellationTokenSource _exitedCts = new();
    private int _pendingBytes;
    private Stream? _conn;
    private int? _exitCode;
    private bool _exitSent;
    private IPty? _pty;
    private string? _failure;
    private NamedPipeServerStream? _server;
    /// <summary>Set by <see cref="Main"/>: ends the process if the host never closes the pipe after Exit.</summary>
    private Action? _hardExit;

    public PtyHolder(PtySpec spec) => _spec = spec;

    /// <summary>Entry point of <c>psh pty-hold</c>.</summary>
    public static int Main(string specPath)
    {
        Log.Quiet = true; // hidden console; problems go to the host as a Failed frame
        Log.InitFileAt(Path.Combine(Path.GetDirectoryName(Path.GetFullPath(specPath)) ?? ".", "pty.log"));
        var spec = PtySpec.Read(specPath);
        if (spec == null) { Log.Error($"pty-hold: cannot read {specPath}"); return 2; }
        Log.Info($"pty-hold {Environment.ProcessId}: {spec.CommandLine} in {spec.Cwd} ({spec.Cols}x{spec.Rows})");
        return new PtyHolder(spec) { _hardExit = () => Environment.Exit(0) }.Run();
    }

    /// <summary>Serves until done. <paramref name="startShell"/> replaces the ConPTY start (tests).</summary>
    public int Run(Func<PtySpec, Action<byte[]>, Action<int>, IPty>? startShell = null)
    {
        // Asynchronous (overlapped): a read waiting for the host must not hold up output written to it meanwhile
        // (synchronous I/O on one handle is serialized).
        using var server = new NamedPipeServerStream(_spec.Pipe, PipeDirection.InOut, 1, PipeTransmissionMode.Byte,
            PipeOptions.CurrentUserOnly | PipeOptions.FirstPipeInstance | PipeOptions.Asynchronous);
        _server = server;
        try
        {
            _pty = (startShell ?? StartConPty)(_spec, OnOutput, OnExit);
        }
        catch (Exception ex) when (ex is InvalidOperationException or System.ComponentModel.Win32Exception or IOException)
        {
            _failure = ex.Message;
            Log.Error($"pty-hold: shell did not start: {ex.Message}");
        }
        if (_pty != null) Log.Info($"pty-hold: shell pid {_pty.Pid}");
        while (true)
        {
            try { server.WaitForConnectionAsync(_exitedCts.Token).GetAwaiter().GetResult(); }
            catch (OperationCanceledException) { return 0; } // shell gone and no host came back for it
            catch (IOException) { Disconnect(server); continue; }

            Log.Info("pty-hold: host connected");
            if (_failure != null)
            {
                TryWrite(server, PtyFrames.Failed, Encoding.UTF8.GetBytes(_failure));
                return 1;
            }
            lock (_gate)
            {
                if (!TryWrite(server, PtyFrames.Hello, PtyFrames.Ints(PtyFrames.Version, _pty!.Pid)))
                {
                    Disconnect(server);
                    continue;
                }
                bool ok = true;
                while (ok && _pending.First != null)
                {
                    var chunk = _pending.First.Value;
                    ok = TryWrite(server, PtyFrames.Output, chunk);
                    if (ok) { _pending.RemoveFirst(); _pendingBytes -= chunk.Length; }
                }
                if (ok && _exitCode is int code)
                {
                    ok = _exitSent = TryWrite(server, PtyFrames.Exit, PtyFrames.Ints(code));
                    if (ok) CloseSoon();
                }
                if (ok) _conn = server;
            }
            Log.Info($"pty-hold: serving (exit sent: {_exitSent})");
            ServeConnection(server);
            lock (_gate) _conn = null;
            Log.Info("pty-hold: host disconnected");
            if (_exitSent) return 0;
            Disconnect(server);
        }
    }

    private static IPty StartConPty(PtySpec s, Action<byte[]> onOutput, Action<int> onExit) =>
        PseudoConsoleProcess.Start(s.CommandLine, s.Cwd, s.Env, s.Cols, s.Rows, onOutput, onExit);

    /// <summary>Reads the host's frames until it goes away.</summary>
    private void ServeConnection(Stream conn)
    {
        while (true)
        {
            (byte Type, byte[] Payload)? f;
            try { f = PtyFrames.Read(conn); }
            catch (Exception ex) when (ex is IOException or InvalidDataException or ObjectDisposedException) { return; }
            if (f == null) return;
            var (type, payload) = f.Value;
            switch (type)
            {
                case PtyFrames.Input: _pty?.Write(payload); break;
                case PtyFrames.Resize: _pty?.Resize(PtyFrames.Int(payload, 0), PtyFrames.Int(payload, 1)); break;
                case PtyFrames.KillShell: _pty?.Kill(); break;
            }
        }
    }

    private static void Disconnect(NamedPipeServerStream server)
    {
        // Not only when IsConnected: after the client went away the stream is "broken", and only DisconnectNamedPipe
        // makes the instance accept the next host.
        try { server.Disconnect(); }
        catch (IOException) { }
        catch (InvalidOperationException) { }
    }

    private static bool TryWrite(Stream s, byte type, ReadOnlySpan<byte> payload)
    {
        try { PtyFrames.Write(s, type, payload); return true; }
        catch (Exception ex) when (ex is IOException or ObjectDisposedException or InvalidOperationException) { return false; }
    }

    private void OnOutput(byte[] chunk)
    {
        lock (_gate)
        {
            if (_conn != null && TryWrite(_conn, PtyFrames.Output, chunk)) return;
            _conn = null; // gone: keep the output for the next host
            _pending.AddLast(chunk);
            _pendingBytes += chunk.Length;
            while (_pendingBytes > MaxPending && _pending.First != null)
            {
                _pendingBytes -= _pending.First.Value.Length;
                _pending.RemoveFirst();
            }
        }
    }

    private void OnExit(int code)
    {
        lock (_gate)
        {
            _exitCode = code;
            if (_conn != null && TryWrite(_conn, PtyFrames.Exit, PtyFrames.Ints(code)))
            {
                _exitSent = true;
                CloseSoon();
            }
            else _conn = null;
        }
        _exitedCts.CancelAfter(ExitedWait);
    }

    /// <summary>The host closes the pipe once told the shell exited; do not wait for it forever.</summary>
    private void CloseSoon()
    {
        var server = _server;
        _ = Task.Delay(TimeSpan.FromSeconds(5)).ContinueWith(_ =>
        {
            _exitedCts.Cancel();
            if (server != null) Disconnect(server);
            _hardExit?.Invoke();
        });
    }
}
