using System.Collections;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Text;
using Microsoft.Win32.SafeHandles;

namespace Mpta.QA;

/// <summary>Win32 P/Invoke surface used by the QA driver (job objects, new-console spawn, ConPTY, foreground window).</summary>
internal static class Native
{
    // Values checked against WinBase.h / ProcessThreadsApi.h / WinNT.h.
    public const uint CREATE_NEW_CONSOLE = 0x00000010;
    public const uint CREATE_UNICODE_ENVIRONMENT = 0x00000400;
    public const uint EXTENDED_STARTUPINFO_PRESENT = 0x00080000;
    public const int STARTF_USESHOWWINDOW = 0x00000001;
    public const int STARTF_USESTDHANDLES = 0x00000100;
    public const short SW_SHOWMINNOACTIVE = 7;
    public static readonly IntPtr PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE = (IntPtr)0x00020016;
    public const uint JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x00002000;
    public const int JobObjectExtendedLimitInformation = 9;
    public const uint WAIT_OBJECT_0 = 0;

    [StructLayout(LayoutKind.Sequential)]
    public struct STARTUPINFO
    {
        public int cb;
        public IntPtr lpReserved;
        public IntPtr lpDesktop;
        public IntPtr lpTitle;
        public int dwX, dwY, dwXSize, dwYSize, dwXCountChars, dwYCountChars, dwFillAttribute, dwFlags;
        public short wShowWindow, cbReserved2;
        public IntPtr lpReserved2, hStdInput, hStdOutput, hStdError;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct STARTUPINFOEX
    {
        public STARTUPINFO StartupInfo;
        public IntPtr lpAttributeList;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct PROCESS_INFORMATION
    {
        public IntPtr hProcess, hThread;
        public int dwProcessId, dwThreadId;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct COORD
    {
        public short X, Y;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct JOBOBJECT_BASIC_LIMIT_INFORMATION
    {
        public long PerProcessUserTimeLimit;
        public long PerJobUserTimeLimit;
        public uint LimitFlags;
        public UIntPtr MinimumWorkingSetSize;
        public UIntPtr MaximumWorkingSetSize;
        public uint ActiveProcessLimit;
        public UIntPtr Affinity;
        public uint PriorityClass;
        public uint SchedulingClass;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct IO_COUNTERS
    {
        public ulong ReadOperationCount, WriteOperationCount, OtherOperationCount, ReadTransferCount, WriteTransferCount, OtherTransferCount;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct JOBOBJECT_EXTENDED_LIMIT_INFORMATION
    {
        public JOBOBJECT_BASIC_LIMIT_INFORMATION BasicLimitInformation;
        public IO_COUNTERS IoInfo;
        public UIntPtr ProcessMemoryLimit, JobMemoryLimit, PeakProcessMemoryUsed, PeakJobMemoryUsed;
    }

    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode, EntryPoint = "CreateProcessW")]
    public static extern bool CreateProcess(string? lpApplicationName, StringBuilder lpCommandLine, IntPtr lpProcessAttributes,
        IntPtr lpThreadAttributes, bool bInheritHandles, uint dwCreationFlags, IntPtr lpEnvironment, string? lpCurrentDirectory,
        ref STARTUPINFO lpStartupInfo, out PROCESS_INFORMATION lpProcessInformation);

    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode, EntryPoint = "CreateProcessW")]
    public static extern bool CreateProcessEx(string? lpApplicationName, StringBuilder lpCommandLine, IntPtr lpProcessAttributes,
        IntPtr lpThreadAttributes, bool bInheritHandles, uint dwCreationFlags, IntPtr lpEnvironment, string? lpCurrentDirectory,
        ref STARTUPINFOEX lpStartupInfo, out PROCESS_INFORMATION lpProcessInformation);

    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern bool CloseHandle(IntPtr h);

    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern bool TerminateProcess(IntPtr hProcess, uint exitCode);

    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern uint WaitForSingleObject(IntPtr h, uint ms);

    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern bool CreatePipe(out SafeFileHandle hReadPipe, out SafeFileHandle hWritePipe, IntPtr lpPipeAttributes, int nSize);

    [DllImport("kernel32.dll")]
    public static extern int CreatePseudoConsole(COORD size, SafeFileHandle hInput, SafeFileHandle hOutput, uint dwFlags, out IntPtr phPC);

    [DllImport("kernel32.dll")]
    public static extern void ClosePseudoConsole(IntPtr hPC);

    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern bool InitializeProcThreadAttributeList(IntPtr lpAttributeList, int dwAttributeCount, int dwFlags, ref IntPtr lpSize);

    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern bool UpdateProcThreadAttribute(IntPtr lpAttributeList, uint dwFlags, IntPtr attribute, IntPtr lpValue,
        IntPtr cbSize, IntPtr lpPreviousValue, IntPtr lpReturnSize);

    [DllImport("kernel32.dll")]
    public static extern void DeleteProcThreadAttributeList(IntPtr lpAttributeList);

    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    public static extern IntPtr CreateJobObjectW(IntPtr lpJobAttributes, string? lpName);

    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern bool SetInformationJobObject(IntPtr hJob, int infoClass, ref JOBOBJECT_EXTENDED_LIMIT_INFORMATION info, int cbInfo);

    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern bool AssignProcessToJobObject(IntPtr hJob, IntPtr hProcess);

    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern bool TerminateJobObject(IntPtr hJob, uint exitCode);

    [DllImport("user32.dll")]
    public static extern IntPtr GetForegroundWindow();

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    public static extern int GetWindowTextW(IntPtr hWnd, StringBuilder text, int max);

    [DllImport("user32.dll", SetLastError = true)]
    public static extern bool SetForegroundWindow(IntPtr hWnd);

    [DllImport("user32.dll", SetLastError = true)]
    public static extern bool AllowSetForegroundWindow(int dwProcessId);

    public const int ASFW_ANY = -1;

    public static string WindowDesc(IntPtr h)
    {
        var sb = new StringBuilder(256);
        if (h != IntPtr.Zero) GetWindowTextW(h, sb, sb.Capacity);
        return $"0x{h.ToInt64():X}('{sb}')";
    }

    /// <summary>Builds a CREATE_UNICODE_ENVIRONMENT block (sorted, double-NUL terminated) from the current env plus overrides.</summary>
    public static IntPtr BuildEnvironmentBlock(IDictionary<string, string>? overrides)
    {
        var env = new SortedDictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (DictionaryEntry e in Environment.GetEnvironmentVariables())
            env[(string)e.Key] = (string?)e.Value ?? "";
        if (overrides != null)
            foreach (var kv in overrides) env[kv.Key] = kv.Value;
        var sb = new StringBuilder();
        foreach (var kv in env) sb.Append(kv.Key).Append('=').Append(kv.Value).Append('\0');
        sb.Append('\0');
        return Marshal.StringToHGlobalUni(sb.ToString());
    }

    /// <summary>Spawns a process in a brand-new console window (minimized, no activation). Returns the pid.</summary>
    public static int SpawnNewConsole(string commandLine, string? cwd, Job? job)
    {
        var si = new STARTUPINFO
        {
            cb = Marshal.SizeOf<STARTUPINFO>(),
            dwFlags = STARTF_USESHOWWINDOW,
            wShowWindow = SW_SHOWMINNOACTIVE,
        };
        if (!CreateProcess(null, new StringBuilder(commandLine), IntPtr.Zero, IntPtr.Zero, false, CREATE_NEW_CONSOLE,
                IntPtr.Zero, cwd, ref si, out var pi))
            throw new Win32Exception(Marshal.GetLastWin32Error(), "CreateProcess(CREATE_NEW_CONSOLE) failed");
        job?.Add(pi.hProcess);
        CloseHandle(pi.hThread);
        CloseHandle(pi.hProcess);
        return pi.dwProcessId;
    }
}

/// <summary>Kill-on-close job object: everything assigned (and its descendants) dies when the job is terminated or the QA process exits.</summary>
internal sealed class Job : IDisposable
{
    private IntPtr _h;

    public Job()
    {
        _h = Native.CreateJobObjectW(IntPtr.Zero, null);
        if (_h == IntPtr.Zero) throw new Win32Exception(Marshal.GetLastWin32Error(), "CreateJobObject failed");
        var info = new Native.JOBOBJECT_EXTENDED_LIMIT_INFORMATION();
        info.BasicLimitInformation.LimitFlags = Native.JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
        if (!Native.SetInformationJobObject(_h, Native.JobObjectExtendedLimitInformation, ref info,
                Marshal.SizeOf<Native.JOBOBJECT_EXTENDED_LIMIT_INFORMATION>()))
            throw new Win32Exception(Marshal.GetLastWin32Error(), "SetInformationJobObject failed");
    }

    public bool Add(IntPtr hProcess) => _h != IntPtr.Zero && Native.AssignProcessToJobObject(_h, hProcess);

    public void Terminate()
    {
        if (_h != IntPtr.Zero) Native.TerminateJobObject(_h, 1);
    }

    public void Dispose()
    {
        if (_h == IntPtr.Zero) return;
        Native.TerminateJobObject(_h, 1);
        Native.CloseHandle(_h);
        _h = IntPtr.Zero;
    }
}

/// <summary>A child process running inside a pseudoconsole created by the QA driver (used for AC-20 / AC-22 `mpta attach|new`).</summary>
internal sealed class PtyProcess : IDisposable
{
    private IntPtr _hPC;
    private IntPtr _hProcess;
    private IntPtr _attrList;
    private FileStream? _in;
    private FileStream? _out;
    private Thread? _reader;
    private readonly MemoryStream _buf = new();
    private readonly object _lk = new();
    public int Pid { get; private set; }

    public static PtyProcess Start(string commandLine, short cols, short rows, string? cwd, IDictionary<string, string>? env, Job? job)
    {
        var p = new PtyProcess();
        if (!Native.CreatePipe(out var inRead, out var inWrite, IntPtr.Zero, 0))
            throw new Win32Exception(Marshal.GetLastWin32Error(), "CreatePipe(in) failed");
        if (!Native.CreatePipe(out var outRead, out var outWrite, IntPtr.Zero, 0))
            throw new Win32Exception(Marshal.GetLastWin32Error(), "CreatePipe(out) failed");
        int hr = Native.CreatePseudoConsole(new Native.COORD { X = cols, Y = rows }, inRead, outWrite, 0, out p._hPC);
        if (hr != 0) throw new Win32Exception(hr, $"CreatePseudoConsole failed hr=0x{hr:X8}");
        // The pseudoconsole duplicated its ends of the pipes.
        inRead.Dispose();
        outWrite.Dispose();
        p._in = new FileStream(inWrite, FileAccess.Write, 1, false);
        p._out = new FileStream(outRead, FileAccess.Read, 4096, false);
        p._reader = new Thread(p.ReadLoop) { IsBackground = true, Name = "pty-reader" };
        p._reader.Start();

        IntPtr size = IntPtr.Zero;
        Native.InitializeProcThreadAttributeList(IntPtr.Zero, 1, 0, ref size);
        p._attrList = Marshal.AllocHGlobal(size);
        if (!Native.InitializeProcThreadAttributeList(p._attrList, 1, 0, ref size))
            throw new Win32Exception(Marshal.GetLastWin32Error(), "InitializeProcThreadAttributeList failed");
        if (!Native.UpdateProcThreadAttribute(p._attrList, 0, Native.PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE, p._hPC,
                (IntPtr)IntPtr.Size, IntPtr.Zero, IntPtr.Zero))
            throw new Win32Exception(Marshal.GetLastWin32Error(), "UpdateProcThreadAttribute failed");

        var si = new Native.STARTUPINFOEX { lpAttributeList = p._attrList };
        si.StartupInfo.cb = Marshal.SizeOf<Native.STARTUPINFOEX>();
        // Null std handles + USESTDHANDLES: otherwise a child of a redirected parent writes to the parent's pipes, not the pseudoconsole.
        si.StartupInfo.dwFlags = Native.STARTF_USESTDHANDLES;
        IntPtr envBlock = Native.BuildEnvironmentBlock(env);
        try
        {
            if (!Native.CreateProcessEx(null, new StringBuilder(commandLine), IntPtr.Zero, IntPtr.Zero, false,
                    Native.EXTENDED_STARTUPINFO_PRESENT | Native.CREATE_UNICODE_ENVIRONMENT, envBlock, cwd, ref si, out var pi))
                throw new Win32Exception(Marshal.GetLastWin32Error(), "CreateProcess(pseudoconsole) failed");
            Native.CloseHandle(pi.hThread);
            p._hProcess = pi.hProcess;
            p.Pid = pi.dwProcessId;
            job?.Add(pi.hProcess);
        }
        finally
        {
            Marshal.FreeHGlobal(envBlock);
        }
        return p;
    }

    private void ReadLoop()
    {
        var tmp = new byte[8192];
        try
        {
            while (true)
            {
                int n = _out!.Read(tmp, 0, tmp.Length);
                if (n <= 0) break;
                lock (_lk) _buf.Write(tmp, 0, n);
            }
        }
        catch (IOException) { }
        catch (ObjectDisposedException) { }
    }

    public byte[] Snapshot()
    {
        lock (_lk) return _buf.ToArray();
    }

    public bool Contains(string s) => Snapshot().AsSpan().IndexOf(Encoding.UTF8.GetBytes(s)) >= 0;

    public void Write(string s)
    {
        var b = Encoding.UTF8.GetBytes(s);
        _in!.Write(b, 0, b.Length);
        _in.Flush();
    }

    public bool HasExited => _hProcess == IntPtr.Zero || Native.WaitForSingleObject(_hProcess, 0) == Native.WAIT_OBJECT_0;

    public bool WaitExit(int ms) => _hProcess == IntPtr.Zero || Native.WaitForSingleObject(_hProcess, (uint)ms) == Native.WAIT_OBJECT_0;

    public void Kill()
    {
        if (_hProcess != IntPtr.Zero && !HasExited) Native.TerminateProcess(_hProcess, 1);
    }

    public void Dispose()
    {
        Kill();
        if (_hPC != IntPtr.Zero)
        {
            var h = _hPC;
            _hPC = IntPtr.Zero;
            // ClosePseudoConsole can block while output is undrained; the reader thread keeps draining, and we cap the wait.
            Task.Run(() => Native.ClosePseudoConsole(h)).Wait(3000);
        }
        try { _in?.Dispose(); } catch (IOException) { }
        try { _out?.Dispose(); } catch (IOException) { }
        if (_attrList != IntPtr.Zero)
        {
            Native.DeleteProcThreadAttributeList(_attrList);
            Marshal.FreeHGlobal(_attrList);
            _attrList = IntPtr.Zero;
        }
        if (_hProcess != IntPtr.Zero)
        {
            Native.CloseHandle(_hProcess);
            _hProcess = IntPtr.Zero;
        }
    }
}
