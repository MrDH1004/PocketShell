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
    private static Dictionary<int, int> _pidOf = new();

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
            var pidOf = new Dictionary<int, int>();
            foreach (var (port, pid) in ListeningPorts())
            {
                if (pid is 0 or 4 || pid == self || port == ownPort) continue; // idle / System / this host
                if (!cwdOf.TryGetValue(pid, out var cwd)) cwdOf[pid] = cwd = ProcessCwd.TryGet(pid);
                if (cwd == null) continue;
                list.Add((port, cwd));
                pidOf[port] = pid;
            }
            _cached = list;
            _pidOf = pidOf;
            _at = DateTime.UtcNow;
            return list;
        }
    }

    private static readonly Dictionary<int, (bool Page, DateTime At)> PageCache = new();

    /// <summary>
    /// The web pages among a folder's listening <paramref name="ports"/>, for the globe: a "GET /" answers 2xx/3xx with
    /// an HTML (or no) content type within 400 ms, so debug adapters (9229), dev-tool internals (wrangler's 403s),
    /// databases and emulator consoles are left out. When some of those ports are named in the command line of a
    /// process in the folder ("--port 8790"), only those are kept: a dev server's random helper sockets go too.
    /// </summary>
    public static List<int> Pages(IReadOnlyList<int> ports)
    {
        var pages = PagesOnly(ports);
        if (pages.Count < 2) return pages;
        Dictionary<int, int> pidOf;
        lock (Gate) pidOf = _pidOf;
        var lines = ports.Select(p => pidOf.TryGetValue(p, out var pid) ? pid : 0).Where(pid => pid != 0).Distinct()
            .Select(ProcessCwd.TryGetCommandLine).OfType<string>().ToList();
        return Named(pages, lines);
    }

    /// <summary>The <paramref name="pages"/> named in one of <paramref name="commandLines"/>, or all of them when none is.</summary>
    public static List<int> Named(IReadOnlyList<int> pages, IReadOnlyList<string> commandLines)
    {
        var named = pages.Where(p => commandLines.Any(line =>
            System.Text.RegularExpressions.Regex.IsMatch(line, $@"(?<![0-9]){p}(?![0-9])"))).ToList();
        return named.Count > 0 ? named : pages.ToList();
    }

    /// <summary><paramref name="ports"/> serving a web page at "/" (see <see cref="Pages"/>); cached 30 s per port.</summary>
    public static List<int> PagesOnly(IEnumerable<int> ports)
    {
        var result = new List<int>();
        foreach (var port in ports)
        {
            lock (PageCache)
            {
                if (PageCache.TryGetValue(port, out var c) && DateTime.UtcNow - c.At < TimeSpan.FromSeconds(30))
                {
                    if (c.Page) result.Add(port);
                    continue;
                }
            }
            var page = ServesPage(port);
            lock (PageCache) PageCache[port] = (page, DateTime.UtcNow);
            if (page) result.Add(port);
        }
        return result;
    }

    private static bool ServesPage(int port)
    {
        foreach (var addr in new[] { System.Net.IPAddress.Loopback, System.Net.IPAddress.IPv6Loopback })
        {
            try
            {
                using var c = new System.Net.Sockets.TcpClient(addr.AddressFamily) { ReceiveTimeout = 400, SendTimeout = 400 };
                if (!c.ConnectAsync(addr, port).Wait(400)) continue;
                var s = c.GetStream();
                var req = System.Text.Encoding.ASCII.GetBytes($"GET / HTTP/1.0\r\nHost: localhost:{port}\r\nAccept: text/html\r\n\r\n");
                s.Write(req, 0, req.Length);
                // Status line and headers only (up to 8 KB); the body is not read.
                var buf = new byte[8192];
                int n = 0;
                while (n < buf.Length)
                {
                    int r = s.Read(buf, n, buf.Length - n);
                    if (r <= 0) break;
                    n += r;
                    if (System.Text.Encoding.ASCII.GetString(buf, 0, n).Contains("\r\n\r\n", StringComparison.Ordinal)) break;
                }
                return IsPageResponse(System.Text.Encoding.ASCII.GetString(buf, 0, n));
            }
            catch (Exception ex) when (ex is System.Net.Sockets.SocketException or IOException or AggregateException) { }
        }
        return false;
    }

    /// <summary>An HTTP response head with a 2xx/3xx status and an HTML content type (or none, as some servers send).</summary>
    public static bool IsPageResponse(string head)
    {
        var m = System.Text.RegularExpressions.Regex.Match(head, @"^HTTP/\d(?:\.\d)? (\d{3})");
        if (!m.Success) return false;
        var status = int.Parse(m.Groups[1].Value, System.Globalization.CultureInfo.InvariantCulture);
        if (status is < 200 or >= 400) return false;
        var type = System.Text.RegularExpressions.Regex.Match(head, @"\r\ncontent-type:\s*([^;\r\n]*)", System.Text.RegularExpressions.RegexOptions.IgnoreCase);
        return !type.Success || type.Groups[1].Value.Trim().Equals("text/html", StringComparison.OrdinalIgnoreCase);
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
