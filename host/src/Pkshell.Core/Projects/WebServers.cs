using System.Runtime.InteropServices;
using Pkshell.Core.External;

namespace Pkshell.Core.Projects;

/// <summary>
/// Web servers a project runs right now (a Vite / Next / Django dev server started in its folder): TCP ports in the
/// LISTEN state whose owning process works in that folder. The phone shows a globe on the session card that opens
/// the page through the web view (0.2.88+). Results are cached for a few seconds (the phone polls the list).
/// </summary>
public static class WebServers
{
    private static readonly object Gate = new();
    private static DateTime _at = DateTime.MinValue;
    private static List<(int Port, string Cwd)> _cached = [];

    /// <summary>Listening ports whose process works in <paramref name="folder"/> (or below it), ascending.</summary>
    public static List<int> For(string folder, IReadOnlyList<(int Port, string Cwd)> listeners)
    {
        if (string.IsNullOrWhiteSpace(folder)) return [];
        var trimmed = Path.TrimEndingDirectorySeparator(folder);
        // A drive root or the user folder holds nearly every process: those are not one project.
        if (string.Equals(Path.TrimEndingDirectorySeparator(Path.GetPathRoot(folder) ?? ""), trimmed, StringComparison.OrdinalIgnoreCase)
            || string.Equals(Path.TrimEndingDirectorySeparator(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile)), trimmed, StringComparison.OrdinalIgnoreCase))
            return [];
        var root = trimmed + Path.DirectorySeparatorChar;
        return listeners
            .Where(l => (Path.TrimEndingDirectorySeparator(l.Cwd) + Path.DirectorySeparatorChar).StartsWith(root, StringComparison.OrdinalIgnoreCase))
            .Select(l => l.Port).Distinct().Order().ToList();
    }

    /// <summary>(port, process folder) of every listening TCP socket of other user processes; cached 5 s.</summary>
    public static IReadOnlyList<(int Port, string Cwd)> Listeners(int ownPort)
    {
        lock (Gate)
        {
            if (DateTime.UtcNow - _at < TimeSpan.FromSeconds(5)) return _cached;
            var self = Environment.ProcessId;
            var cwdOf = new Dictionary<int, string?>();
            var list = new List<(int, string)>();
            foreach (var (port, pid) in ListeningPorts())
            {
                if (pid is 0 or 4 || pid == self || port == ownPort) continue; // idle / System / this host
                if (!cwdOf.TryGetValue(pid, out var cwd)) cwdOf[pid] = cwd = ProcessCwd.TryGet(pid);
                if (cwd != null) list.Add((port, cwd));
            }
            _cached = list;
            _at = DateTime.UtcNow;
            return list;
        }
    }

    private static readonly Dictionary<int, (bool Http, DateTime At)> HttpCache = new();

    /// <summary>
    /// <paramref name="ports"/> that answer HTTP on localhost (a "HEAD /" gets an "HTTP/" status line within 400 ms);
    /// emulator consoles, debug adapters and databases listening in the same folder are left out. Cached 30 s per port.
    /// </summary>
    public static List<int> HttpOnly(IEnumerable<int> ports)
    {
        var result = new List<int>();
        foreach (var port in ports)
        {
            lock (HttpCache)
            {
                if (HttpCache.TryGetValue(port, out var c) && DateTime.UtcNow - c.At < TimeSpan.FromSeconds(30))
                {
                    if (c.Http) result.Add(port);
                    continue;
                }
            }
            var http = AnswersHttp(port);
            lock (HttpCache) HttpCache[port] = (http, DateTime.UtcNow);
            if (http) result.Add(port);
        }
        return result;
    }

    private static bool AnswersHttp(int port)
    {
        foreach (var addr in new[] { System.Net.IPAddress.Loopback, System.Net.IPAddress.IPv6Loopback })
        {
            try
            {
                using var c = new System.Net.Sockets.TcpClient(addr.AddressFamily) { ReceiveTimeout = 400, SendTimeout = 400 };
                if (!c.ConnectAsync(addr, port).Wait(400)) continue;
                var s = c.GetStream();
                var req = System.Text.Encoding.ASCII.GetBytes($"HEAD / HTTP/1.0\r\nHost: localhost:{port}\r\n\r\n");
                s.Write(req, 0, req.Length);
                var buf = new byte[5];
                int n = 0;
                while (n < 5)
                {
                    int r = s.Read(buf, n, 5 - n);
                    if (r <= 0) break;
                    n += r;
                }
                return n == 5 && System.Text.Encoding.ASCII.GetString(buf) == "HTTP/";
            }
            catch (Exception ex) when (ex is System.Net.Sockets.SocketException or IOException or AggregateException) { }
        }
        return false;
    }

    // iphlpapi.h: GetExtendedTcpTable(pTcpTable, pdwSize, bOrder, ulAf, TableClass, Reserved).
    [DllImport("iphlpapi.dll", SetLastError = true)]
    private static extern uint GetExtendedTcpTable(IntPtr table, ref int size, bool order, int af, int tableClass, uint reserved);

    private const int AF_INET = 2;
    private const int AF_INET6 = 23;
    private const int TCP_TABLE_OWNER_PID_LISTENER = 3;
    private const uint ERROR_INSUFFICIENT_BUFFER = 122;

    /// <summary>(port, owning pid) of every listening TCP socket, IPv4 and IPv6.</summary>
    public static List<(int Port, int Pid)> ListeningPorts()
    {
        var result = new List<(int, int)>();
        // MIB_TCPROW_OWNER_PID: state, localAddr, localPort, remoteAddr, remotePort, owningPid (6 DWORDs = 24 bytes).
        Read(AF_INET, 24, row => (Port(Marshal.ReadInt32(row, 8)), Marshal.ReadInt32(row, 20)), result);
        // MIB_TCP6ROW_OWNER_PID: localAddr[16], localScopeId, localPort, remoteAddr[16], remoteScopeId, remotePort,
        // state, owningPid = 56 bytes; localPort at 20, owningPid at 52.
        Read(AF_INET6, 56, row => (Port(Marshal.ReadInt32(row, 20)), Marshal.ReadInt32(row, 52)), result);
        return result;
    }

    /// <summary>The port DWORD holds the port in network byte order in its low 16 bits.</summary>
    private static int Port(int raw) => ((raw & 0xFF) << 8) | ((raw >> 8) & 0xFF);

    private static void Read(int af, int rowSize, Func<IntPtr, (int, int)> parse, List<(int, int)> into)
    {
        int size = 0;
        uint rc = GetExtendedTcpTable(IntPtr.Zero, ref size, false, af, TCP_TABLE_OWNER_PID_LISTENER, 0);
        if (rc != ERROR_INSUFFICIENT_BUFFER || size <= 0) return;
        var buf = Marshal.AllocHGlobal(size);
        try
        {
            rc = GetExtendedTcpTable(buf, ref size, false, af, TCP_TABLE_OWNER_PID_LISTENER, 0);
            if (rc != 0) return;
            int n = Marshal.ReadInt32(buf);
            for (int i = 0; i < n; i++)
            {
                var row = buf + 4 + i * rowSize;
                into.Add(parse(row));
            }
        }
        finally { Marshal.FreeHGlobal(buf); }
    }
}
