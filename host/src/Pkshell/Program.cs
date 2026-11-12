using System.Text;
using System.Text.Json;
using Pkshell.Cli;
using Pkshell.Core.External;
using Pkshell.Core.Net;
using Pkshell.Core.Util;
using Pkshell.Server;

namespace Pkshell;

public static class Program
{
    private const string Usage = """
        psh — PC terminal host for Android mirroring

        usage:
          psh serve [--port N] [--bind ip]... [--discovery-port N]   (N=0 disables LAN discovery)
          psh list
          psh new [--shell pwsh|powershell|cmd|bash|wsl|<exe>] [--cwd D] [--title T] [--attach|--no-attach]
          psh attach <id>
          psh pair                       (one-time PIN for phone pairing; --pin is accepted as a no-op)
          psh devices
          psh revoke <deviceId>
          psh update [--check] [--force [--kill-working]]
                                         (--force ends running host sessions; --kill-working also those where an AI is mid-turn)
          psh firewall-cleanup [--list]  (remove Windows Firewall rules of earlier copies; asks for administrator rights)
          psh console-info <pid>...      (internal helper)
          psh console-reader <pid>       (internal helper)

        client verbs accept: --port N (default: $PKSHELL_PORT or config.json port) and --home DIR (default: $PKSHELL_HOME or %APPDATA%\PocketShell)
        """;

    public static async Task<int> Main(string[] args)
    {
        if (args.Length == 0 || args[0] is "-h" or "--help" or "help")
        {
            Console.WriteLine(Usage);
            return args.Length == 0 ? 1 : 0;
        }
        var verb = args[0];
        var rest = args.Skip(1).ToList();
        try
        {
            switch (verb)
            {
                case "console-info":
                    return ConsoleHelpers.RunConsoleInfo(rest.Select(int.Parse));
                case "console-reader":
                    if (rest.Count != 1 || !int.TryParse(rest[0], out var rpid)) { Console.Error.WriteLine("usage: psh console-reader <pid>"); return 2; }
                    return ConsoleHelpers.RunConsoleReader(rpid);
                case "serve":
                    return await Serve(rest);
                case "firewall-cleanup":
                    if (rest.Any(a => a != "--list")) { Console.Error.WriteLine("usage: psh firewall-cleanup [--list]"); return 2; }
                    return FirewallCleanup.Run(listOnly: rest.Contains("--list"));
            }

            var opts = Options.Parse(rest);
            var home = opts.Home ?? PkshellPaths.Home;
            int port = opts.Port ?? EnvPort() ?? PkshellConfig.LoadOrCreate(home).Port;
            using var api = new ApiClient(home, port);
            switch (verb)
            {
                case "list": return await ListCmd(api);
                case "new": return await NewCmd(api, opts);
                case "attach":
                    if (opts.Positional.Count != 1) throw new CliException("usage: psh attach <id>");
                    return await AttachCommand.RunAsync(api, opts.Positional[0]);
                case "pair": return await PairPinCmd(api); // --pin kept as a no-op for compatibility
                case "update": return await UpdateCmd(api, opts);
                case "devices": return await DevicesCmd(api);
                case "revoke":
                    if (opts.Positional.Count != 1) throw new CliException("usage: psh revoke <deviceId>");
                    await api.SendAsync(HttpMethod.Delete, $"api/devices/{Uri.EscapeDataString(opts.Positional[0])}");
                    Console.WriteLine($"revoked {opts.Positional[0]}");
                    return 0;
                default:
                    Console.Error.WriteLine($"unknown command: {verb}\n");
                    Console.Error.WriteLine(Usage);
                    return 2;
            }
        }
        catch (CliException ex)
        {
            Console.Error.WriteLine("psh: " + ex.Message);
            return 1;
        }
        catch (FormatException ex)
        {
            Console.Error.WriteLine("psh: " + ex.Message);
            return 2;
        }
    }

    private static int? EnvPort() => int.TryParse(Pkshell.Core.Util.EnvVars.Get("PORT"), out var p) && p > 0 ? p : null;

    private sealed class Options
    {
        public int? Port;
        public string? Home;
        public string? Shell, Cwd, Title;
        public bool NoAttach;
        public bool Check, Force, KillWorking;
        public int? DiscoveryPort;
        public List<string> Binds = new();
        public List<string> Positional = new();

        public static Options Parse(List<string> args)
        {
            var o = new Options();
            for (int i = 0; i < args.Count; i++)
            {
                string Next() => i + 1 < args.Count ? args[++i] : throw new FormatException($"missing value for {args[i]}");
                switch (args[i])
                {
                    case "--port": o.Port = int.Parse(Next(), System.Globalization.CultureInfo.InvariantCulture); break;
                    case "--home": o.Home = Path.GetFullPath(Next()); break;
                    case "--bind": o.Binds.Add(Next()); break;
                    case "--shell": o.Shell = Next(); break;
                    case "--cwd": o.Cwd = Path.GetFullPath(Next()); break;
                    case "--title": o.Title = Next(); break;
                    case "--attach": o.NoAttach = false; break;
                    case "--no-attach": o.NoAttach = true; break;
                    case "--pin": break; // pair is always PIN; accepted for compatibility
                    case "--check": o.Check = true; break;
                    case "--force": o.Force = true; break;
                    case "--kill-working": o.KillWorking = true; break;
                    case "--discovery-port": o.DiscoveryPort = int.Parse(Next(), System.Globalization.CultureInfo.InvariantCulture); break;
                    default:
                        if (args[i].StartsWith("--", StringComparison.Ordinal)) throw new FormatException($"unknown option {args[i]}");
                        o.Positional.Add(args[i]);
                        break;
                }
            }
            return o;
        }
    }

    private static async Task<int> Serve(List<string> args)
    {
        var opts = Options.Parse(args);
        var home = opts.Home ?? PkshellPaths.Home;
        if (opts.Home != null) Environment.SetEnvironmentVariable("PKSHELL_HOME", home);
        Directory.CreateDirectory(home);
        var cfg = PkshellConfig.LoadOrCreate(home);
        int port = opts.Port ?? cfg.Port;
        List<System.Net.IPAddress> binds;
        try { binds = HostAddresses.ResolveBindAddresses(opts.Binds); }
        catch (ArgumentException ex) { Console.Error.WriteLine("psh: " + ex.Message); return 2; }
        int discoveryPort = opts.DiscoveryPort ?? cfg.DiscoveryPort;
        using var server = new HostServer(home, port, binds, args, discoveryPort, autoBinds: opts.Binds.Count == 0);
        return await server.RunAsync();
    }

    private static string Str(JsonElement e, string name) =>
        e.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() ?? "" : "";

    private static readonly string[] ListHeader = { "KIND", "ID", "TITLE", "SHELL", "CWD", "STATE", "LAST ACTIVITY" };
    private static readonly string[] DevicesHeader = { "ID", "NAME", "CREATED", "REVOKED" };

    private static async Task<int> ListCmd(ApiClient api)
    {
        var res = await api.SendAsync(HttpMethod.Get, "api/sessions");
        var rows = new List<string[]> { ListHeader };
        foreach (var s in res.GetProperty("sessions").EnumerateArray())
        {
            var last = Str(s, "lastActivity");
            if (DateTimeOffset.TryParse(last, out var t)) last = t.ToLocalTime().ToString("yyyy-MM-dd HH:mm:ss", System.Globalization.CultureInfo.CurrentCulture);
            rows.Add(new[] { Str(s, "kind"), Str(s, "id"), Str(s, "title"), Str(s, "shell"), Str(s, "cwd"), Str(s, "state"), last });
        }
        PrintTable(rows);
        return 0;
    }

    private static void PrintTable(List<string[]> rows)
    {
        if (rows.Count == 0) return;
        var widths = Enumerable.Range(0, rows[0].Length).Select(c => Math.Min(48, rows.Max(r => r[c].Length))).ToArray();
        foreach (var r in rows)
        {
            var sb = new StringBuilder();
            for (int c = 0; c < r.Length; c++)
            {
                var cell = r[c].Length > widths[c] ? r[c][..(widths[c] - 3)] + "..." : r[c];
                sb.Append(cell.PadRight(widths[c] + 2));
            }
            Console.WriteLine(sb.ToString().TrimEnd());
        }
    }

    private static async Task<int> NewCmd(ApiClient api, Options o)
    {
        int cols = 120, rows = 30;
        try { cols = Console.WindowWidth; rows = Console.WindowHeight; } catch (IOException) { }
        var s = await api.SendAsync(HttpMethod.Post, "api/sessions", new { shell = o.Shell, cwd = o.Cwd, title = o.Title, cols, rows });
        var id = Str(s, "id");
        if (o.NoAttach)
        {
            Console.WriteLine(id);
            return 0;
        }
        return await AttachCommand.RunAsync(api, id);
    }

    private static async Task<int> PairPinCmd(ApiClient api)
    {
        var r = await api.SendAsync(HttpMethod.Post, "api/pair/start", new { mode = "pin" });
        var pin = Str(r, "pin");
        var fpShort = Str(r, "fpShort");
        var hosts = r.TryGetProperty("hosts", out var h) && h.ValueKind == JsonValueKind.Array
            ? string.Join(", ", h.EnumerateArray().Select(x => x.GetString())) : "";
        Console.WriteLine();
        foreach (var line in BigDigits.Render(pin)) Console.WriteLine("    " + line);
        Console.WriteLine();
        Console.WriteLine($"    PIN:          {pin}");
        Console.WriteLine($"    fingerprint:  {fpShort}");
        Console.WriteLine($"    hosts:        {hosts}  (port {r.GetProperty("port").GetInt32()})");
        Console.WriteLine($"    expires:      {Str(r, "expiresAt")}  (one-time; 5 wrong attempts invalidate it)");
        Console.WriteLine();
        Console.WriteLine("    On the phone: pick this PC (LAN search) or enter its IP, type the PIN, and confirm the");
        Console.WriteLine("    fingerprint shown on the phone matches the one above before pairing.");
        return 0;
    }

    private static async Task<int> UpdateCmd(ApiClient api, Options o)
    {
        var st = await api.SendAsync(HttpMethod.Post, "api/update/check");
        var current = Str(st, "current");
        var latest = Str(st, "latest");
        var state = Str(st, "state");
        var err = Str(st, "error");
        Console.WriteLine($"current: {current}");
        Console.WriteLine($"latest:  {(latest.Length > 0 ? latest : "-")}");
        Console.WriteLine($"state:   {state}{(err.Length > 0 ? " (" + err + ")" : "")}");
        if (o.Check) return state == "error" ? 1 : 0;
        if (state is "none") { Console.WriteLine("already up to date"); return 0; }
        if (state is "error" && (latest.Length == 0 || latest == current)) return 1;
        var r = await api.SendRawAsync(HttpMethod.Post, "api/update/apply", new { force = o.Force, killWorking = o.KillWorking });
        if (r.Status == 409)
        {
            bool working = r.Body.TryGetProperty("workingAgents", out var w) && w.TryGetInt32(out var wn) && wn > 0;
            Console.Error.WriteLine($"psh: {Str(r.Body, "error")}");
            if (r.Body.TryGetProperty("sessions", out var list) && list.ValueKind == JsonValueKind.Array)
            {
                foreach (var s in list.EnumerateArray())
                {
                    var agentState = Str(s, "agentState");
                    Console.Error.WriteLine($"  {Str(s, "id")}  {Str(s, "title")}{(agentState.Length > 0 ? $"  [{Str(s, "agent")} {agentState}]" : "")}");
                }
            }
            Console.Error.WriteLine(o.Force && working
                ? "these sessions would be terminated; add --kill-working to do it while an AI is mid-turn"
                : "these sessions would be terminated; use --force to do it (it is installed by itself once they end)");
            return 1;
        }
        if (r.Status != 200) { Console.Error.WriteLine($"psh: apply failed: HTTP {r.Status} {r.Text}"); return 1; }
        Console.WriteLine($"applying update to {latest}; the host restarts on the same port when ready...");
        // Follow the handoff: the host is briefly unreachable while the new process takes over the port.
        var deadline = DateTime.UtcNow.AddSeconds(90);
        string shown = "";
        while (DateTime.UtcNow < deadline)
        {
            await Task.Delay(1000);
            try
            {
                var info = await api.SendRawAsync(HttpMethod.Get, "api/info");
                if (info.Status != 200) continue;
                if (Str(info.Body, "version") == latest) { Console.WriteLine($"updated: host now runs {latest}"); return 0; }
                var u = info.Body.TryGetProperty("update", out var ue) ? ue : default;
                if (u.ValueKind == JsonValueKind.Object && Str(u, "state") == "error")
                {
                    Console.Error.WriteLine($"psh: update failed: {Str(u, "error")}");
                    return 1;
                }
                if (u.ValueKind != JsonValueKind.Object) continue;
                // A download in progress is not a stall: say how far it is and keep waiting.
                string line = Str(u, "state");
                if (line == "downloading")
                {
                    deadline = DateTime.UtcNow.AddSeconds(90);
                    long got = u.TryGetProperty("downloaded", out var d) && d.TryGetInt64(out var dv) ? dv : 0;
                    long total = u.TryGetProperty("total", out var t) && t.TryGetInt64(out var tv) ? tv : 0;
                    string step = Str(u, "step");
                    line = step is "verify" or "extract" ? step
                        : total > 0 ? $"downloading {got / 1048576.0:0.0} / {total / 1048576.0:0.0} MB ({got * 100 / total}%)"
                        : $"downloading {got / 1048576.0:0.0} MB";
                }
                if (line.Length > 0 && line != shown) Console.WriteLine("  " + (shown = line));
            }
            catch (Exception ex) when (ex is CliException or TaskCanceledException) { /* port in handoff */ }
        }
        Console.Error.WriteLine("psh: timed out waiting for the updated host; check `psh update --check` and logs/host.log");
        return 1;
    }

    private static async Task<int> DevicesCmd(ApiClient api)
    {
        var r = await api.SendAsync(HttpMethod.Get, "api/devices");
        var rows = new List<string[]> { DevicesHeader };
        foreach (var d in r.GetProperty("devices").EnumerateArray())
            rows.Add(new[] { Str(d, "id"), Str(d, "name"), Str(d, "createdAt"), d.GetProperty("revoked").GetBoolean() ? "yes" : "no" });
        PrintTable(rows);
        return 0;
    }
}
