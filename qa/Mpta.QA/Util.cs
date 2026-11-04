using System.Diagnostics;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace Mpta.QA;

internal enum Outcome { PASS, FAIL, SKIP }

internal sealed record Result(string Id, string Name, Outcome Outcome, string Evidence, TimeSpan Duration);

/// <summary>Evidence collector for one scenario. Each Check() records an ok/FAIL line; any failed check fails the scenario.</summary>
internal sealed class Ev
{
    private readonly List<string> _lines = new();
    public bool Failed { get; private set; }
    public string? SkipReason { get; private set; }

    public void Info(string s)
    {
        lock (_lines) _lines.Add(s);
    }

    public bool Check(bool ok, string what)
    {
        Info((ok ? "ok: " : "FAIL: ") + what);
        if (!ok) Failed = true;
        return ok;
    }

    public Outcome Skip(string reason)
    {
        SkipReason = reason;
        Info("SKIP: " + reason);
        return Outcome.SKIP;
    }

    public Outcome Result() => Failed ? Outcome.FAIL : Outcome.PASS;

    public string Text
    {
        get { lock (_lines) return string.Join(" | ", _lines); }
    }
}

internal static class Util
{
    public static string Trunc(string? s, int max)
    {
        if (string.IsNullOrEmpty(s)) return "";
        s = s.Replace("\r", " ").Replace("\n", " ");
        return s.Length <= max ? s : s[..max] + "...";
    }

    public static string AllMessages(Exception e)
    {
        var sb = new StringBuilder(e.Message);
        for (var i = e.InnerException; i != null; i = i.InnerException) sb.Append(" -> ").Append(i.Message);
        return sb.ToString();
    }

    public static string RedactToken(string url) => Regex.Replace(url, "token=[^&]*", "token=***");

    /// <summary>Printable escape of raw bytes: ASCII printable as-is, everything else as \xNN.</summary>
    public static string Esc(ReadOnlySpan<byte> b, int max = 400)
    {
        var sb = new StringBuilder();
        foreach (var x in b)
        {
            if (sb.Length >= max) { sb.Append("..."); break; }
            if (x >= 0x20 && x < 0x7f && x != (byte)'\\' && x != (byte)'|') sb.Append((char)x);
            else sb.Append($"\\x{x:x2}");
        }
        return sb.ToString();
    }

    public static string Hex(ReadOnlySpan<byte> b) => Convert.ToHexString(b);

    public static string NormPath(string? p)
    {
        if (string.IsNullOrWhiteSpace(p)) return "";
        try { p = Path.GetFullPath(p.Trim()); }
        catch (Exception e) when (e is ArgumentException or NotSupportedException or PathTooLongException) { p = p.Trim(); }
        return p.TrimEnd('\\', '/');
    }

    public static bool SamePath(string? a, string? b) =>
        NormPath(a).Length > 0 && string.Equals(NormPath(a), NormPath(b), StringComparison.OrdinalIgnoreCase);

    public static double Median(List<double> v)
    {
        if (v.Count == 0) return double.NaN;
        var s = v.OrderBy(x => x).ToList();
        return s.Count % 2 == 1 ? s[s.Count / 2] : (s[s.Count / 2 - 1] + s[s.Count / 2]) / 2;
    }

    public static string? Str(JsonNode? n, string key) => n?[key]?.ToString();

    public static int? Int(JsonNode? n, string key) => int.TryParse(n?[key]?.ToString(), out var v) ? v : null;

    public static string Compact(JsonNode? n, int max = 300) => Trunc(n?.ToJsonString() ?? "null", max);

    public static string? FindOnPath(string exe)
    {
        foreach (var dir in (Environment.GetEnvironmentVariable("PATH") ?? "").Split(';', StringSplitOptions.RemoveEmptyEntries))
        {
            try
            {
                var p = Path.Combine(dir.Trim(), exe);
                if (File.Exists(p)) return p;
            }
            catch (ArgumentException) { }
        }
        return null;
    }

    public static int FreePort()
    {
        var l = new TcpListener(IPAddress.Loopback, 0);
        l.Start();
        int port = ((IPEndPoint)l.LocalEndpoint).Port;
        l.Stop();
        return port;
    }

    public static int FreeUdpPort()
    {
        using var u = new UdpClient(new IPEndPoint(IPAddress.Loopback, 0));
        return ((IPEndPoint)u.Client.LocalEndPoint!).Port;
    }

    /// <summary>Returns (LAN private IPv4, Tailscale CGNAT IPv4) of up interfaces; LAN prefers interfaces with a gateway.</summary>
    public static (string? Lan, string? Tailscale) DetectAddresses()
    {
        var lan = new List<(string Ip, bool Gw)>();
        string? ts = null;
        foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (ni.OperationalStatus != OperationalStatus.Up || ni.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
            var props = ni.GetIPProperties();
            bool gw = props.GatewayAddresses.Any(g => g.Address.AddressFamily == AddressFamily.InterNetwork && !g.Address.Equals(IPAddress.Any));
            foreach (var ua in props.UnicastAddresses)
            {
                if (ua.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                var b = ua.Address.GetAddressBytes();
                bool priv = b[0] == 10 || (b[0] == 172 && b[1] >= 16 && b[1] <= 31) || (b[0] == 192 && b[1] == 168);
                bool cgnat = b[0] == 100 && b[1] >= 64 && b[1] <= 127;
                if (priv) lan.Add((ua.Address.ToString(), gw));
                else if (cgnat) ts ??= ua.Address.ToString();
            }
        }
        var pick = lan.OrderByDescending(x => x.Gw).Select(x => x.Ip).FirstOrDefault();
        return (pick, ts);
    }

    /// <summary>Runs a process to completion with a hard timeout (tree-killed on timeout). Returns exit code (-999 on timeout) and combined output.</summary>
    public static async Task<(int Code, string Output)> Run(string file, IEnumerable<string> args, string? cwd, IDictionary<string, string>? env, int timeoutMs, string? stdin = null)
    {
        var psi = new ProcessStartInfo(file)
        {
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            RedirectStandardInput = true,
            CreateNoWindow = true,
            StandardOutputEncoding = Encoding.UTF8,
            StandardErrorEncoding = Encoding.UTF8,
        };
        foreach (var a in args) psi.ArgumentList.Add(a);
        if (cwd != null) psi.WorkingDirectory = cwd;
        if (env != null) foreach (var kv in env) psi.Environment[kv.Key] = kv.Value;
        var sb = new StringBuilder();
        using var p = new Process { StartInfo = psi };
        p.OutputDataReceived += (_, e) => { if (e.Data != null) lock (sb) sb.AppendLine(e.Data); };
        p.ErrorDataReceived += (_, e) => { if (e.Data != null) lock (sb) sb.AppendLine(e.Data); };
        try
        {
            p.Start();
        }
        catch (System.ComponentModel.Win32Exception e)
        {
            return (-998, $"start failed: {e.Message}");
        }
        p.BeginOutputReadLine();
        p.BeginErrorReadLine();
        try
        {
            if (stdin != null) await p.StandardInput.WriteAsync(stdin);
            p.StandardInput.Close();
        }
        catch (IOException) { }
        using var cts = new CancellationTokenSource(timeoutMs);
        try
        {
            await p.WaitForExitAsync(cts.Token);
        }
        catch (OperationCanceledException)
        {
            try { p.Kill(entireProcessTree: true); }
            catch (InvalidOperationException) { }
            catch (System.ComponentModel.Win32Exception) { }
            p.WaitForExit(3000);
            lock (sb) return (-999, sb.ToString());
        }
        p.WaitForExit(); // flush async output readers
        lock (sb) return (p.ExitCode, sb.ToString());
    }

    /// <summary>Lists (pid, commandLine) for processes with the given image name via CIM (no extra NuGet dependency).</summary>
    public static async Task<List<(int Pid, string Cmd)>> ProcessesByName(string imageName)
    {
        var script = $"Get-CimInstance Win32_Process -Filter \"Name='{imageName}'\" | ForEach-Object {{ \"$($_.ProcessId)|$($_.CommandLine)\" }}";
        var (_, output) = await Run("powershell.exe", new[] { "-NoProfile", "-NonInteractive", "-Command", script }, null, null, 30000);
        var list = new List<(int, string)>();
        foreach (var line in output.Split('\n', StringSplitOptions.RemoveEmptyEntries))
        {
            var i = line.IndexOf('|');
            if (i > 0 && int.TryParse(line[..i].Trim(), out var pid)) list.Add((pid, line[(i + 1)..].Trim()));
        }
        return list;
    }

    /// <summary>Kills `mpta.exe attach &lt;id&gt;` processes (e.g. spawned by Windows Terminal, outside our job objects).</summary>
    public static async Task<List<int>> KillAttachProcesses(IReadOnlyCollection<string> sessionIds)
    {
        var killed = new List<int>();
        if (sessionIds.Count == 0) return killed;
        foreach (var (pid, cmd) in await ProcessesByName("mpta.exe"))
        {
            if (!cmd.Contains("attach", StringComparison.OrdinalIgnoreCase)) continue;
            if (!sessionIds.Any(id => cmd.Contains(id, StringComparison.OrdinalIgnoreCase))) continue;
            if (KillPid(pid)) killed.Add(pid);
        }
        return killed;
    }

    public static bool KillPid(int pid)
    {
        try
        {
            using var p = Process.GetProcessById(pid);
            p.Kill(entireProcessTree: true);
            p.WaitForExit(3000);
            return true;
        }
        catch (ArgumentException) { return false; }
        catch (InvalidOperationException) { return false; }
        catch (System.ComponentModel.Win32Exception) { return false; }
    }

    public static bool PidAlive(int pid)
    {
        try
        {
            using var p = Process.GetProcessById(pid);
            return !p.HasExited;
        }
        catch (ArgumentException) { return false; }
        catch (InvalidOperationException) { return false; }
    }

    public static bool WaitPidExit(int pid, int ms)
    {
        try
        {
            using var p = Process.GetProcessById(pid);
            return p.WaitForExit(ms);
        }
        catch (ArgumentException) { return true; }
        catch (InvalidOperationException) { return true; }
    }
}
