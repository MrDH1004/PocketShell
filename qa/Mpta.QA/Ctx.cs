using System.Security.Cryptography;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace Mpta.QA;

/// <summary>Shared state for host scenarios plus small protocol helpers (DESIGN §4/§5).</summary>
internal sealed class Ctx
{
    public required string Root { get; init; }
    public required string OutDir { get; init; }
    public required string Work { get; init; }
    public required HostRunner Host { get; init; }
    public required Job Aux { get; init; }
    public string? Lan { get; init; }
    public string? Tailscale { get; init; }
    /// <summary>"pwsh" when pwsh.exe is on PATH, else "powershell" (both accept the PowerShell expressions used below).</summary>
    public required string Shell { get; init; }

    /// <summary>versionName from repo-root version.properties (DESIGN §11.1).</summary>
    public string VersionName { get; init; } = "";
    /// <summary>Local releases feed the main host's MPTA_UPDATE_URL points at (no newer release).</summary>
    public FakeReleaseServer? Releases { get; init; }
    /// <summary>UDP LAN discovery port given to the main host (--discovery-port).</summary>
    public int DiscoveryPort { get; init; }
    /// <summary>Additional hosts started by scenarios (update cases); killed by the global cleanup.</summary>
    public List<HostRunner> ExtraHosts { get; } = new();

    public Api Local { get; private set; } = null!;
    public Api? LanApi { get; private set; }

    public List<string> CreatedSessions { get; } = new();
    public List<int> ExtPids { get; } = new();
    public HashSet<string> AttachIds { get; } = new();

    public int Port => Host.Port;
    public string Token => Host.Token;
    public string Fp => Host.Fp;
    public string Exe => Host.Exe;
    public string Home => Host.Home;

    public void ResetApis()
    {
        Local?.Dispose();
        LanApi?.Dispose();
        Local = new Api("127.0.0.1", Port, Fp);
        LanApi = Lan != null ? new Api(Lan, Port, Fp) : null;
    }

    public static string R() => Convert.ToHexString(RandomNumberGenerator.GetBytes(3)).ToLowerInvariant();

    public static string Id(JsonNode? s) => Util.Str(s, "id") ?? throw new InvalidOperationException("session JSON has no id: " + Util.Compact(s));

    public string MakeDir(string prefix)
    {
        var d = Path.Combine(Work, $"{prefix}-{R()}");
        Directory.CreateDirectory(d);
        return d;
    }

    /// <summary>
    /// A PowerShell (or cmd) command that prints <paramref name="marker"/> without the typed echo containing it,
    /// so a marker found in the stream proves the command actually ran.
    /// </summary>
    public static string Expr(string marker, string shell = "powershell")
    {
        int k = Math.Max(1, marker.Length / 2);
        return shell == "cmd" ? $"echo {marker[..k]}^{marker[k..]}" : $"'{marker[..k]}'+'{marker[k..]}'";
    }

    public string Expr(string marker) => Expr(marker, Shell);

    /// <summary>Command printing QCWD=&lt;current dir&gt; for the given shell.</summary>
    public static string CwdProbe(string? shell) =>
        shell == "cmd" ? "echo QC^WD=%CD%" : "'QC'+'WD='+(Get-Location).Path";

    public static string? LastCwd(byte[] data)
    {
        var s = System.Text.Encoding.UTF8.GetString(data);
        var ms = Regex.Matches(s, @"QCWD=([^\r\n\x1b""]+)");
        return ms.Count == 0 ? null : ms[^1].Groups[1].Value.Trim();
    }

    public async Task<JsonNode> NewSession(Ev ev, string title, string? cwd = null, string? shell = null, int cols = 120, int rows = 30)
    {
        var body = new Dictionary<string, object> { ["shell"] = shell ?? Shell, ["title"] = title, ["cols"] = cols, ["rows"] = rows };
        if (cwd != null) body["cwd"] = cwd;
        var r = await Local.Post("/api/sessions", Token, body);
        if (!r.Ok || r.Json == null) throw new InvalidOperationException($"POST /api/sessions failed: {r}");
        var id = Id(r.Json);
        CreatedSessions.Add(id);
        ev.Info($"created session {id} ({title})");
        return r.Json;
    }

    public string WsUrl(string id, string client = "qa", int cols = 120, int rows = 30, string extra = "", string? token = null, string? host = null)
    {
        var tok = token ?? Token;
        var q = $"client={client}&cols={cols}&rows={rows}{extra}";
        if (tok.Length > 0) q = $"token={Uri.EscapeDataString(tok)}&" + q;
        return $"wss://{host ?? "127.0.0.1"}:{Port}/ws/sessions/{Uri.EscapeDataString(id)}?{q}";
    }

    public Task<WsClient> Open(string id, string client = "qa", int cols = 120, int rows = 30, string extra = "", string? token = null, string? host = null) =>
        WsClient.Connect(WsUrl(id, client, cols, rows, extra, token, host), Fp);

    /// <summary>Waits for replay-end and for the shell to go quiet (prompt drawn).</summary>
    public static async Task Ready(WsClient ws, int quietMs = 900)
    {
        await ws.WaitReplayEnd(5000);
        var sw = System.Diagnostics.Stopwatch.StartNew();
        while (ws.Length == 0 && sw.ElapsedMilliseconds < 10000) await Task.Delay(50);
        await ws.WaitQuiet(quietMs, 15000);
    }

    public async Task<JsonNode?> GetSession(string id)
    {
        var r = await Local.Get($"/api/sessions/{Uri.EscapeDataString(id)}", Token);
        return r.Ok ? r.Json : null;
    }

    public async Task<JsonArray> ListSessions()
    {
        var r = await Local.Get("/api/sessions", Token);
        if (!r.Ok) throw new InvalidOperationException("GET /api/sessions failed: " + r);
        return r.Json?["sessions"] as JsonArray ?? throw new InvalidOperationException("no sessions[] in " + Util.Trunc(r.Body, 200));
    }

    public static JsonNode? FindById(JsonArray list, string id) => list.FirstOrDefault(s => Util.Str(s, "id") == id);

    public static int ClientCount(JsonNode? s, string? type = null) =>
        (s?["clients"] as JsonArray)?.Count(c => type == null || Util.Str(c, "type") == type) ?? -1;

    /// <summary>Polls a session until <paramref name="pred"/> holds.</summary>
    public async Task<JsonNode?> WaitSession(string id, Func<JsonNode, bool> pred, int ms)
    {
        var sw = System.Diagnostics.Stopwatch.StartNew();
        JsonNode? last = null;
        while (sw.ElapsedMilliseconds < ms)
        {
            last = await GetSession(id);
            if (last != null && pred(last)) return last;
            await Task.Delay(250);
        }
        return null;
    }

    /// <summary>Sends `exit` and waits for {"type":"exit"} or state=exited.</summary>
    public async Task<bool> ExitShell(WsClient ws, string id, int ms = 10000)
    {
        int ti = ws.TextCount;
        await ws.Send("exit\r");
        var sw = System.Diagnostics.Stopwatch.StartNew();
        while (sw.ElapsedMilliseconds < ms)
        {
            if (ws.TextsOfType("exit", ti).Count > 0) return true;
            var s = await GetSession(id);
            if (Util.Str(s, "state") == "exited") return true;
            await Task.Delay(250);
        }
        return false;
    }

    /// <summary>Spawns a real external console (CREATE_NEW_CONSOLE): cmd /k "cd /d dir &amp; echo marker".</summary>
    public int SpawnExternal(string dir, string marker)
    {
        var cmd = Environment.ExpandEnvironmentVariables(@"%SystemRoot%\System32\cmd.exe");
        int pid = Native.SpawnNewConsole($"\"{cmd}\" /k \"cd /d {dir} & echo {marker}\"", dir, Aux);
        ExtPids.Add(pid);
        return pid;
    }

    public async Task<JsonNode?> WaitInList(string id, int ms)
    {
        var sw = System.Diagnostics.Stopwatch.StartNew();
        while (sw.ElapsedMilliseconds < ms)
        {
            try
            {
                var hit = FindById(await ListSessions(), id);
                if (hit != null) return hit;
            }
            catch (InvalidOperationException) { }
            await Task.Delay(400);
        }
        return null;
    }
}
