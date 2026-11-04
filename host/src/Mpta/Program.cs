using System.Diagnostics;
using System.Text;
using System.Text.Json;
using Mpta.Cli;
using Mpta.Core.External;
using Mpta.Core.Net;
using Mpta.Core.Util;
using Mpta.Server;

namespace Mpta;

public static class Program
{
    private const string Usage = """
        mpta — PC terminal host for Android mirroring

        usage:
          mpta serve [--port N] [--bind ip]...
          mpta list
          mpta new [--shell pwsh|powershell|cmd|bash|wsl|<exe>] [--cwd D] [--title T] [--attach|--no-attach]
          mpta attach <id>
          mpta pair
          mpta devices
          mpta revoke <deviceId>
          mpta console-info <pid>...      (internal helper)
          mpta console-reader <pid>       (internal helper)

        client verbs accept: --port N (default: $MPTA_PORT or config.json port) and --home DIR (default: $MPTA_HOME or %APPDATA%\mpta)
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
                    if (rest.Count != 1 || !int.TryParse(rest[0], out var rpid)) { Console.Error.WriteLine("usage: mpta console-reader <pid>"); return 2; }
                    return ConsoleHelpers.RunConsoleReader(rpid);
                case "serve":
                    return await Serve(rest);
            }

            var opts = Options.Parse(rest);
            var home = opts.Home ?? MptaPaths.Home;
            int port = opts.Port ?? EnvPort() ?? MptaConfig.LoadOrCreate(home).Port;
            using var api = new ApiClient(home, port);
            switch (verb)
            {
                case "list": return await ListCmd(api);
                case "new": return await NewCmd(api, opts);
                case "attach":
                    if (opts.Positional.Count != 1) throw new CliException("usage: mpta attach <id>");
                    return await AttachCommand.RunAsync(api, opts.Positional[0]);
                case "pair": return await PairCmd(api);
                case "devices": return await DevicesCmd(api);
                case "revoke":
                    if (opts.Positional.Count != 1) throw new CliException("usage: mpta revoke <deviceId>");
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
            Console.Error.WriteLine("mpta: " + ex.Message);
            return 1;
        }
        catch (FormatException ex)
        {
            Console.Error.WriteLine("mpta: " + ex.Message);
            return 2;
        }
    }

    private static int? EnvPort() => int.TryParse(Environment.GetEnvironmentVariable("MPTA_PORT"), out var p) && p > 0 ? p : null;

    private sealed class Options
    {
        public int? Port;
        public string? Home;
        public string? Shell, Cwd, Title;
        public bool NoAttach;
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
                    case "--port": o.Port = int.Parse(Next()); break;
                    case "--home": o.Home = Path.GetFullPath(Next()); break;
                    case "--bind": o.Binds.Add(Next()); break;
                    case "--shell": o.Shell = Next(); break;
                    case "--cwd": o.Cwd = Path.GetFullPath(Next()); break;
                    case "--title": o.Title = Next(); break;
                    case "--attach": o.NoAttach = false; break;
                    case "--no-attach": o.NoAttach = true; break;
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
        var home = opts.Home ?? MptaPaths.Home;
        if (opts.Home != null) Environment.SetEnvironmentVariable("MPTA_HOME", home);
        Directory.CreateDirectory(home);
        var cfg = MptaConfig.LoadOrCreate(home);
        int port = opts.Port ?? cfg.Port;
        List<System.Net.IPAddress> binds;
        try { binds = HostAddresses.ResolveBindAddresses(opts.Binds); }
        catch (ArgumentException ex) { Console.Error.WriteLine("mpta: " + ex.Message); return 2; }
        return await new HostServer(home, port, binds).RunAsync();
    }

    private static string Str(JsonElement e, string name) =>
        e.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() ?? "" : "";

    private static async Task<int> ListCmd(ApiClient api)
    {
        var res = await api.SendAsync(HttpMethod.Get, "api/sessions");
        var rows = new List<string[]> { new[] { "KIND", "ID", "TITLE", "SHELL", "CWD", "STATE", "LAST ACTIVITY" } };
        foreach (var s in res.GetProperty("sessions").EnumerateArray())
        {
            var last = Str(s, "lastActivity");
            if (DateTimeOffset.TryParse(last, out var t)) last = t.ToLocalTime().ToString("yyyy-MM-dd HH:mm:ss");
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

    private static async Task<int> PairCmd(ApiClient api)
    {
        var r = await api.SendAsync(HttpMethod.Post, "api/pair/start");
        Console.WriteLine(Str(r, "qrAscii"));
        Console.WriteLine("payload: " + Str(r, "qrPayload"));
        Console.WriteLine("expires: " + Str(r, "expiresAt"));
        var png = Str(r, "qrPngPath");
        Console.WriteLine("png:     " + png);
        if (File.Exists(png))
        {
            try { Process.Start(new ProcessStartInfo(png) { UseShellExecute = true })?.Dispose(); }
            catch (System.ComponentModel.Win32Exception ex) { Console.Error.WriteLine($"could not open PNG: {ex.Message}"); }
        }
        return 0;
    }

    private static async Task<int> DevicesCmd(ApiClient api)
    {
        var r = await api.SendAsync(HttpMethod.Get, "api/devices");
        var rows = new List<string[]> { new[] { "ID", "NAME", "CREATED", "REVOKED" } };
        foreach (var d in r.GetProperty("devices").EnumerateArray())
            rows.Add(new[] { Str(d, "id"), Str(d, "name"), Str(d, "createdAt"), d.GetProperty("revoked").GetBoolean() ? "yes" : "no" });
        PrintTable(rows);
        return 0;
    }
}
