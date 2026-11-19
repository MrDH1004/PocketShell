using System.IO.Pipes;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using System.Threading.Channels;
using Pkshell.Core.Native;
using Pkshell.Core.Util;

namespace Pkshell.Core.Pty;

/// <summary>Host side of a <see cref="PtyHolder"/>: the session's shell runs in the holder process, this forwards input,
/// size and kill to it and raises its output and exit.</summary>
public sealed class RemotePty : IPty
{
    private readonly NamedPipeClientStream _pipe;
    /// <summary>
    /// Frames for the holder, written by one sender thread: callers never block on the pipe. A resize is sent while the
    /// session lock is held, and the reader needs that lock to deliver output; a write that waited for the holder (busy
    /// sending that output) would deadlock both.
    /// </summary>
    private readonly Channel<(byte Type, byte[] Payload)> _outbox = Channel.CreateUnbounded<(byte, byte[])>(new UnboundedChannelOptions { SingleReader = true });
    private readonly Action<byte[]> _onOutput;
    private readonly Action<int> _onExit;
    private volatile bool _detached;
    private int _exitRaised;

    public int Pid { get; }
    public bool SurvivesHost => true;

    private RemotePty(NamedPipeClientStream pipe, int pid, Action<byte[]> onOutput, Action<int> onExit)
    {
        _pipe = pipe;
        Pid = pid;
        _onOutput = onOutput;
        _onExit = onExit;
    }

    /// <summary>
    /// Connects to the holder on <paramref name="pipeName"/> and reads its hello. Null when nothing answers within
    /// <paramref name="timeout"/> (or <paramref name="alive"/> says the holder is gone). Throws when the holder could not
    /// start the shell.
    /// </summary>
    public static RemotePty? Connect(string pipeName, TimeSpan timeout, Action<byte[]> onOutput, Action<int> onExit, Func<bool>? alive = null)
    {
        var deadline = DateTime.UtcNow + timeout;
        while (true)
        {
            var pipe = new NamedPipeClientStream(".", pipeName, PipeDirection.InOut, PipeOptions.CurrentUserOnly | PipeOptions.Asynchronous);
            try
            {
                var left = deadline - DateTime.UtcNow;
                pipe.Connect((int)Math.Clamp(left.TotalMilliseconds, 1, 500));
            }
            catch (Exception ex) when (ex is TimeoutException or IOException or UnauthorizedAccessException)
            {
                pipe.Dispose();
                if (DateTime.UtcNow >= deadline || alive?.Invoke() == false) return null;
                continue;
            }
            (byte Type, byte[] Payload)? hello;
            try { hello = PtyFrames.Read(pipe); }
            catch (Exception ex) when (ex is IOException or InvalidDataException)
            {
                pipe.Dispose();
                return null;
            }
            if (hello is not { } h) { pipe.Dispose(); return null; }
            if (h.Type == PtyFrames.Failed)
            {
                pipe.Dispose();
                throw new InvalidOperationException(Encoding.UTF8.GetString(h.Payload));
            }
            if (h.Type != PtyFrames.Hello) { pipe.Dispose(); return null; }
            var p = new RemotePty(pipe, PtyFrames.Int(h.Payload, 1), onOutput, onExit);
            Task.Factory.StartNew(p.ReadLoop, TaskCreationOptions.LongRunning);
            Task.Factory.StartNew(p.SendLoop, TaskCreationOptions.LongRunning);
            return p;
        }
    }

    private void ReadLoop()
    {
        int code = -1;
        try
        {
            while (true)
            {
                var f = PtyFrames.Read(_pipe);
                if (f == null) break;
                var (type, payload) = f.Value;
                if (type == PtyFrames.Output)
                {
                    try { _onOutput(payload); }
                    catch (Exception ex) { Log.Error($"pty[{Pid}] output handler failed: {ex}"); }
                }
                else if (type == PtyFrames.Exit)
                {
                    code = PtyFrames.Int(payload, 0);
                    break;
                }
            }
        }
        catch (Exception ex) when (ex is IOException or InvalidDataException or ObjectDisposedException)
        {
            if (!_detached) Log.Warn($"pty[{Pid}] holder connection lost: {ex.Message}");
        }
        if (_detached) return; // this host is handing the session over; the holder keeps it running
        _outbox.Writer.TryComplete();
        try { _pipe.Dispose(); } catch (IOException) { }
        if (Interlocked.Exchange(ref _exitRaised, 1) == 0)
        {
            try { _onExit(code); }
            catch (Exception ex) { Log.Error($"pty[{Pid}] exit handler failed: {ex}"); }
        }
    }

    private void Send(byte type, ReadOnlySpan<byte> payload) => _outbox.Writer.TryWrite((type, payload.ToArray()));

    private void SendLoop()
    {
        var reader = _outbox.Reader;
        while (reader.WaitToReadAsync().AsTask().GetAwaiter().GetResult())
        {
            while (reader.TryRead(out var f))
            {
                try { PtyFrames.Write(_pipe, f.Type, f.Payload); }
                catch (Exception ex) when (ex is IOException or ObjectDisposedException or InvalidOperationException)
                {
                    if (!_detached) Log.Warn($"pty[{Pid}] send to holder failed: {ex.Message}");
                    _outbox.Writer.TryComplete();
                    return;
                }
            }
        }
    }

    public void Write(ReadOnlySpan<byte> data) => Send(PtyFrames.Input, data);

    public void Resize(int cols, int rows)
    {
        if (cols < 1 || rows < 1) return;
        Send(PtyFrames.Resize, PtyFrames.Ints(cols, rows));
    }

    public void Kill() => Send(PtyFrames.KillShell, ReadOnlySpan<byte>.Empty);

    /// <summary>Lets go of the holder without ending the shell (update handoff): the next host reattaches.</summary>
    public void Detach()
    {
        _detached = true;
        _outbox.Writer.TryComplete();
        try { _pipe.Dispose(); } catch (IOException) { }
    }

    public void Dispose()
    {
        _outbox.Writer.TryComplete();
        try { _pipe.Dispose(); } catch (IOException) { }
    }
}

/// <summary>Starts session shells: in this process, or through holder processes that keep them across host restarts.</summary>
public interface IPtyLauncher
{
    IPty Start(string sessionDir, string commandLine, string? cwd, Dictionary<string, string>? env, int cols, int rows, Action<byte[]> onOutput, Action<int> onExit);
    /// <summary>The still-running shell of a session a previous host started, or null.</summary>
    IPty? Reattach(string sessionDir, Action<byte[]> onOutput, Action<int> onExit);
}

public sealed class InProcessPtyLauncher : IPtyLauncher
{
    public static readonly InProcessPtyLauncher Instance = new();

    public IPty Start(string sessionDir, string commandLine, string? cwd, Dictionary<string, string>? env, int cols, int rows, Action<byte[]> onOutput, Action<int> onExit) =>
        PseudoConsoleProcess.Start(commandLine, cwd, env, cols, rows, onOutput, onExit);

    public IPty? Reattach(string sessionDir, Action<byte[]> onOutput, Action<int> onExit) => null;
}

/// <summary>
/// Runs each session's shell in its own <c>psh pty-hold</c> process so an update (host restart) does not end it.
/// Falls back to the in-process pseudo console when the holder cannot be started.
/// </summary>
public sealed class HolderPtyLauncher : IPtyLauncher
{
    private const uint CREATE_NO_WINDOW = 0x08000000;
    private readonly string _exe;

    public HolderPtyLauncher(string pshExe) => _exe = pshExe;

    public IPty Start(string sessionDir, string commandLine, string? cwd, Dictionary<string, string>? env, int cols, int rows, Action<byte[]> onOutput, Action<int> onExit)
    {
        var spec = new PtySpec
        {
            Pipe = "pocketshell-pty-" + Guid.NewGuid().ToString("N"),
            CommandLine = commandLine, Cwd = cwd, Env = env, Cols = cols, Rows = rows,
        };
        var specPath = PtySpec.PathIn(sessionDir);
        try
        {
            AtomicFile.WriteAllText(specPath, JsonSerializer.Serialize(spec, Json.Indented));
            var (hProcess, pid) = Launch(specPath);
            try
            {
                var pty = RemotePty.Connect(spec.Pipe, TimeSpan.FromSeconds(15), onOutput, onExit, () => Win32.WaitForSingleObject(hProcess, 0) != Win32.WAIT_OBJECT_0);
                if (pty != null)
                {
                    Log.Info($"session shell pid {pty.Pid} held by psh pty-hold (pid {pid})");
                    return pty;
                }
                Log.Warn($"pty holder (pid {pid}) did not answer; running the shell in the host process");
                Win32.TerminateProcess(hProcess, 1);
            }
            finally { Win32.CloseHandle(hProcess); }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or System.ComponentModel.Win32Exception)
        {
            Log.Warn($"pty holder not started ({ex.Message}); running the shell in the host process");
        }
        TryDelete(specPath);
        return PseudoConsoleProcess.Start(commandLine, cwd, env, cols, rows, onOutput, onExit);
    }

    public IPty? Reattach(string sessionDir, Action<byte[]> onOutput, Action<int> onExit)
    {
        var specPath = PtySpec.PathIn(sessionDir);
        if (!File.Exists(specPath)) return null;
        var spec = PtySpec.Read(specPath);
        if (spec == null) return null;
        try
        {
            var pty = RemotePty.Connect(spec.Pipe, TimeSpan.FromSeconds(2), onOutput, onExit);
            if (pty == null) TryDelete(specPath); // holder gone: the shell ended with it
            return pty;
        }
        catch (InvalidOperationException ex)
        {
            Log.Warn($"pty holder for {sessionDir}: {ex.Message}");
            TryDelete(specPath);
            return null;
        }
    }

    public static void TryDelete(string specPath)
    {
        try { File.Delete(specPath); }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }

    /// <summary>
    /// Starts <c>psh pty-hold</c> hidden, inheriting no handles (the host's listening sockets, its serve mutex and open
    /// session files must not stay alive in a process that outlives it).
    /// </summary>
    private (IntPtr Process, int Pid) Launch(string specPath)
    {
        var si = new Win32.STARTUPINFOEX();
        si.StartupInfo.cb = Marshal.SizeOf<Win32.STARTUPINFO>();
        var cmd = ($"\"{_exe}\" pty-hold \"{specPath}\"" + "\0").ToCharArray();
        if (!Win32.CreateProcess(_exe, cmd, IntPtr.Zero, IntPtr.Zero, false, CREATE_NO_WINDOW, IntPtr.Zero,
                Environment.SystemDirectory, ref si, out var pi)) // not the install folder: it must stay renamable
            throw Win32.LastError("CreateProcess(psh pty-hold)");
        Win32.CloseHandle(pi.hThread);
        return (pi.hProcess, pi.dwProcessId);
    }
}
