using System.Diagnostics;
using System.Text;
using System.Text.Json;
using Mpta.QA;

// E2E QA driver for mpta (docs/DESIGN.md §9).
// Usage: dotnet run --project qa/Mpta.QA -c Release [-- --host-exe <path>] [--only AC-1,AC-7,...] [--no-build]
//        [--port N] [--cap-seconds 900] [--gradle-timeout-seconds 900]

Console.OutputEncoding = Encoding.UTF8;

string? hostExe = null;
string? only = null;
bool noBuild = false;
int? fixedPort = null;
int capSeconds = 900;
int gradleTimeout = 900;
for (int i = 0; i < args.Length; i++)
{
    string Next() => i + 1 < args.Length ? args[++i] : throw new ArgumentException($"missing value for {args[i]}");
    switch (args[i])
    {
        case "--host-exe": hostExe = Next(); break;
        case "--only": only = Next(); break;
        case "--no-build": noBuild = true; break;
        case "--port": fixedPort = int.Parse(Next()); break;
        case "--cap-seconds": capSeconds = int.Parse(Next()); break;
        case "--gradle-timeout-seconds": gradleTimeout = int.Parse(Next()); break;
        case "-h":
        case "--help":
            Console.WriteLine("Mpta.QA [--host-exe <mpta.exe>] [--only AC-1,AC-7,ANDROID] [--no-build] [--port N] [--cap-seconds 900] [--gradle-timeout-seconds 900]");
            return 0;
        default:
            Console.Error.WriteLine($"unknown argument: {args[i]}");
            return 2;
    }
}

var root = FindRoot();
var outDir = Path.Combine(root, "qa", "out");
var runDir = Path.Combine(outDir, "run");
Directory.CreateDirectory(outDir);
try { if (Directory.Exists(runDir)) Directory.Delete(runDir, true); }
catch (IOException e) { Console.Error.WriteLine($"warning: could not clear {runDir}: {e.Message}"); }
catch (UnauthorizedAccessException e) { Console.Error.WriteLine($"warning: could not clear {runDir}: {e.Message}"); }
var home = Path.Combine(runDir, "home");
var work = Path.Combine(runDir, "work");
Directory.CreateDirectory(home);
Directory.CreateDirectory(work);
var hostLog = Path.Combine(outDir, "host.log");
File.WriteAllText(hostLog, "", Encoding.UTF8);

// Scenario table: id, title, timeout, needs host.
var hostScenarios = new List<(string Id, string Name, int TimeoutS, Func<Ctx, Ev, Task<Outcome>> Fn)>
{
    ("AC-7", "auth: 401s, pairing, revoke (4401), local-token loopback only", 45, Scenarios.AC7),
    ("AC-1", "session survives with 0 clients, reattach works", 40, Scenarios.AC1),
    ("AC-2", "2 clients receive byte-identical live output", 40, Scenarios.AC2),
    ("AC-3", "replay then live, no gap/dup at attach", 50, Scenarios.AC3),
    ("AC-5", "input from both clients reaches the shell", 40, Scenarios.AC5),
    ("AC-6", "PTY size = min of clients, restored on detach", 50, Scenarios.AC6),
    ("AC-8", "no wildcard bind, no plain http, TLS on LAN/TS, latency", 60, Scenarios.AC8),
    ("AC-16", "truecolor SGR passes through raw stream", 30, Scenarios.AC16),
    ("AC-17", "Korean UTF-8 in/out", 30, Scenarios.AC17),
    ("AC-20", "`mpta attach` renders + types into session", 45, Scenarios.AC20),
    ("AC-21", "android openPcTab opens one WT tab, no duplicate", 60, Scenarios.AC21),
    ("AC-21-FOCUS", "WT tab auto-open does not steal foreground focus", 60, Scenarios.AC21Focus),
    ("AC-22", "CLI list/devices/pair/new", 75, Scenarios.AC22),
    ("AC-12/13/14", "external console list/view/input/adopt", 60, Scenarios.AC12_14),
    ("AC-9", "list has host/external/history with fields", 45, Scenarios.AC9),
    ("AC-10", "resume history: replay + same cwd/shell", 50, Scenarios.AC10),
    ("AC-4/11", "host restart keeps history + output.vt", 70, Scenarios.AC4_11),
    ("US008-API", "version.properties -> /api/info.version, GET /api/update, `mpta update --check`", 40, Scenarios.US008Api),
    ("US009-CLI", "`mpta pair --pin` prints PIN + fpShort (PIN is claimable)", 40, Scenarios.US009Cli),
    ("US009-DISC", "UDP LAN discovery MPTA?1 reply, no secrets, private binds only", 30, Scenarios.US009Discovery),
    ("US009-PIN", "PIN pairing: rules, 5 wrong kill, new PIN claim -> token + fp", 45, Scenarios.US009Pin),
    ("US008-UPDATE", "self-update: verify, 409 w/ session, idle auto-apply -> new version same port, *.old cleanup", 330, Scenarios.US008Update),
    ("US008-REFUSE", "bad/missing sha256 -> update refused, install dir unchanged", 90, Scenarios.US008Refuse),
    ("US008-ROLLBACK", "new build fails to start -> rollback, old version keeps serving", 120, Scenarios.US008Rollback),
    ("US008-ROLLBACK-LATE", "new build serves but fails the version check -> full rollback, fresh start works", 150, Scenarios.US008RollbackLate),
    ("US009-429", "per-IP claim failures > 10 / 10 min -> 429", 45, Scenarios.US009RateLimit),
};
var selectedHost = hostScenarios.Where(s => Selected(s.Id)).ToList();
bool runSmoke = Selected("ANDROID-SMOKE");
bool runUnit = Selected("ANDROID-UNIT");

var results = new List<Result>();
var (lan, ts) = Util.DetectAddresses();
string shell = Util.FindOnPath("pwsh.exe") != null ? "pwsh" : "powershell";
HostRunner? host = null;
Ctx? ctx = null;
using var releases = new FakeReleaseServer();
var versionName = UpdateFixture.ReadVersionName(root);
// Main host: the feed only lists the current version, so no update is ever found (and no GitHub traffic).
releases.SetFile(UpdateFixture.AssetName(versionName), new byte[] { 1, 2, 3 });
releases.SetReleases(releases.Release(versionName, false, new[] { (UpdateFixture.AssetName(versionName), (string?)("sha256:" + UpdateFixture.Sha256(new byte[] { 1, 2, 3 }))) }));
int discoveryPort = Util.FreeUdpPort();
using var aux = new Job();
string headerExe = "(not used)";
int port = fixedPort ?? Util.FreePort();
var binds = new List<string> { "127.0.0.1" };
if (lan != null) binds.Add(lan);
if (ts != null) binds.Add(ts);

int cleanedUp = 0;
async Task Cleanup()
{
    if (Interlocked.Exchange(ref cleanedUp, 1) == 1) return;
    if (ctx != null)
    {
        try
        {
            foreach (var id in ctx.CreatedSessions.ToList()) await ctx.Local.Delete($"/api/sessions/{id}", ctx.Token);
        }
        catch (ObjectDisposedException) { }
        foreach (var pid in ctx.ExtPids) if (Util.PidAlive(pid)) Util.KillPid(pid);
        try { await Util.KillAttachProcesses(ctx.AttachIds); }
        catch (Exception e) when (e is IOException or InvalidOperationException) { Console.Error.WriteLine("attach cleanup: " + e.Message); }
    }
    if (ctx != null) foreach (var h in ctx.ExtraHosts.ToList()) h.Kill();
    host?.Kill();
    aux.Dispose();
}
Console.CancelKeyPress += (_, e) =>
{
    e.Cancel = true;
    Console.Error.WriteLine("Ctrl+C: cleaning up...");
    Cleanup().Wait(20000);
    Environment.Exit(130);
};

var deadline = DateTime.UtcNow.AddSeconds(capSeconds);
try
{
    if (selectedHost.Count > 0)
    {
        var sw = Stopwatch.StartNew();
        string? setupErr = null;
        if (hostExe == null)
        {
            if (noBuild)
            {
                var bin = Path.Combine(root, "host", "src", "Mpta", "bin", "Release");
                hostExe = Directory.Exists(bin) ? Directory.GetFiles(bin, "mpta.exe", SearchOption.AllDirectories).OrderByDescending(File.GetLastWriteTimeUtc).FirstOrDefault() : null;
                if (hostExe == null) setupErr = $"--no-build: no mpta.exe under {bin}";
            }
            else
            {
                Console.WriteLine("building host (dotnet build host/src/Mpta -c Release)...");
                var (exe, detail) = await HostRunner.BuildDefault(root, outDir);
                hostExe = exe;
                if (exe == null) setupErr = detail;
            }
        }
        else if (!File.Exists(hostExe)) setupErr = $"--host-exe not found: {hostExe}";

        if (setupErr == null)
        {
            hostExe = Path.GetFullPath(hostExe!);
            headerExe = hostExe;
            File.WriteAllText(Path.Combine(home, "config.json"), JsonSerializer.Serialize(new { port }), new UTF8Encoding(false));
            host = new HostRunner(hostExe, home, port, binds, hostLog,
                new Dictionary<string, string> { ["MPTA_UPDATE_URL"] = releases.FeedUrl },
                new[] { "--discovery-port", discoveryPort.ToString() });
            Console.WriteLine($"starting host: {hostExe} serve --port {port} {string.Join(" ", binds.Select(b => "--bind " + b))}");
            deadline = DateTime.UtcNow.AddSeconds(capSeconds); // cap covers scenarios, not the build
            var err = await host.Start(30000);
            if (err != null) setupErr = "host start: " + err;
        }

        if (setupErr != null)
        {
            results.Add(new Result("HOST-SETUP", "build/locate/start mpta serve", Outcome.FAIL, setupErr, sw.Elapsed));
            foreach (var s in selectedHost) results.Add(new Result(s.Id, s.Name, Outcome.SKIP, "host unavailable: " + setupErr, TimeSpan.Zero));
        }
        else
        {
            results.Add(new Result("HOST-SETUP", "build/locate/start mpta serve", Outcome.PASS,
                $"ready on 127.0.0.1:{port}; binds={string.Join(",", binds)}; fp={host!.Fp[..16]}...; info={Util.Compact(host.Info, 200)}", sw.Elapsed));
            ctx = new Ctx
            {
                Root = root, OutDir = outDir, Work = work, Host = host, Aux = aux, Lan = lan, Tailscale = ts, Shell = shell,
                VersionName = versionName, Releases = releases, DiscoveryPort = discoveryPort,
            };
            ctx.ResetApis();
            foreach (var s in selectedHost)
            {
                var r = await RunOne(s.Id, s.Name, s.TimeoutS, s.Fn, ctx);
                results.Add(r);
                PrintLine(r);
            }
        }
    }
}
finally
{
    await Cleanup();
}

if (runSmoke)
{
    var ev = new Ev();
    var sw = Stopwatch.StartNew();
    var r = await Guard("ANDROID-SMOKE", "adb install + launch com.mpta.client", ev, Scenarios.AndroidSmoke(root, ev), 300, sw);
    results.Add(r);
    PrintLine(r);
}
if (runUnit)
{
    var ev = new Ev();
    var sw = Stopwatch.StartNew();
    var r = await Guard("ANDROID-UNIT", "android gradlew test (JVM unit tests)", ev, Scenarios.AndroidUnit(root, outDir, gradleTimeout * 1000, ev), gradleTimeout + 30, sw);
    results.Add(r);
    PrintLine(r);
}

WriteReport();
PrintTable();
return results.Any(r => r.Outcome == Outcome.FAIL) ? 1 : 0;

// ------------------------------------------------------------------ helpers

bool Selected(string id)
{
    if (string.IsNullOrWhiteSpace(only)) return true;
    var wanted = only.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries).Select(x => x.ToUpperInvariant()).ToHashSet();
    var aliases = new HashSet<string> { id.ToUpperInvariant() };
    if (id.StartsWith("AC-"))
        foreach (var part in id[3..].Split('/'))
        {
            aliases.Add("AC-" + part);
            int dash = part.IndexOf('-');
            if (dash > 0) aliases.Add("AC-" + part[..dash]); // AC-21-FOCUS also runs with --only AC-21
        }
    if (id.StartsWith("ANDROID")) aliases.Add("ANDROID");
    if (id.StartsWith("US") && id.Length > 5) aliases.Add(id[..5].ToUpperInvariant()); // --only US008 runs all US008-*
    if (id.StartsWith("US") && id.Length > 6)
        for (int i = id.IndexOf('-', 6); i > 0; i = id.IndexOf('-', i + 1)) aliases.Add(id[..i].ToUpperInvariant()); // US008-ROLLBACK-LATE also runs with --only US008-ROLLBACK
    return aliases.Overlaps(wanted);
}

async Task<Result> RunOne(string id, string name, int timeoutS, Func<Ctx, Ev, Task<Outcome>> fn, Ctx c)
{
    var sw = Stopwatch.StartNew();
    var remaining = deadline - DateTime.UtcNow;
    if (remaining < TimeSpan.FromSeconds(5))
        return new Result(id, name, Outcome.SKIP, $"global time cap ({capSeconds}s) reached", TimeSpan.Zero);
    var ev = new Ev(); // owned here so evidence survives a scenario timeout
    var task = Task.Run(() => fn(c, ev));
    var limit = TimeSpan.FromSeconds(Math.Min(timeoutS, remaining.TotalSeconds));
    Console.WriteLine($"-- {id}: {name} (timeout {limit.TotalSeconds:0}s)");
    return await Guard(id, name, ev, task, (int)limit.TotalSeconds, sw);
}

async Task<Result> Guard(string id, string name, Ev ev, Task<Outcome> task, int timeoutS, Stopwatch sw)
{
    var done = await Task.WhenAny(task, Task.Delay(TimeSpan.FromSeconds(timeoutS)));
    if (done != task)
        return new Result(id, name, Outcome.FAIL, $"TIMEOUT after {timeoutS}s | {ev.Text}", sw.Elapsed);
    try
    {
        var o = await task;
        return new Result(id, name, o, ev.Text, sw.Elapsed);
    }
    catch (Exception e)
    {
        return new Result(id, name, Outcome.FAIL, $"EXCEPTION {e.GetType().Name}: {Util.AllMessages(e)} | {ev.Text}", sw.Elapsed);
    }
}

void PrintLine(Result r) => Console.WriteLine($"   {r.Outcome,-4} {r.Id} ({r.Duration.TotalSeconds:0.0}s)");

void PrintTable()
{
    Console.WriteLine();
    Console.WriteLine($"{"AC",-14} {"RESULT",-6} {"TIME",7}  EVIDENCE");
    Console.WriteLine(new string('-', 120));
    foreach (var r in results)
        Console.WriteLine($"{r.Id,-14} {r.Outcome,-6} {r.Duration.TotalSeconds,6:0.0}s  {Util.Trunc(r.Evidence, 90)}");
    Console.WriteLine(new string('-', 120));
    Console.WriteLine($"PASS={results.Count(r => r.Outcome == Outcome.PASS)} FAIL={results.Count(r => r.Outcome == Outcome.FAIL)} SKIP={results.Count(r => r.Outcome == Outcome.SKIP)}  report: {Path.Combine(outDir, "report.md")}");
}

void WriteReport()
{
    var sb = new StringBuilder();
    sb.AppendLine("# mpta E2E QA report");
    sb.AppendLine();
    sb.AppendLine($"- Run: {DateTime.Now:yyyy-MM-dd HH:mm:ss}");
    sb.AppendLine($"- Host exe: `{headerExe}`");
    sb.AppendLine($"- Port: {port}; binds: {string.Join(", ", binds)}; shell under test: {shell}");
    sb.AppendLine($"- MPTA_HOME: `{home}`; host log: `qa/out/host.log`");
    sb.AppendLine($"- Summary: PASS={results.Count(r => r.Outcome == Outcome.PASS)} FAIL={results.Count(r => r.Outcome == Outcome.FAIL)} SKIP={results.Count(r => r.Outcome == Outcome.SKIP)}");
    sb.AppendLine();
    sb.AppendLine("| AC | Result | Duration | Scenario | Evidence |");
    sb.AppendLine("|---|---|---|---|---|");
    foreach (var r in results)
    {
        var evid = r.Evidence.Length > 4000 ? r.Evidence[..4000] + "..." : r.Evidence;
        evid = evid.Replace("|", "\\|").Replace("\r", "").Replace("\n", "<br>").Replace(" \\| ", "<br>");
        sb.AppendLine($"| {r.Id} | **{r.Outcome}** | {r.Duration.TotalSeconds:0.0}s | {r.Name.Replace("|", "\\|")} | {evid} |");
    }
    File.WriteAllText(Path.Combine(outDir, "report.md"), sb.ToString(), new UTF8Encoding(false));
}

static string FindRoot()
{
    foreach (var start in new[] { AppContext.BaseDirectory, Directory.GetCurrentDirectory() })
    {
        for (var d = new DirectoryInfo(start); d != null; d = d.Parent)
            if (File.Exists(Path.Combine(d.FullName, "docs", "DESIGN.md"))) return d.FullName;
    }
    return Directory.GetCurrentDirectory();
}
