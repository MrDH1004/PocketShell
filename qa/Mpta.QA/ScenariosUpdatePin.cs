using System.Diagnostics;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using static Mpta.QA.Util;

namespace Mpta.QA;

/// <summary>US-008 (self-update, DESIGN §11) and US-009 (PIN pairing + LAN discovery, DESIGN §12) scenarios.</summary>
internal static partial class Scenarios
{
    private static string FpShortOf(string fp)
    {
        var u = fp[..16].ToUpperInvariant();
        return $"{u[..4]}-{u[4..8]}-{u[8..12]}-{u[12..16]}";
    }

    // ================================================================ US-008 helpers

    /// <summary>A separate host installed into its own temp dir, pointed at a scenario-owned fake releases feed.</summary>
    private sealed class UpdCase : IAsyncDisposable
    {
        public required Ctx C { get; init; }
        public required HostRunner Host { get; init; }
        public required string Install { get; init; }
        public required string Home { get; init; }
        public required Api Api { get; init; }
        public string Token => Host.Token;

        public async Task<JsonNode?> Info() => (await Api.Get("/api/info", Token)) is { Ok: true } r ? r.Json : null;

        /// <summary>Polls GET /api/update (tolerating a briefly unreachable host) until <paramref name="pred"/> holds.</summary>
        public async Task<(JsonNode? Hit, JsonNode? Last, List<string> States)> WaitUpdate(Func<JsonNode, bool> pred, int ms)
        {
            var states = new List<string>();
            JsonNode? last = null;
            var sw = Stopwatch.StartNew();
            while (sw.ElapsedMilliseconds < ms)
            {
                var r = await Api.Get("/api/update", Token);
                if (r.Ok && r.Json != null)
                {
                    last = r.Json;
                    var st = Str(r.Json, "state") ?? "?";
                    if (states.Count == 0 || states[^1] != st) states.Add(st);
                    if (pred(r.Json)) return (r.Json, last, states);
                }
                else if (states.Count == 0 || states[^1] != "unreachable") states.Add("unreachable");
                await Task.Delay(250);
            }
            return (null, last, states);
        }

        public async ValueTask DisposeAsync()
        {
            Api.Dispose();
            Host.Kill();
            C.ExtraHosts.Remove(Host);
            await Task.CompletedTask;
        }
    }

    private static async Task<UpdCase> StartUpdCase(Ctx c, Ev ev, string name, string mode, FakeReleaseServer srv, int intervalSec)
    {
        var dir = Path.Combine(c.Work, $"{name}-{Ctx.R()}");
        var home = Path.Combine(dir, "home");
        Directory.CreateDirectory(home);
        var exe = UpdateFixture.Install(Path.GetDirectoryName(c.Exe)!, Path.Combine(dir, "install"));
        int port = FreePort();
        File.WriteAllText(Path.Combine(home, "config.json"), JsonSerializer.Serialize(new { port, autoUpdate = mode, discoveryPort = 0 }), new UTF8Encoding(false));
        var env = new Dictionary<string, string> { ["MPTA_UPDATE_URL"] = srv.FeedUrl, ["MPTA_UPDATE_INTERVAL_SEC"] = intervalSec.ToString() };
        var log = Path.Combine(c.OutDir, $"host-{name}.log");
        File.WriteAllText(log, "", Encoding.UTF8);
        var host = new HostRunner(exe, home, port, new[] { "127.0.0.1" }, log, env);
        c.ExtraHosts.Add(host);
        var err = await host.Start(30000);
        if (err != null) throw new InvalidOperationException($"{name}: host start failed: {err}");
        ev.Info($"[{name}] installed {c.VersionName} at {Path.GetDirectoryName(exe)}; serving on 127.0.0.1:{port} (autoUpdate={mode}, feed={srv.FeedUrl}, log=qa/out/host-{name}.log)");
        return new UpdCase { C = c, Host = host, Install = Path.GetDirectoryName(exe)!, Home = home, Api = new Api("127.0.0.1", port, host.Fp) };
    }

    private static string ProductVersion(string file) => FileVersionInfo.GetVersionInfo(file).ProductVersion ?? "";

    // ================================================================ US008-API (main host)
    public static async Task<Outcome> US008Api(Ctx c, Ev ev)
    {
        var info = await c.Local.Get("/api/info", c.Token);
        ev.Check(info.Ok && Str(info.Json, "version") == c.VersionName, $"/api/info.version={Str(info.Json, "version")} == version.properties versionName {c.VersionName}");
        var pv = ProductVersion(Path.Combine(Path.GetDirectoryName(c.Exe)!, "mpta.dll"));
        ev.Check(pv.Split('+')[0] == c.VersionName, $"mpta.dll InformationalVersion '{pv}' from version.properties");
        var upd = info.Json?["update"];
        ev.Check(upd != null && Str(upd, "current") == c.VersionName, $"/api/info.update present: {Compact(upd, 160)}");

        // Startup check against the local feed (lists only the current version) -> state none.
        JsonNode? st = null;
        var sw = Stopwatch.StartNew();
        while (sw.ElapsedMilliseconds < 10000)
        {
            st = (await c.Local.Get("/api/update", c.Token)).Json;
            if (Str(st, "state") == "none" && Str(st, "latest") == c.VersionName) break;
            await Task.Delay(300);
        }
        ev.Check(Str(st, "state") == "none" && Str(st, "latest") == c.VersionName && Str(st, "current") == c.VersionName,
            $"GET /api/update -> {Compact(st, 160)}");
        ev.Check(c.Releases != null && c.Releases.Hits("/feed") >= 1, $"host queried MPTA_UPDATE_URL feed ({c.Releases?.Hits("/feed")} request(s))");
        var unauth = await c.Local.Get("/api/update", null);
        ev.Check(unauth.Status == 401, $"GET /api/update without token -> {unauth.Status}");

        var (cc, co) = await Run(c.Exe, new[] { "update", "--check" }, c.Home, c.Host.Env, 30000);
        ev.Check(cc == 0 && co.Contains($"current: {c.VersionName}") && Regex.IsMatch(co, @"state:\s+none"), $"`mpta update --check` exit={cc}: {Trunc(co, 160)}");
        var (uc, uo) = await Run(c.Exe, new[] { "update" }, c.Home, c.Host.Env, 30000);
        ev.Check(uc == 0 && uo.Contains("already up to date"), $"`mpta update` with nothing newer exit={uc}: {Trunc(uo, 120)}");
        var ap = await c.Local.Post("/api/update/apply", c.Token, new { force = false });
        ev.Check(ap.Status == 400, $"POST /api/update/apply with no update -> {ap}");
        return ev.Result();
    }

    // ================================================================ US008-UPDATE
    public static async Task<Outcome> US008Update(Ctx c, Ev ev)
    {
        var fx = await UpdateFixture.Ensure(c, ev);
        using var srv = new FakeReleaseServer();
        var asset = UpdateFixture.AssetName(fx.Version);
        var digest = "sha256:" + UpdateFixture.Sha256(fx.GoodZip);
        srv.SetFile(asset, fx.GoodZip);
        srv.SetReleases(srv.Release(c.VersionName, false, new[] { (UpdateFixture.AssetName(c.VersionName), (string?)null) }));
        await using var u = await StartUpdCase(c, ev, "upd", "auto", srv, 2);

        var info0 = await u.Info();
        ev.Check(Str(info0, "version") == c.VersionName, $"installed host reports {Str(info0, "version")}");
        var before = UpdateFixture.Snapshot(u.Install);

        var sess = await u.Api.Post("/api/sessions", u.Token, new { shell = c.Shell, title = "qa-us008-busy" });
        var sid = Str(sess.Json, "id");
        if (!ev.Check(sess.Ok && sid != null, $"session created on update host -> {sess.Status} {sid}")) return Outcome.FAIL;

        // Publish the newer release (+ a higher draft that must be ignored).
        srv.SetFile(UpdateFixture.AssetName("99.0.0"), fx.GoodZip);
        srv.SetReleases(
            srv.Release("99.0.0", true, new[] { (UpdateFixture.AssetName("99.0.0"), (string?)digest) }),
            srv.Release(fx.Version, false, new[] { (asset, (string?)digest) }),
            srv.Release(c.VersionName, false, new[] { (UpdateFixture.AssetName(c.VersionName), (string?)null) }));
        var (ready, last, states) = await u.WaitUpdate(j => Str(j, "state") == "ready", 40000);
        ev.Check(ready != null && Str(ready, "latest") == fx.Version, $"periodic check found + staged {fx.Version}: states {string.Join("->", states)}; last={Compact(last, 200)}");
        ev.Check(Str(last, "latest") != "99.0.0" && srv.Hits("/dl/" + Uri.EscapeDataString(UpdateFixture.AssetName("99.0.0"))) == 0, "draft release 99.0.0 ignored");
        ev.Check(srv.Hits("/dl/" + Uri.EscapeDataString(asset)) >= 1, $"downloaded {asset} ({srv.Hits("/dl/" + Uri.EscapeDataString(asset))}x)");
        ev.Check(File.Exists(Path.Combine(u.Home, "updates", fx.Version, "app", "mpta.exe")), $"staged at MPTA_HOME/updates/{fx.Version}/app");

        var ap = await u.Api.Post("/api/update/apply", u.Token, new { });
        ev.Check(ap.Status == 409 && Int(ap.Json, "runningSessions") == 1, $"apply without force while 1 session runs -> {ap}");
        await Task.Delay(5000);
        var mid = await u.Info();
        ev.Check(Str(mid, "version") == c.VersionName && Str(mid?["update"], "state") == "ready" && u.Host.Proc is { HasExited: false },
            $"auto mode waits while a session runs (5s later version={Str(mid, "version")}, state={Str(mid?["update"], "state")})");
        ev.Check(UpdateFixture.SameTree(before, UpdateFixture.Snapshot(u.Install)), "install dir untouched before apply");

        var del = await u.Api.Delete($"/api/sessions/{sid}", u.Token);
        ev.Check(del.Status == 204, $"closed the session -> {del.Status}");
        int oldPid = u.Host.Proc?.Id ?? -1;
        var sw = Stopwatch.StartNew();
        string? ver = null;
        long firstDown = -1, backAt = -1;
        while (sw.ElapsedMilliseconds < 60000)
        {
            var r = await u.Api.Get("/api/info", u.Token);
            if (!r.Ok && firstDown < 0) firstDown = sw.ElapsedMilliseconds;
            ver = Str(r.Json, "version") ?? ver;
            if (r.Ok && ver == fx.Version) { backAt = sw.ElapsedMilliseconds; break; }
            await Task.Delay(200);
        }
        ev.Check(backAt >= 0, $"idle auto-apply: same port 127.0.0.1:{u.Host.Port} now reports {ver} after {backAt}ms (unreachable from {firstDown}ms), pinned cert unchanged");
        bool oldExited = u.Host.Proc?.WaitForExit(15000) ?? false;
        ev.Check(oldExited && u.Host.Proc!.ExitCode == 0, $"old process pid {oldPid} exited (exit={(oldExited ? u.Host.Proc!.ExitCode.ToString() : "still running")})");
        var pv = ProductVersion(Path.Combine(u.Install, "mpta.dll"));
        ev.Check(pv.Split('+')[0] == fx.Version, $"install dir mpta.dll is now {pv}");
        var olds = UpdateFixture.OldFiles(u.Install);
        var cleanSw = Stopwatch.StartNew();
        while (olds.Count > 0 && cleanSw.ElapsedMilliseconds < 20000)
        {
            await Task.Delay(500);
            olds = UpdateFixture.OldFiles(u.Install);
        }
        ev.Check(olds.Count == 0, $"*.old removed by the new process after the old one exited ({cleanSw.ElapsedMilliseconds}ms){(olds.Count > 0 ? ": " + string.Join(",", olds.Take(5)) : "")}");
        var after = await u.WaitUpdate(j => Str(j, "state") == "none", 10000);
        ev.Check(after.Hit != null && Str(after.Hit, "current") == fx.Version, $"new host update state: {Compact(after.Last, 160)}");
        var s2 = await u.Api.Post("/api/sessions", u.Token, new { shell = c.Shell, title = "qa-us008-after" });
        ev.Check(s2.Ok, $"new host creates sessions -> {s2.Status}");
        if (s2.Ok) await u.Api.Delete($"/api/sessions/{Str(s2.Json, "id")}", u.Token);

        // Following start: plant a leftover *.old (as if the old exe had still been locked) and restart from the install dir.
        var planted = Path.Combine(u.Install, "mpta.dll.old");
        File.Copy(Path.Combine(u.Install, "mpta.dll"), planted, true);
        u.Host.Kill();
        var err = await u.Host.Start(30000);
        ev.Check(err == null, "restart from install dir" + (err == null ? "" : ": " + err));
        var info2 = await u.Info();
        ev.Check(Str(info2, "version") == fx.Version, $"after restart version={Str(info2, "version")}");
        ev.Check(!File.Exists(planted) && UpdateFixture.OldFiles(u.Install).Count == 0, "leftover *.old deleted at the following start");
        ev.Check(!Directory.Exists(Path.Combine(u.Home, "updates", fx.Version)), "applied staging folder removed at start");
        return ev.Result();
    }

    // ================================================================ US008-REFUSE
    public static async Task<Outcome> US008Refuse(Ctx c, Ev ev)
    {
        var fx = await UpdateFixture.Ensure(c, ev);
        using var srv = new FakeReleaseServer();
        var asset = UpdateFixture.AssetName(fx.Version);
        srv.SetFile(asset, fx.GoodZip);
        var wrong = "sha256:" + UpdateFixture.Sha256(fx.GoodZip.Append((byte)0).ToArray());
        srv.SetReleases(srv.Release(fx.Version, false, new[] { (asset, (string?)wrong) }));
        await using var u = await StartUpdCase(c, ev, "refuse", "auto", srv, 3600);
        var before = UpdateFixture.Snapshot(u.Install);

        var (err, last, states) = await u.WaitUpdate(j => Str(j, "state") == "error", 30000);
        ev.Check(err != null && (Str(err, "error") ?? "").Contains("sha256 mismatch"), $"wrong digest -> state=error: {Compact(last, 260)} (states {string.Join("->", states)})");
        ev.Check(srv.Hits("/dl/" + Uri.EscapeDataString(asset)) >= 1, "asset was downloaded and then rejected");
        ev.Check(!Directory.Exists(Path.Combine(u.Home, "updates", fx.Version, "app")), "nothing staged for the rejected build");
        var ap = await u.Api.Post("/api/update/apply", u.Token, new { force = true });
        ev.Check(ap.Status == 200, $"forced apply request -> {ap.Status}");
        await Task.Delay(3000);
        var st = (await u.Api.Get("/api/update", u.Token)).Json;
        var info = await u.Info();
        ev.Check(Str(st, "state") == "error" && Str(info, "version") == c.VersionName && u.Host.Proc is { HasExited: false },
            $"forced apply still refused: state={Str(st, "state")} version={Str(info, "version")} old process alive={u.Host.Proc is { HasExited: false }}");

        // No digest and no SHA256SUMS.txt -> refuse without installing.
        srv.SetReleases(srv.Release(fx.Version, false, new[] { (asset, (string?)null) }));
        var chk = await u.Api.Post("/api/update/check", u.Token, new { });
        ev.Check(Str(chk.Json, "state") == "error" && (Str(chk.Json, "error") ?? "").Contains("no sha256 digest"), $"no digest/SHA256SUMS -> {Compact(chk.Json, 220)}");
        ev.Check(UpdateFixture.SameTree(before, UpdateFixture.Snapshot(u.Install)) && UpdateFixture.OldFiles(u.Install).Count == 0, "install dir byte-identical, no *.old");
        return ev.Result();
    }

    // ================================================================ US008-ROLLBACK
    public static async Task<Outcome> US008Rollback(Ctx c, Ev ev)
    {
        var fx = await UpdateFixture.Ensure(c, ev);
        using var srv = new FakeReleaseServer();
        var asset = UpdateFixture.AssetName(fx.Version);
        srv.SetFile(asset, fx.BrokenZip);
        // No digest field: integrity comes from SHA256SUMS.txt (DESIGN §11.2 fallback).
        srv.SetFile("SHA256SUMS.txt", Encoding.UTF8.GetBytes($"{UpdateFixture.Sha256(fx.BrokenZip)}  {asset}\n"));
        srv.SetReleases(srv.Release(fx.Version, false, new[] { (asset, (string?)null), ("SHA256SUMS.txt", (string?)null) }));
        await using var u = await StartUpdCase(c, ev, "rollback", "notify", srv, 3600);
        var before = UpdateFixture.Snapshot(u.Install);

        var chk = await u.Api.Post("/api/update/check", u.Token, new { });
        ev.Check(Str(chk.Json, "state") == "available" && Str(chk.Json, "latest") == fx.Version, $"notify mode: check -> {Compact(chk.Json, 160)}");
        ev.Check(srv.Hits("/dl/" + Uri.EscapeDataString(asset)) == 0, "notify mode does not download before apply");
        int oldPid = u.Host.Proc?.Id ?? -1;

        // `mpta update` triggers the apply and follows the handoff; here it must report the rollback and exit 1.
        var (uc, uo) = await Run(u.Host.Exe, new[] { "update" }, u.Home, u.Host.Env, 100000);
        ev.Check(uc == 1 && uo.Contains("applying update") && uo.Contains("rolled back"), $"`mpta update` exit={uc}: {Trunc(uo, 260)}");
        var (err, last, states) = await u.WaitUpdate(j => Str(j, "state") == "error", 60000);
        ev.Check(err != null && (Str(err, "error") ?? "").Contains("rolled back"), $"new build failed -> rollback: {Compact(last, 300)} (states {string.Join("->", states)})");
        ev.Check(srv.Hits("/dl/SHA256SUMS.txt") >= 1, "SHA256SUMS.txt used for verification");
        var info = await u.Info();
        ev.Check(Str(info, "version") == c.VersionName && u.Host.Proc is { HasExited: false } && u.Host.Proc.Id == oldPid,
            $"old process pid {oldPid} still serving {Str(info, "version")} on the same port");
        ev.Check(UpdateFixture.SameTree(before, UpdateFixture.Snapshot(u.Install)) && UpdateFixture.OldFiles(u.Install).Count == 0,
            "install dir restored byte-identical, no *.old left");
        var s = await u.Api.Post("/api/sessions", u.Token, new { shell = c.Shell, title = "qa-us008-rb" });
        ev.Check(s.Ok, $"re-opened listeners serve requests (create session -> {s.Status})");
        if (s.Ok) await u.Api.Delete($"/api/sessions/{Str(s.Json, "id")}", u.Token);
        return ev.Result();
    }

    // ================================================================ US008-ROLLBACK-LATE
    /// <summary>
    /// The new build starts fully (managed code runs, startup cleanup runs) but reports a version other than the release
    /// tag, so the old process rolls back after the handoff check. The rollback must still restore every file.
    /// </summary>
    public static async Task<Outcome> US008RollbackLate(Ctx c, Ev ev)
    {
        var fx = await UpdateFixture.Ensure(c, ev);
        // Tag a higher version but ship the working fx.Version build: the new process serves, then fails the version check.
        var tag = UpdateFixture.NextVersion(fx.Version);
        using var srv = new FakeReleaseServer();
        var asset = UpdateFixture.AssetName(tag);
        srv.SetFile(asset, fx.GoodZip);
        srv.SetReleases(srv.Release(tag, false, new[] { (asset, (string?)("sha256:" + UpdateFixture.Sha256(fx.GoodZip))) }));
        await using var u = await StartUpdCase(c, ev, "rollbacklate", "notify", srv, 3600);
        var userOld = Path.Combine(u.Install, "notes.txt.old"); // unrelated user file: not ours to delete
        File.WriteAllText(Path.Combine(u.Install, "notes.txt"), "user file");
        File.WriteAllText(userOld, "user backup");
        var before = UpdateFixture.Snapshot(u.Install);

        var chk = await u.Api.Post("/api/update/check", u.Token, new { });
        ev.Check(Str(chk.Json, "state") == "available" && Str(chk.Json, "latest") == tag, $"check -> {Compact(chk.Json, 160)}");
        int oldPid = u.Host.Proc?.Id ?? -1;
        var ap = await u.Api.Post("/api/update/apply", u.Token, new { });
        ev.Check(ap.Ok, $"apply -> {ap.Status}");
        var (err, last, states) = await u.WaitUpdate(j => Str(j, "state") == "error", 90000);
        var msg = Str(err, "error") ?? "";
        ev.Check(err != null && msg.Contains("rolled back") && msg.Contains($"reports version '{fx.Version}'"),
            $"new process served {fx.Version} != tag {tag} -> rollback: {Compact(last, 400)} (states {string.Join("->", states)})");
        ev.Check(!msg.Contains("restore problems"), "rollback reported no restore problems");
        var info = await u.Info();
        ev.Check(Str(info, "version") == c.VersionName && u.Host.Proc is { HasExited: false } && u.Host.Proc.Id == oldPid,
            $"old process pid {oldPid} still serving {Str(info, "version")}");
        var now = UpdateFixture.Snapshot(u.Install);
        var missing = before.Keys.Where(k => !now.ContainsKey(k)).ToList();
        var changed = before.Keys.Where(k => now.TryGetValue(k, out var h) && h != before[k]).ToList();
        var extra = now.Keys.Where(k => !before.ContainsKey(k)).ToList();
        ev.Check(UpdateFixture.SameTree(before, now),
            $"install dir byte-identical (missing {missing.Count}: {string.Join(",", missing.Take(8))}; changed {changed.Count}: {string.Join(",", changed.Take(8))}; extra {extra.Count}: {string.Join(",", extra.Take(8))})");
        var olds = UpdateFixture.OldFiles(u.Install).Where(f => !string.Equals(f, "notes.txt.old", StringComparison.OrdinalIgnoreCase)).ToList();
        ev.Check(olds.Count == 0 && File.Exists(userOld), $"no update *.old left ({string.Join(",", olds.Take(8))}), user notes.txt.old kept={File.Exists(userOld)}");

        // A fresh `mpta serve` from the install dir must still start (no vanished runtimeconfig/deps/dlls).
        u.Host.Kill();
        var startErr = await u.Host.Start(30000);
        var info2 = startErr == null ? await u.Info() : null;
        ev.Check(startErr == null && Str(info2, "version") == c.VersionName,
            $"fresh start from install dir -> {(startErr ?? "ok")}, version={Str(info2, "version")}");
        ev.Check(File.Exists(userOld), "user notes.txt.old survives the following start's cleanup");
        return ev.Result();
    }

    // ================================================================ US009-PIN
    public static async Task<Outcome> US009Pin(Ctx c, Ev ev)
    {
        var fpShort = FpShortOf(c.Fp);
        var ps = await c.Local.Post("/api/pair/start", c.Token, new { mode = "pin" });
        var pin = Str(ps.Json, "pin") ?? "";
        ev.Check(ps.Ok && Regex.IsMatch(pin, "^[0-9]{6}$"), $"pair/start mode=pin -> {ps.Status} pin={(pin.Length == 6 ? "6 digits" : pin)}");
        ev.Check(Str(ps.Json, "fpShort") == fpShort, $"fpShort {Str(ps.Json, "fpShort")} == SHA-256 first 8 bytes {fpShort}");
        ev.Check(Int(ps.Json, "port") == c.Port && ps.Json?["hosts"] is JsonArray, $"port/hosts: {Compact(ps.Json?["hosts"], 80)} port={Int(ps.Json, "port")}");
        var exp = DateTimeOffset.TryParse(Str(ps.Json, "expiresAt"), out var e) ? e : DateTimeOffset.MinValue;
        var left = exp - DateTimeOffset.UtcNow;
        ev.Check(left > TimeSpan.FromMinutes(4.5) && left < TimeSpan.FromMinutes(5.5), $"expiresAt ~5 min ahead ({left.TotalSeconds:0}s)");
        // Re-issue: a new pair/start revokes the previous PIN.
        var ps2 = await c.Local.Post("/api/pair/start", c.Token, new { mode = "pin" });
        var pin2 = Str(ps2.Json, "pin") ?? "";
        if (pin2 != pin)
        {
            var old = await c.Local.Post("/api/pair/claim", null, new { pin, deviceName = "qa-pin-old" });
            ev.Check(old.Status == 401, $"previous PIN revoked by a new pair/start -> {old.Status}");
        }
        else ev.Info("new PIN equal to previous by chance; revocation check skipped this run");
        var wrongPin = pin2 == "000000" ? "111111" : "000000";
        var codes = new List<int>();
        for (int i = 0; i < 5; i++) codes.Add((await c.Local.Post("/api/pair/claim", null, new { pin = wrongPin, deviceName = "qa-pin-bad" })).Status);
        ev.Check(codes.All(x => x == 401), $"5 wrong PIN claims -> {string.Join(",", codes)}");
        var dead = await c.Local.Post("/api/pair/claim", null, new { pin = pin2, deviceName = "qa-pin-dead" });
        ev.Check(dead.Status == 401, $"correct PIN after 5 failures is dead -> {dead.Status}");

        var ps3 = await c.Local.Post("/api/pair/start", c.Token, new { mode = "pin" });
        var pin3 = Str(ps3.Json, "pin") ?? "";
        var name = "qa-pin-" + Ctx.R();
        var cl = await c.Local.Post("/api/pair/claim", null, new { pin = pin3, deviceName = name });
        var tok = Str(cl.Json, "token");
        var devId = Str(cl.Json, "deviceId");
        ev.Check(cl.Ok && tok != null && devId != null && string.Equals(Str(cl.Json, "fp"), c.Fp, StringComparison.OrdinalIgnoreCase),
            $"new PIN claim -> {cl.Status} deviceId={devId} fp matches={string.Equals(Str(cl.Json, "fp"), c.Fp, StringComparison.OrdinalIgnoreCase)}");
        if (tok != null)
        {
            var api = c.LanApi ?? c.Local;
            var r = await api.Get("/api/sessions", tok);
            ev.Check(r.Ok, $"PIN-paired device token authenticates via {api.Host} -> {r.Status}");
            var ds = await c.Local.Get("/api/devices", c.Token);
            ev.Check(ds.Ok && ds.Body.Contains(devId!) && ds.Body.Contains(name), $"device {devId} listed in /api/devices");
            var lp = await c.Local.Post("/api/pair/start", tok, new { mode = "pin" });
            ev.Check(lp.Status == 401, $"pair/start with a device token -> {lp.Status}");
        }
        var reuse = await c.Local.Post("/api/pair/claim", null, new { pin = pin3, deviceName = name + "-again" });
        ev.Check(reuse.Status == 401, $"PIN is one-time (reuse -> {reuse.Status})");
        return ev.Result();
    }

    // ================================================================ US009-CLI
    public static async Task<Outcome> US009Cli(Ctx c, Ev ev)
    {
        var fpShort = FpShortOf(c.Fp);
        var (code, output) = await Run(c.Exe, new[] { "pair", "--pin" }, c.Home, c.Host.Env, 20000);
        var m = Regex.Match(output, @"PIN:\s+([0-9]{6})\b");
        ev.Check(code == 0 && m.Success, $"`mpta pair --pin` exit={code}, PIN line found={m.Success}");
        ev.Check(output.Contains(fpShort), $"output shows fpShort {fpShort}");
        // 5 consecutive lines made only of '#'/spaces (block font rows) — independent of which digits the PIN has.
        ev.Check(Regex.IsMatch(output, @"(^[ #]*#[ #]*\r?\n){4}^[ #]*#[ #]*\r?$", RegexOptions.Multiline), "PIN rendered large (block digits)");
        ev.Info("output: " + Trunc(Regex.Replace(output, "[# ]{3,}", " "), 240));
        if (m.Success)
        {
            var cl = await c.Local.Post("/api/pair/claim", null, new { pin = m.Groups[1].Value, deviceName = "qa-pin-cli-" + Ctx.R() });
            ev.Check(cl.Ok && Str(cl.Json, "token") != null, $"printed PIN is the live one: claim -> {cl.Status}");
        }
        return ev.Result();
    }

    // ================================================================ US009-DISC
    public static async Task<Outcome> US009Discovery(Ctx c, Ev ev)
    {
        var fpShort = FpShortOf(c.Fp);
        int dport = c.DiscoveryPort;
        var targets = new List<string> { "127.0.0.1" };
        if (c.Lan != null) targets.Add(c.Lan);
        foreach (var t in targets)
        {
            var reply = await UdpAsk(t, dport, "MPTA?1", 2000);
            JsonNode? j = null;
            try { j = reply != null ? JsonNode.Parse(reply) : null; }
            catch (JsonException) { j = null; }
            ev.Check(j != null, $"reply from {t}:{dport}: {Trunc(reply, 200)}");
            if (j == null) continue;
            var keys = (j as JsonObject)?.Select(kv => kv.Key).OrderBy(k => k, StringComparer.Ordinal).ToList() ?? new();
            ev.Check(string.Join(",", keys) == "fpShort,hosts,name,port,v", $"fields exactly v,name,hosts,port,fpShort (got {string.Join(",", keys)})");
            ev.Check(Int(j, "v") == 1 && Int(j, "port") == c.Port && Str(j, "fpShort") == fpShort && (Str(j, "name") ?? "").Length > 0,
                $"v=1 port={Int(j, "port")} fpShort={Str(j, "fpShort")}");
            if (c.Lan != null) ev.Check((j["hosts"] as JsonArray)?.Any(h => h?.ToString() == c.Lan) == true, $"hosts include LAN {c.Lan}");
            ev.Check(!reply!.Contains(c.Token) && !reply.Contains("token", StringComparison.OrdinalIgnoreCase) && !reply.Contains(c.Fp, StringComparison.OrdinalIgnoreCase),
                "no token / full fingerprint in reply");
        }
        var junk = await UdpAsk("127.0.0.1", dport, "HELLO", 1000);
        ev.Check(junk == null, "non-MPTA?1 datagram gets no reply");
        // A wildcard socket would also answer on other loopback addresses; per-address binds do not.
        var wild = await UdpAsk("127.0.0.2", dport, "MPTA?1", 1000);
        ev.Check(wild == null, "no reply on unbound 127.0.0.2 (no 0.0.0.0 bind)");
        if (c.Tailscale != null)
        {
            var tsr = await UdpAsk(c.Tailscale, dport, "MPTA?1", 1000);
            ev.Check(tsr == null, $"no discovery socket on Tailscale CGNAT {c.Tailscale}");
        }
        var (nc, nout) = await Run("netstat.exe", new[] { "-ano", "-p", "UDP" }, null, null, 20000);
        var eps = nout.Split('\n').Select(l => l.Split(' ', StringSplitOptions.RemoveEmptyEntries))
            .Where(cols => cols.Length >= 2 && cols[0] == "UDP" && IPEndPoint.TryParse(cols[1], out var ep) && ep.Port == dport)
            .Select(cols => IPEndPoint.Parse(cols[1])).ToList();
        ev.Info($"netstat(exit {nc}) UDP sockets on {dport}: {string.Join(", ", eps)}");
        ev.Check(eps.All(ep => targets.Contains(ep.Address.ToString())), "UDP table: discovery bound only to loopback/private LAN addresses");

        if (c.Lan != null)
        {
            // Extra (informational): limited broadcast as the phone sends it. Delivery of a host's own broadcast back to
            // itself depends on the NIC/driver, so this is evidence only.
            var b = await UdpAsk("255.255.255.255", dport, "MPTA?1", 1500, bindTo: c.Lan);
            ev.Info($"broadcast 255.255.255.255 from {c.Lan}: {(b != null ? "reply received" : "no reply (own broadcast not looped back)")}");
        }
        return ev.Result();
    }

    private static async Task<string?> UdpAsk(string host, int port, string msg, int timeoutMs, string? bindTo = null)
    {
        var target = IPAddress.Parse(host);
        IPAddress local = bindTo != null ? IPAddress.Parse(bindTo) : IPAddress.IsLoopback(target) ? IPAddress.Loopback : target;
        using var udp = new UdpClient(new IPEndPoint(local, 0));
        if (bindTo != null) udp.EnableBroadcast = true;
        try { await udp.SendAsync(Encoding.ASCII.GetBytes(msg), new IPEndPoint(target, port)); }
        catch (SocketException) { return null; }
        using var cts = new CancellationTokenSource(timeoutMs);
        try
        {
            var r = await udp.ReceiveAsync(cts.Token);
            return Encoding.UTF8.GetString(r.Buffer);
        }
        catch (Exception e) when (e is OperationCanceledException or SocketException) { return null; }
    }

    // ================================================================ US009-429
    public static async Task<Outcome> US009RateLimit(Ctx c, Ev ev)
    {
        // Prefer the LAN address so the loopback client (used by other scenarios) keeps its own budget.
        var api = c.LanApi ?? c.Local;
        ev.Info($"claims sent via {api.Host} (remote IP seen by the host = {api.Host})");
        var ps = await c.Local.Post("/api/pair/start", c.Token, new { mode = "pin" });
        var pin = Str(ps.Json, "pin") ?? "";
        var wrong = pin == "000000" ? "111111" : "000000";
        var statuses = new List<int>();
        for (int i = 0; i < 12 && !statuses.Contains(429); i++)
            statuses.Add((await api.Post("/api/pair/claim", null, new { pin = wrong, deviceName = "qa-429" })).Status);
        int first429 = statuses.IndexOf(429);
        ev.Info($"statuses: {string.Join(",", statuses)}");
        if (c.LanApi != null)
            ev.Check(first429 == 10 && statuses.Take(10).All(s => s == 401), $"10 failures -> 401, 11th claim -> 429 (first 429 at #{first429 + 1})");
        else
            ev.Check(first429 > 0 && statuses.Take(first429).All(s => s == 401), $"429 after the per-IP failure budget (first 429 at #{first429 + 1})");
        var ps2 = await c.Local.Post("/api/pair/start", c.Token, new { mode = "pin" });
        var good = await api.Post("/api/pair/claim", null, new { pin = Str(ps2.Json, "pin"), deviceName = "qa-429-good" });
        ev.Check(good.Status == 429, $"even a valid PIN is refused from the blocked IP -> {good.Status}");
        if (c.LanApi != null)
        {
            var other = await c.Local.Post("/api/pair/claim", null, new { pin = Str(ps2.Json, "pin"), deviceName = "qa-429-loopback" });
            ev.Check(other.Ok, $"limit is per IP: same valid PIN from 127.0.0.1 -> {other.Status}");
        }
        else ev.Info("no LAN address: per-IP isolation not observable");
        var log = File.Exists(Path.Combine(c.Home, "logs", "host.log"))
            ? ReadShared(Path.Combine(c.Home, "logs", "host.log")) : "";
        ev.Check(log.Contains("pairing claim failed from") && log.Contains("(429)"), "failures and 429 are logged in host.log");
        return ev.Result();
    }

    private static string ReadShared(string path)
    {
        using var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
        using var sr = new StreamReader(fs, Encoding.UTF8);
        return sr.ReadToEnd();
    }
}
