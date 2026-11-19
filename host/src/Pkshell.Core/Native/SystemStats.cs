using System.Runtime.InteropServices;

namespace Pkshell.Core.Native;

/// <summary>PC load for the phone: CPU %, GPU % (as Task Manager shows it), memory in use.</summary>
public sealed record SystemSnapshot(double? Cpu, double? Gpu, ulong MemUsed, ulong MemTotal);

/// <summary>
/// Samples CPU (GetSystemTimes deltas) and GPU (PDH "GPU Engine" utilization, busiest engine) once a second while
/// someone reads; stops sampling after <see cref="IdleStop"/> without a read.
/// </summary>
public sealed class SystemStats : IDisposable
{
    public static readonly TimeSpan Interval = TimeSpan.FromSeconds(1);
    public static readonly TimeSpan IdleStop = TimeSpan.FromSeconds(30);

    private readonly object _lock = new();
    private Timer? _timer;
    private DateTime _lastRead;
    private ulong _prevIdle, _prevTotal;
    private bool _havePrevCpu;
    private double? _cpu, _gpu;
    private IntPtr _query, _gpuCounter;
    private bool _gpuTried, _gpuPrimed;

    /// <summary>Latest values; the first read samples twice a moment apart so CPU and GPU have a value.</summary>
    public async Task<SystemSnapshot> ReadAsync()
    {
        bool first;
        lock (_lock)
        {
            _lastRead = DateTime.UtcNow;
            first = _timer == null;
            if (first)
            {
                Sample();
                _timer = new Timer(_ => Tick(), null, Interval, Interval);
            }
        }
        if (first)
        {
            await Task.Delay(300).ConfigureAwait(false);
            lock (_lock) Sample();
        }
        var (used, total) = Memory();
        lock (_lock) return new SystemSnapshot(_cpu, _gpu, used, total);
    }

    private void Tick()
    {
        lock (_lock)
        {
            if (DateTime.UtcNow - _lastRead > IdleStop)
            {
                _timer?.Dispose();
                _timer = null;
                _havePrevCpu = false;
                _gpuPrimed = false;
                return;
            }
            Sample();
        }
    }

    private void Sample()
    {
        if (GetSystemTimes(out var idle, out var kernel, out var user))
        {
            ulong i = idle, t = kernel + user; // kernel time includes idle
            if (_havePrevCpu && t > _prevTotal)
                _cpu = Math.Clamp(100.0 * (1.0 - (double)(i - _prevIdle) / (t - _prevTotal)), 0, 100);
            _prevIdle = i;
            _prevTotal = t;
            _havePrevCpu = true;
        }
        SampleGpu();
    }

    private void SampleGpu()
    {
        if (!_gpuTried)
        {
            _gpuTried = true;
            if (PdhOpenQueryW(null, IntPtr.Zero, out _query) != 0) { _query = IntPtr.Zero; return; }
            if (PdhAddEnglishCounterW(_query, @"\GPU Engine(*)\Utilization Percentage", IntPtr.Zero, out _gpuCounter) != 0)
            {
                PdhCloseQuery(_query);
                _query = IntPtr.Zero;
                return;
            }
        }
        if (_query == IntPtr.Zero) return;
        if (PdhCollectQueryData(_query) != 0) return;
        if (!_gpuPrimed) { _gpuPrimed = true; return; } // a rate counter needs two collections
        uint size = 0;
        uint st = PdhGetFormattedCounterArrayW(_gpuCounter, PDH_FMT_DOUBLE, ref size, out _, IntPtr.Zero);
        if (st != PDH_MORE_DATA || size == 0) { if (st == 0) _gpu = 0; return; }
        var buf = Marshal.AllocHGlobal((int)size);
        try
        {
            if (PdhGetFormattedCounterArrayW(_gpuCounter, PDH_FMT_DOUBLE, ref size, out var count, buf) != 0) return;
            var items = new List<(string, double)>((int)count);
            for (int k = 0; k < count; k++)
            {
                var item = buf + k * ItemSize;
                var name = Marshal.PtrToStringUni(Marshal.ReadIntPtr(item));
                if (name == null || Marshal.ReadInt32(item + ValueOffset) != 0) continue; // CStatus
                items.Add((name, BitConverter.Int64BitsToDouble(Marshal.ReadInt64(item + ValueOffset + 8))));
            }
            _gpu = Busiest(items);
        }
        finally
        {
            Marshal.FreeHGlobal(buf);
        }
    }

    /// <summary>
    /// Task Manager's GPU %: per engine ("luid_…_phys_0_eng_3"), the sum over processes; the busiest engine wins.
    /// Instance names look like "pid_1234_luid_0x00000000_0x0000D1E3_phys_0_eng_3_engtype_3D".
    /// </summary>
    public static double Busiest(IEnumerable<(string Instance, double Value)> items)
    {
        var sums = new Dictionary<string, double>(StringComparer.OrdinalIgnoreCase);
        foreach (var (inst, v) in items)
        {
            int a = inst.IndexOf("luid_", StringComparison.OrdinalIgnoreCase);
            if (a < 0) continue;
            int b = inst.IndexOf("_engtype", a, StringComparison.OrdinalIgnoreCase);
            var key = b < 0 ? inst[a..] : inst[a..b];
            sums[key] = sums.GetValueOrDefault(key) + v;
        }
        return sums.Count == 0 ? 0 : Math.Clamp(sums.Values.Max(), 0, 100);
    }

    private static (ulong Used, ulong Total) Memory()
    {
        var m = new MEMORYSTATUSEX { dwLength = (uint)Marshal.SizeOf<MEMORYSTATUSEX>() };
        if (!GlobalMemoryStatusEx(ref m)) return (0, 0);
        return (m.ullTotalPhys - m.ullAvailPhys, m.ullTotalPhys);
    }

    public void Dispose()
    {
        lock (_lock)
        {
            _timer?.Dispose();
            _timer = null;
            if (_query != IntPtr.Zero) PdhCloseQuery(_query);
            _query = IntPtr.Zero;
        }
    }

    // pdh.h / pdhmsg.h
    private const uint PDH_FMT_DOUBLE = 0x00000200;
    private const uint PDH_MORE_DATA = 0x800007D2;
    // PDH_FMT_COUNTERVALUE_ITEM_W: LPWSTR szName; PDH_FMT_COUNTERVALUE { DWORD CStatus; union (8 bytes, 8-aligned) }
    private static readonly int ValueOffset = 8; // the value struct is 8-aligned on x86 and x64
    private static readonly int ItemSize = 24;

    [StructLayout(LayoutKind.Sequential)]
    private struct MEMORYSTATUSEX
    {
        public uint dwLength;
        public uint dwMemoryLoad;
        public ulong ullTotalPhys;
        public ulong ullAvailPhys;
        public ulong ullTotalPageFile;
        public ulong ullAvailPageFile;
        public ulong ullTotalVirtual;
        public ulong ullAvailVirtual;
        public ulong ullAvailExtendedVirtual;
    }

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GlobalMemoryStatusEx(ref MEMORYSTATUSEX lpBuffer);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetSystemTimes(out ulong idle, out ulong kernel, out ulong user);

    [DllImport("pdh.dll", CharSet = CharSet.Unicode)]
    private static extern uint PdhOpenQueryW(string? dataSource, IntPtr userData, out IntPtr query);

    [DllImport("pdh.dll", CharSet = CharSet.Unicode)]
    private static extern uint PdhAddEnglishCounterW(IntPtr query, string path, IntPtr userData, out IntPtr counter);

    [DllImport("pdh.dll")]
    private static extern uint PdhCollectQueryData(IntPtr query);

    [DllImport("pdh.dll", CharSet = CharSet.Unicode)]
    private static extern uint PdhGetFormattedCounterArrayW(IntPtr counter, uint format, ref uint bufferSize, out uint itemCount, IntPtr itemBuffer);

    [DllImport("pdh.dll")]
    private static extern uint PdhCloseQuery(IntPtr query);
}
