using System.Runtime.InteropServices;
using System.Text;
using Microsoft.Win32.SafeHandles;
using Mpta.Core.Native;
using Mpta.Core.Util;

namespace Mpta.Core.Pty;

/// <summary>
/// A child process attached to a Windows pseudo console (ConPTY).
/// Output bytes are delivered raw via <see cref="Output"/>; <see cref="Exited"/> fires after all output is drained.
/// </summary>
public sealed class PseudoConsoleProcess : IDisposable
{
    private IntPtr _hPC;
    private IntPtr _hProcess;
    private readonly SafeFileHandle _inputWrite;
    private readonly SafeFileHandle _outputRead;
    private readonly FileStream _inputStream;
    private readonly object _pcLock = new();
    private int _exitedRaised;

    public int Pid { get; }
    private readonly Action<byte[]> _onOutput;
    private readonly Action<int> _onExit;

    private PseudoConsoleProcess(IntPtr hPC, IntPtr hProcess, int pid, SafeFileHandle inputWrite, SafeFileHandle outputRead, Action<byte[]> onOutput, Action<int> onExit)
    {
        _onOutput = onOutput;
        _onExit = onExit;
        _hPC = hPC;
        _hProcess = hProcess;
        Pid = pid;
        _inputWrite = inputWrite;
        _outputRead = outputRead;
        _inputStream = new FileStream(_inputWrite, FileAccess.Write, 1, false);
    }

    public static PseudoConsoleProcess Start(string commandLine, string? workingDirectory, IDictionary<string, string>? envOverrides, int cols, int rows,
        Action<byte[]> onOutput, Action<int> onExit)
    {
        cols = Math.Clamp(cols, 1, short.MaxValue);
        rows = Math.Clamp(rows, 1, short.MaxValue);

        if (!Win32.CreatePipe(out var inputRead, out var inputWrite, IntPtr.Zero, 0))
            throw Win32.LastError("CreatePipe(input)");
        if (!Win32.CreatePipe(out var outputRead, out var outputWrite, IntPtr.Zero, 0))
        {
            var ex = Win32.LastError("CreatePipe(output)");
            inputRead.Dispose(); inputWrite.Dispose();
            throw ex;
        }

        int hr = Win32.CreatePseudoConsole(new Win32.COORD((short)cols, (short)rows), inputRead, outputWrite, 0, out var hPC);
        // ConPTY duplicates the handles it needs; release our copies of the PTY-side ends.
        inputRead.Dispose();
        outputWrite.Dispose();
        if (hr != 0)
        {
            inputWrite.Dispose(); outputRead.Dispose();
            throw new InvalidOperationException($"CreatePseudoConsole failed: HRESULT=0x{hr:X8}");
        }

        IntPtr attrList = IntPtr.Zero;
        IntPtr envBlock = IntPtr.Zero;
        try
        {
            IntPtr size = IntPtr.Zero;
            // First call is expected to fail with ERROR_INSUFFICIENT_BUFFER and return the required size.
            Win32.InitializeProcThreadAttributeList(IntPtr.Zero, 1, 0, ref size);
            if (size == IntPtr.Zero) throw Win32.LastError("InitializeProcThreadAttributeList(size)");
            attrList = Marshal.AllocHGlobal(size);
            if (!Win32.InitializeProcThreadAttributeList(attrList, 1, 0, ref size))
                throw Win32.LastError("InitializeProcThreadAttributeList");
            if (!Win32.UpdateProcThreadAttribute(attrList, 0, Win32.PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE, hPC, (IntPtr)IntPtr.Size, IntPtr.Zero, IntPtr.Zero))
                throw Win32.LastError("UpdateProcThreadAttribute(PSEUDOCONSOLE)");

            var si = new Win32.STARTUPINFOEX();
            si.StartupInfo.cb = Marshal.SizeOf<Win32.STARTUPINFOEX>();
            // Prevent the child from inheriting this process's (possibly redirected) std handles instead of the pseudo console.
            si.StartupInfo.dwFlags = Win32.STARTF_USESTDHANDLES;
            si.lpAttributeList = attrList;

            envBlock = BuildEnvironmentBlock(envOverrides);
            // CreateProcessW may modify lpCommandLine in place, so it needs a writable NUL-terminated buffer.
            var cmd = (commandLine + "\0").ToCharArray();
            string? cwd = string.IsNullOrEmpty(workingDirectory) || !Directory.Exists(workingDirectory) ? null : workingDirectory;
            if (!Win32.CreateProcess(null, cmd, IntPtr.Zero, IntPtr.Zero, false,
                    Win32.EXTENDED_STARTUPINFO_PRESENT | Win32.CREATE_UNICODE_ENVIRONMENT, envBlock, cwd, ref si, out var pi))
                throw Win32.LastError($"CreateProcess({commandLine})");
            Win32.CloseHandle(pi.hThread);

            var p = new PseudoConsoleProcess(hPC, pi.hProcess, pi.dwProcessId, inputWrite, outputRead, onOutput, onExit);
            hPC = IntPtr.Zero; // ownership transferred
            p.StartPumps();
            return p;
        }
        catch
        {
            if (hPC != IntPtr.Zero) Win32.ClosePseudoConsole(hPC);
            inputWrite.Dispose();
            outputRead.Dispose();
            throw;
        }
        finally
        {
            if (attrList != IntPtr.Zero)
            {
                Win32.DeleteProcThreadAttributeList(attrList);
                Marshal.FreeHGlobal(attrList);
            }
            if (envBlock != IntPtr.Zero) Marshal.FreeHGlobal(envBlock);
        }
    }

    private static IntPtr BuildEnvironmentBlock(IDictionary<string, string>? overrides)
    {
        var env = new SortedDictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (System.Collections.DictionaryEntry e in Environment.GetEnvironmentVariables())
        {
            var k = (string)e.Key;
            if (k.Length == 0 || k.Contains('=') && !k.StartsWith('=')) continue;
            env[k] = (string?)e.Value ?? "";
        }
        if (overrides != null)
            foreach (var kv in overrides) env[kv.Key] = kv.Value;
        var sb = new StringBuilder();
        foreach (var kv in env) sb.Append(kv.Key).Append('=').Append(kv.Value).Append('\0');
        sb.Append('\0');
        return Marshal.StringToHGlobalUni(sb.ToString());
    }

    private Task? _readerTask;

    private void StartPumps()
    {
        _readerTask = Task.Factory.StartNew(ReadLoop, TaskCreationOptions.LongRunning);
        Task.Factory.StartNew(WaitLoop, TaskCreationOptions.LongRunning);
    }

    private void ReadLoop()
    {
        var buf = new byte[16384];
        using var fs = new FileStream(_outputRead, FileAccess.Read, 1, false);
        while (true)
        {
            int n;
            try { n = fs.Read(buf, 0, buf.Length); }
            catch (IOException ex) { Log.Info($"pty[{Pid}] output pipe closed: {ex.Message}"); break; }
            catch (ObjectDisposedException) { break; }
            if (n <= 0) break;
            var chunk = new byte[n];
            Buffer.BlockCopy(buf, 0, chunk, 0, n);
            try { _onOutput(chunk); }
            catch (Exception ex) { Log.Error($"pty[{Pid}] output handler failed: {ex}"); }
        }
    }

    private void WaitLoop()
    {
        uint w = Win32.WaitForSingleObject(_hProcess, Win32.INFINITE);
        if (w != Win32.WAIT_OBJECT_0) Log.Error($"pty[{Pid}] WaitForSingleObject returned {w}: {Win32.LastError("WaitForSingleObject").Message}");
        int code = -1;
        if (Win32.GetExitCodeProcess(_hProcess, out var ec)) code = unchecked((int)ec);
        else Log.Error(Win32.LastError("GetExitCodeProcess").Message);

        // Closing the pseudo console flushes remaining output and terminates conhost, which closes the output pipe (EOF for ReadLoop).
        ClosePc();
        if (_readerTask != null && !_readerTask.Wait(TimeSpan.FromSeconds(5)))
            Log.Warn($"pty[{Pid}] output reader did not finish within 5s after exit");
        if (Interlocked.Exchange(ref _exitedRaised, 1) == 0)
        {
            try { _onExit(code); }
            catch (Exception ex) { Log.Error($"pty[{Pid}] exit handler failed: {ex}"); }
        }
    }

    private void ClosePc()
    {
        IntPtr h;
        lock (_pcLock) { h = _hPC; _hPC = IntPtr.Zero; }
        if (h != IntPtr.Zero) Win32.ClosePseudoConsole(h);
    }

    public void Write(ReadOnlySpan<byte> data)
    {
        try
        {
            _inputStream.Write(data);
            _inputStream.Flush();
        }
        catch (IOException ex) { Log.Warn($"pty[{Pid}] input write failed: {ex.Message}"); }
        catch (ObjectDisposedException) { }
    }

    public void Resize(int cols, int rows)
    {
        if (cols < 1 || rows < 1) return;
        lock (_pcLock)
        {
            if (_hPC == IntPtr.Zero) return;
            int hr = Win32.ResizePseudoConsole(_hPC, new Win32.COORD((short)Math.Min(cols, short.MaxValue), (short)Math.Min(rows, short.MaxValue)));
            if (hr != 0) Log.Error($"pty[{Pid}] ResizePseudoConsole({cols}x{rows}) failed: HRESULT=0x{hr:X8}");
        }
    }

    public void Kill()
    {
        var hp = _hProcess;
        if (hp == IntPtr.Zero || Volatile.Read(ref _exitedRaised) == 1) return;
        if (!Win32.TerminateProcess(hp, 1))
            Log.Warn($"pty[{Pid}] {Win32.LastError("TerminateProcess").Message}");
    }

    public void Dispose()
    {
        ClosePc();
        try { _inputStream.Dispose(); } catch (IOException) { }
        var hp = Interlocked.Exchange(ref _hProcess, IntPtr.Zero);
        if (hp != IntPtr.Zero && Volatile.Read(ref _exitedRaised) == 1) Win32.CloseHandle(hp);
        else if (hp != IntPtr.Zero) _hProcess = hp; // still running: keep handle for the wait loop
    }
}
