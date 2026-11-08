using System.Diagnostics;
using System.Net;
using System.Net.NetworkInformation;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using static Pkshell.QA.Util;

namespace Pkshell.QA;

/// <summary>One function per acceptance criterion (spec AC-x), written strictly against docs/DESIGN.md.</summary>
internal static partial class Scenarios
{
    // ---------------------------------------------------------------- AC-1
    public static async Task<Outcome> AC1(Ctx c, Ev ev)
    {
        var id = Ctx.Id(await c.NewSession(ev, "qa-ac1"));
        var m1 = "AC1A_" + Ctx.R();
        await using (var ws = await c.Open(id))
        {
            await Ctx.Ready(ws);
            await ws.Send(c.Expr(m1) + "\r");
            ev.Check(await ws.WaitContains(m1, 10000), $"marker {m1} printed while attached");
        }
        await Task.Delay(3000);
        var s = await c.GetSession(id);
        ev.Check(s != null, "GET /api/sessions/{id} after all clients left");
        ev.Check(Str(s, "state") == "running", $"state={Str(s, "state")} 3s after last client disconnected");
        ev.Check(Ctx.ClientCount(s) == 0, $"clients={Ctx.ClientCount(s)}");
        await using (var ws2 = await c.Open(id))
        {
            ev.Check(await ws2.WaitReplayEnd(5000), "reattach: replay-end received");
            long at = await ws2.WaitIndex(m1, 5000);
            ev.Check(at >= 0 && (ws2.ReplayEndOffset < 0 || at < ws2.ReplayEndOffset), $"reattach replay contains {m1} (offset {at}, replay-end {ws2.ReplayEndOffset})");
            await Ctx.Ready(ws2);
            var m2 = "AC1B_" + Ctx.R();
            await ws2.Send(c.Expr(m2) + "\r");
            ev.Check(await ws2.WaitContains(m2, 10000, Math.Max(0, ws2.ReplayEndOffset)), $"shell still interactive after reattach ({m2})");
        }
        return ev.Result();
    }

    // ---------------------------------------------------------------- AC-2
    public static async Task<Outcome> AC2(Ctx c, Ev ev)
    {
        var id = Ctx.Id(await c.NewSession(ev, "qa-ac2"));
        await using var a = await c.Open(id);
        await Ctx.Ready(a);
        await using var b = await c.Open(id);
        await b.WaitReplayEnd(5000);
        await a.WaitQuiet(1000, 8000);
        await b.WaitQuiet(1000, 8000);
        long oa = a.Length, ob = b.Length;
        var tag = "AC2" + Ctx.R();
        // Output: <tag>_1 .. <tag>_20 ; typed echo never contains "<tag>_<n>".
        await a.Send($"1..20 | ForEach-Object {{ '{tag}'+'_'+$_ }}\r");
        bool ga = await a.WaitContains(tag + "_20", 10000, oa);
        bool gb = await b.WaitContains(tag + "_20", 10000, ob);
        ev.Check(ga && gb, $"both clients saw {tag}_20 (A={ga}, B={gb})");
        await a.WaitQuiet(800, 5000);
        await b.WaitQuiet(800, 5000);
        var sa = a.Slice(oa);
        var sb = b.Slice(ob);
        var ha = Convert.ToHexString(SHA256.HashData(sa))[..16];
        var hb = Convert.ToHexString(SHA256.HashData(sb))[..16];
        ev.Info($"A live bytes={sa.Length} sha256={ha}; B live bytes={sb.Length} sha256={hb}");
        ev.Check(sa.Length > 0 && sa.AsSpan().SequenceEqual(sb), "live output byte-identical across 2 clients");
        var text = Encoding.UTF8.GetString(sa);
        bool all = Enumerable.Range(1, 20).All(n => text.Contains($"{tag}_{n}"));
        ev.Check(all, "known output lines 1..20 present");
        return ev.Result();
    }

    // ---------------------------------------------------------------- AC-3
    public static async Task<Outcome> AC3(Ctx c, Ev ev)
    {
        var id = Ctx.Id(await c.NewSession(ev, "qa-ac3"));
        await using var a = await c.Open(id);
        await Ctx.Ready(a);
        var early = "AC3EARLY_" + Ctx.R();
        await a.Send(c.Expr(early) + "\r");
        ev.Check(await a.WaitContains(early, 10000), $"early marker {early} printed");
        await a.WaitQuiet(500, 3000);
        const int n = 80;
        var tag = "Q" + Ctx.R();
        await a.Send($"for($i=0;$i -lt {n};$i++){{ '{tag}'+'_'+$i+'_E'; Start-Sleep -Milliseconds 40 }}\r");
        ev.Check(await a.WaitContains($"{tag}_10_E", 15000), "numbered loop started");
        // Attach B while markers are being emitted.
        await using var b = await c.Open(id);
        ev.Check(await b.WaitReplayEnd(8000), "B: replay-end received");
        bool aDone = await a.WaitContains($"{tag}_{n - 1}_E", 20000);
        bool bDone = await b.WaitContains($"{tag}_{n - 1}_E", 5000);
        ev.Check(aDone && bDone, $"loop finished on A={aDone} B={bDone}");
        await b.WaitQuiet(500, 3000);
        var bAll = b.Snapshot();
        long split = b.ReplayEndOffset;
        ev.Check(split >= 0 && bAll.AsSpan(0, (int)split).IndexOf(Encoding.UTF8.GetBytes(early)) >= 0, "B replay contains early marker");
        var rx = new Regex(Regex.Escape(tag) + @"_(\d+)_E");
        var seq = new List<(int N, int Off)>();
        foreach (Match m in rx.Matches(Encoding.Latin1.GetString(bAll))) seq.Add((int.Parse(m.Groups[1].Value), m.Index));
        var nums = seq.Select(x => x.N).ToList();
        bool exact = nums.SequenceEqual(Enumerable.Range(0, n));
        var replayNums = seq.Where(x => x.Off < split).Select(x => x.N).ToList();
        var liveNums = seq.Where(x => x.Off >= split).Select(x => x.N).ToList();
        ev.Info($"B saw {nums.Count} markers; replay part {Range(replayNums)}, live part {Range(liveNums)}");
        if (!exact)
        {
            var dups = nums.GroupBy(x => x).Where(g => g.Count() > 1).Select(g => g.Key).ToList();
            var missing = Enumerable.Range(0, n).Except(nums).ToList();
            ev.Info($"dups=[{string.Join(",", dups.Take(10))}] missing=[{string.Join(",", missing.Take(10))}] seq-head=[{string.Join(",", nums.Take(15))}]");
        }
        ev.Check(exact, $"B stream (replay+live) has markers 0..{n - 1} exactly once, in order");
        ev.Check(replayNums.Count > 0 && liveNums.Count > 0, "attach happened mid-loop (markers on both sides of replay-end)");
        return ev.Result();

        static string Range(List<int> v) => v.Count == 0 ? "none" : $"{v.Min()}..{v.Max()} ({v.Count})";
    }

    // ---------------------------------------------------------------- AC-4 / AC-11 (restart)
    public static async Task<Outcome> AC4_11(Ctx c, Ev ev)
    {
        var idRun = Ctx.Id(await c.NewSession(ev, "qa-ac4-running"));
        var idExit = Ctx.Id(await c.NewSession(ev, "qa-ac4-exited"));
        var mRun = "AC4RUN_" + Ctx.R();
        var mExit = "AC4EXIT_" + Ctx.R();
        await using (var w1 = await c.Open(idRun))
        {
            await Ctx.Ready(w1);
            await w1.Send(c.Expr(mRun) + "\r");
            ev.Check(await w1.WaitContains(mRun, 10000), $"running session printed {mRun}");
        }
        await using (var w2 = await c.Open(idExit))
        {
            await Ctx.Ready(w2);
            await w2.Send(c.Expr(mExit) + "\r");
            ev.Check(await w2.WaitContains(mExit, 10000), $"exiting session printed {mExit}");
            ev.Check(await c.ExitShell(w2, idExit), "shell exit observed before restart");
        }
        await Task.Delay(1000);
        c.Host.Kill();
        ev.Info("host killed (job terminate)");
        var err = await c.Host.Start(30000);
        if (!ev.Check(err == null, "host restarted with same PKSHELL_HOME" + (err == null ? "" : ": " + err))) return Outcome.FAIL;
        c.ResetApis();
        var list = await c.ListSessions();
        foreach (var (id, marker, label) in new[] { (idExit, mExit, "exited-before-restart"), (idRun, mRun, "running-at-kill") })
        {
            var e = Ctx.FindById(list, id);
            ev.Check(e != null && Str(e, "kind") == "history", $"{label} {id} listed as kind={Str(e, "kind") ?? "(missing)"} state={Str(e, "state")}");
            var vt = Path.Combine(c.Home, "sessions", id, "output.vt");
            bool has = false;
            long len = -1;
            if (File.Exists(vt))
            {
                // The host may keep output.vt open for append; share read/write/delete so we don't collide with it.
                using var fs = new FileStream(vt, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
                using var ms = new MemoryStream();
                fs.CopyTo(ms);
                var bytes = ms.ToArray();
                len = bytes.Length;
                has = bytes.AsSpan().IndexOf(Encoding.UTF8.GetBytes(marker)) >= 0;
            }
            ev.Check(has, $"{label} output.vt ({len} bytes) preserved with {marker}");
            ev.Check(File.Exists(Path.Combine(c.Home, "sessions", id, "meta.json")), $"{label} meta.json exists");
        }
        return ev.Result();
    }

    // ---------------------------------------------------------------- AC-5
    public static async Task<Outcome> AC5(Ctx c, Ev ev)
    {
        var id = Ctx.Id(await c.NewSession(ev, "qa-ac5"));
        await using var a = await c.Open(id);
        await Ctx.Ready(a);
        await using var b = await c.Open(id);
        await b.WaitReplayEnd(5000);
        await b.WaitQuiet(500, 5000);
        long oa = a.Length, ob = b.Length;
        var ma = "INA_" + Ctx.R();
        var mb = "INB_" + Ctx.R();
        await a.Send(c.Expr(ma) + "\r");
        ev.Check(await a.WaitContains(ma, 10000, oa), $"input from client A ran ({ma}) - seen by A");
        ev.Check(await b.WaitContains(ma, 5000, ob), $"input from client A seen by B");
        await a.WaitQuiet(400, 3000);
        await b.Send(c.Expr(mb) + "\r");
        ev.Check(await b.WaitContains(mb, 10000, ob), $"input from client B ran ({mb}) - seen by B");
        ev.Check(await a.WaitContains(mb, 5000, oa), $"input from client B seen by A");
        return ev.Result();
    }

    // ---------------------------------------------------------------- AC-6
    public static async Task<Outcome> AC6(Ctx c, Ev ev)
    {
        var id = Ctx.Id(await c.NewSession(ev, "qa-ac6"));
        await using var big = await c.Open(id, cols: 120, rows: 30);
        await Ctx.Ready(big);
        int ti = big.TextCount;
        var small = await c.Open(id, cols: 50, rows: 20);
        try
        {
            var msg = await big.WaitText("size", 5000, ti, j => Int(j, "cols") == 50 && Int(j, "rows") == 20);
            ev.Check(msg != null, "big client got {\"type\":\"size\",cols:50,rows:20}" + (msg == null ? $" (got: {string.Join(",", big.TextsOfType("size", ti).Select(j => j.ToJsonString()))})" : ""));
            var s = await c.WaitSession(id, x => Int(x, "cols") == 50 && Int(x, "rows") == 20, 3000) ?? await c.GetSession(id);
            ev.Check(Int(s, "cols") == 50 && Int(s, "rows") == 20, $"GET session size {Int(s, "cols")}x{Int(s, "rows")} (expect 50x20)");
            await big.WaitQuiet(700, 4000);
            var inner = await Probe(big, c);
            ev.Check(inner == "50x20", $"inside shell [Console]::WindowWidth x WindowHeight = {inner ?? "(none)"} (expect 50x20)");
        }
        finally
        {
            ti = big.TextCount;
            await small.DisposeAsync();
        }
        var msg2 = await big.WaitText("size", 5000, ti, j => Int(j, "cols") == 120 && Int(j, "rows") == 30);
        ev.Check(msg2 != null, "after small client left: size message 120x30");
        var s2 = await c.WaitSession(id, x => Int(x, "cols") == 120 && Int(x, "rows") == 30, 3000) ?? await c.GetSession(id);
        ev.Check(Int(s2, "cols") == 120 && Int(s2, "rows") == 30, $"GET session size {Int(s2, "cols")}x{Int(s2, "rows")} (expect 120x30)");
        await big.WaitQuiet(700, 4000);
        var inner2 = await Probe(big, c);
        ev.Check(inner2 == "120x30", $"inside shell = {inner2 ?? "(none)"} (expect 120x30)");
        return ev.Result();

        static async Task<string?> Probe(WsClient ws, Ctx c)
        {
            long off = ws.Length;
            await ws.Send("'W'+'IN='+[Console]::WindowWidth+'x'+[Console]::WindowHeight\r");
            await ws.WaitIndex("WIN=", 8000, off);
            await ws.WaitQuiet(400, 3000);
            var ms = Regex.Matches(Encoding.UTF8.GetString(ws.Slice(off)), @"WIN=(\d+)x(\d+)");
            return ms.Count == 0 ? null : $"{ms[^1].Groups[1].Value}x{ms[^1].Groups[2].Value}";
        }
    }

    // ---------------------------------------------------------------- AC-7
    public static async Task<Outcome> AC7(Ctx c, Ev ev)
    {
        var id = Ctx.Id(await c.NewSession(ev, "qa-ac7"));
        var r1 = await c.Local.Get("/api/sessions", null);
        ev.Check(r1.Status == 401, $"REST no token -> {r1.Status}");
        var r2 = await c.Local.Get("/api/sessions", "bogus-" + Ctx.R());
        ev.Check(r2.Status == 401, $"REST bad token -> {r2.Status}");
        var (w0, st0, e0) = await WsClient.TryConnect(c.WsUrl(id, token: ""), c.Fp);
        if (w0 != null) await w0.DisposeAsync();
        ev.Check(w0 == null && st0 == 401, $"WS no token -> HTTP {st0} {Trunc(e0, 80)}");
        var (w1, st1, _) = await WsClient.TryConnect(c.WsUrl(id, token: "bogus"), c.Fp);
        if (w1 != null) await w1.DisposeAsync();
        ev.Check(w1 == null && st1 == 401, $"WS bad token -> HTTP {st1}");

        var ps = await c.Local.Post("/api/pair/start", c.Token);
        if (!ev.Check(ps.Ok, $"pair/start -> {ps}")) return Outcome.FAIL;
        var code = Str(ps.Json, "code");
        var devName = "qa-device-" + Ctx.R();
        var cl = await c.Local.Post("/api/pair/claim", null, new { code, deviceName = devName });
        var devId = Str(cl.Json, "deviceId");
        var devTok = Str(cl.Json, "token");
        ev.Check(cl.Ok && devId != null && devTok != null, $"pair/claim -> {cl.Status} deviceId={devId}");
        if (!cl.Ok || devId == null || devTok == null) return Outcome.FAIL;

        var devApi = c.LanApi ?? c.Local;
        var rDev = await devApi.Get("/api/sessions", devTok);
        ev.Check(rDev.Ok, $"device token works via {devApi.Host} -> {rDev.Status}");
        var reuse = await c.Local.Post("/api/pair/claim", null, new { code, deviceName = devName + "-again" });
        ev.Check(reuse.Status == 401, $"claim code reuse -> {reuse.Status}");
        if (c.LanApi != null)
        {
            var rl = await c.LanApi.Get("/api/sessions", c.Token);
            ev.Check(rl.Status == 401, $"local.token from non-loopback {c.Lan} -> {rl.Status}");
            var (wl, stl, _) = await WsClient.TryConnect(c.WsUrl(id, host: c.Lan), c.Fp);
            if (wl != null) await wl.DisposeAsync();
            ev.Check(wl == null && stl == 401, $"WS with local.token from {c.Lan} -> HTTP {stl}");
        }
        else ev.Info("no LAN IPv4: non-loopback local-token check not applicable");
        var rd = await c.Local.Get("/api/devices", devTok);
        ev.Check(rd.Status == 401, $"/api/devices with device token (local-only endpoint) -> {rd.Status}");
        var devs = await c.Local.Get("/api/devices", c.Token);
        ev.Check(devs.Ok && devs.Body.Contains(devId), $"GET /api/devices lists {devId}");

        await using var dws = await c.Open(id, token: devTok, host: c.Lan ?? "127.0.0.1");
        await dws.WaitReplayEnd(5000);
        ev.Check(!dws.Closed, "device WS connected");
        var del = await c.Local.Delete($"/api/devices/{devId}", c.Token);
        ev.Check(del.Ok, $"DELETE /api/devices/{devId} -> {del.Status}");
        bool closed = await dws.WaitClosed(5000);
        ev.Check(closed && dws.CloseCode == 4401, $"open device WS closed after revoke: closed={closed} code={dws.CloseCode?.ToString() ?? "none"} desc={dws.CloseDesc}");
        var after = await devApi.Get("/api/sessions", devTok);
        ev.Check(after.Status == 401, $"revoked device token REST -> {after.Status}");
        var (wr, str, _) = await WsClient.TryConnect(c.WsUrl(id, token: devTok), c.Fp);
        if (wr != null) await wr.DisposeAsync();
        ev.Check(wr == null && str == 401, $"revoked device token WS -> HTTP {str}");
        return ev.Result();
    }

    // ---------------------------------------------------------------- AC-8
    public static async Task<Outcome> AC8(Ctx c, Ev ev)
    {
        // netstat is authoritative here: IPGlobalProperties.GetActiveTcpListeners() was observed to omit live listeners on this OS.
        var (nc, nout) = await Run("netstat.exe", new[] { "-ano", "-p", "TCP" }, null, null, 20000);
        var (_, nout6) = await Run("netstat.exe", new[] { "-ano", "-p", "TCPv6" }, null, null, 20000);
        var listeners = new List<IPEndPoint>();
        foreach (var line in (nout + "\n" + nout6).Split('\n'))
        {
            var cols = line.Split(' ', StringSplitOptions.RemoveEmptyEntries);
            if (cols.Length < 4 || !cols[0].StartsWith("TCP") || !line.Contains("LISTENING")) continue;
            if (IPEndPoint.TryParse(cols[1], out var ep) && ep.Port == c.Port) listeners.Add(ep);
        }
        ev.Info($"netstat(exit {nc}) listeners on port: " + string.Join(", ", listeners.Select(l => l.ToString())));
        var gp = IPGlobalProperties.GetIPGlobalProperties().GetActiveTcpListeners().Count(e => e.Port == c.Port);
        ev.Info($"(IPGlobalProperties reports {gp} for the port)");
        if (listeners.Count == 0) ev.Info("TCP table shows no listener although the host serves (table incomplete on this OS); relying on the probe below");
        ev.Check(!listeners.Any(l => l.Address.Equals(IPAddress.Any) || l.Address.Equals(IPAddress.IPv6Any)), "TCP table: no 0.0.0.0 / [::] wildcard listener");
        var expected = new[] { "127.0.0.1", c.Lan, c.Tailscale }.Where(x => x != null).Cast<string>().ToHashSet();
        ev.Check(listeners.All(l => expected.Contains(l.Address.ToString())), $"TCP table: listeners only on requested binds ({string.Join(",", expected)})");
        // Behavioral wildcard probe: a 0.0.0.0/[::] listener would accept on every local address; per-IP binds refuse unbound ones.
        var unbound = NetworkInterface.GetAllNetworkInterfaces().Where(n => n.OperationalStatus == OperationalStatus.Up)
            .SelectMany(n => n.GetIPProperties().UnicastAddresses.Select(u => u.Address))
            .Where(a => a.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork && !expected.Contains(a.ToString()) && !IPAddress.IsLoopback(a))
            .Append(IPAddress.Parse("127.0.0.2")).Append(IPAddress.IPv6Loopback).Distinct().ToList();
        var accepted = new List<string>();
        foreach (var a in unbound)
        {
            using var tc = new System.Net.Sockets.TcpClient(a.AddressFamily);
            try
            {
                using var cts = new CancellationTokenSource(2000);
                await tc.ConnectAsync(a, c.Port, cts.Token);
                accepted.Add(a.ToString());
            }
            catch (Exception e) when (e is System.Net.Sockets.SocketException or OperationCanceledException) { }
        }
        ev.Check(accepted.Count == 0, $"connect to unbound local addresses refused ({string.Join(",", unbound)})" + (accepted.Count > 0 ? $" ACCEPTED on {string.Join(",", accepted)}" : ""));

        bool plainOk;
        try
        {
            using var h = new HttpClient { Timeout = TimeSpan.FromSeconds(5) };
            using var resp = await h.GetAsync($"http://127.0.0.1:{c.Port}/api/info");
            plainOk = resp.IsSuccessStatusCode;
            ev.Info($"plain http answered {(int)resp.StatusCode}");
        }
        catch (Exception e) when (e is HttpRequestException or TaskCanceledException or IOException)
        {
            plainOk = false;
            ev.Info($"plain http failed: {e.GetType().Name}: {Trunc(AllMessages(e), 100)}");
        }
        ev.Check(!plainOk, "plain http:// to the port is not served");

        // TLS (pinned) reachability on every non-loopback bind using a device token.
        var nonLoop = new[] { c.Lan, c.Tailscale }.Where(x => x != null).Cast<string>().ToList();
        if (nonLoop.Count > 0)
        {
            var ps = await c.Local.Post("/api/pair/start", c.Token);
            var cl = await c.Local.Post("/api/pair/claim", null, new { code = Str(ps.Json, "code"), deviceName = "qa-ac8-" + Ctx.R() });
            var tok = Str(cl.Json, "token");
            foreach (var ip in nonLoop)
            {
                using var api = new Api(ip, c.Port, c.Fp);
                var r = await api.Get("/api/info", tok);
                ev.Check(r.Ok, $"wss/https reachable + cert pinned via {ip} -> {r.Status}");
            }
            var did = Str(cl.Json, "deviceId");
            if (did != null) await c.Local.Delete($"/api/devices/{did}", c.Token);
        }
        else ev.Info("no LAN/Tailscale IPv4 present: only loopback TLS verified");

        // Input -> echo latency at an idle prompt (time to the frame that carries the echoed char).
        var id = Ctx.Id(await c.NewSession(ev, "qa-ac8"));
        await using var ws = await c.Open(id);
        await Ctx.Ready(ws);
        const string chars = "zwkjgyovxzwkjgyovxzw";
        var lat = new List<double>();
        foreach (var ch in chars)
        {
            long off = ws.Length;
            long t0 = Stopwatch.GetTimestamp();
            await ws.Send(ch.ToString());
            long idx = -1;
            var sw = Stopwatch.StartNew();
            while (sw.ElapsedMilliseconds < 2000 && (idx = ws.IndexOf(ch.ToString(), off)) < 0) await Task.Delay(1);
            long ts = idx >= 0 ? ws.FrameTsAt(idx) : -1;
            lat.Add(ts > 0 ? (ts - t0) * 1000.0 / Stopwatch.Frequency : 2000);
            await ws.WaitQuiet(120, 1000);
        }
        await ws.Send("\x03");
        var med = Median(lat);
        ev.Info("latencies ms: " + string.Join(",", lat.Select(x => x.ToString("0.0"))));
        ev.Check(med < 100, $"input->echo median {med:0.0} ms over {lat.Count} round trips (< 100ms)");
        return ev.Result();
    }

    // ---------------------------------------------------------------- AC-9
    public static async Task<Outcome> AC9(Ctx c, Ev ev)
    {
        var idRun = Ctx.Id(await c.NewSession(ev, "qa-ac9-run"));
        var idHist = Ctx.Id(await c.NewSession(ev, "qa-ac9-hist"));
        await using (var w = await c.Open(idHist))
        {
            await Ctx.Ready(w);
            ev.Check(await c.ExitShell(w, idHist), "history candidate exited");
        }
        var dir = c.MakeDir("ac9ext");
        int pid = c.SpawnExternal(dir, "AC9EXT_" + Ctx.R());
        try
        {
            var ext = await c.WaitInList($"ext-{pid}", 10000);
            ev.Check(ext != null, $"external ext-{pid} appeared in list");
            var list = await c.ListSessions();
            var required = new[] { "id", "kind", "title", "shell", "cwd", "state", "lastActivity" };
            foreach (var (id, kind) in new[] { (idRun, "host"), ($"ext-{pid}", "external"), (idHist, "history") })
            {
                var e = Ctx.FindById(list, id);
                if (!ev.Check(e != null, $"{kind} entry {id} present")) continue;
                ev.Check(Str(e, "kind") == kind, $"{id} kind={Str(e, "kind")} (expect {kind})");
                var obj = e as JsonObject;
                var missing = required.Where(k => obj == null || !obj.ContainsKey(k) || (k != "cwd" && k != "title" && obj[k] == null)).ToList();
                ev.Check(missing.Count == 0, $"{kind} has fields {string.Join("/", required)}" + (missing.Count > 0 ? $" missing={string.Join(",", missing)}" : ""));
                if (kind != "external") ev.Check(!string.IsNullOrEmpty(Str(e, "cwd")), $"{kind} cwd non-empty ({Str(e, "cwd")})");
                if (kind == "external") ev.Check(Int(e, "pid") == pid, $"external pid={Int(e, "pid")}");
                ev.Info($"{kind}: {Compact(e, 220)}");
            }
        }
        finally
        {
            KillPid(pid);
        }
        return ev.Result();
    }

    // ---------------------------------------------------------------- AC-10
    public static async Task<Outcome> AC10(Ctx c, Ev ev)
    {
        var dir = c.MakeDir("ac10");
        var id = Ctx.Id(await c.NewSession(ev, "qa-ac10"));
        var mOld = "AC10OLD_" + Ctx.R();
        await using (var w = await c.Open(id))
        {
            await Ctx.Ready(w);
            await w.Send($"Set-Location -LiteralPath '{dir}'\r");
            await w.WaitQuiet(600, 5000);
            await w.Send(c.Expr(mOld) + "\r");
            ev.Check(await w.WaitContains(mOld, 10000), $"old marker {mOld} printed after cd");
            await w.WaitQuiet(600, 4000);
            ev.Check(await c.ExitShell(w, id), "old shell exited");
        }
        var list = await c.ListSessions();
        var h = Ctx.FindById(list, id);
        ev.Check(Str(h, "kind") == "history", $"exited session listed as kind={Str(h, "kind")}");
        ev.Check(SamePath(Str(h, "cwd"), dir), $"history cwd tracked = {Str(h, "cwd")} (expect {dir})");
        var r = await c.Local.Post($"/api/sessions/{id}/resume", c.Token);
        if (!ev.Check(r.Ok && r.Json != null, $"resume -> {r}")) return Outcome.FAIL;
        var nid = Ctx.Id(r.Json);
        c.CreatedSessions.Add(nid);
        ev.Check(Str(r.Json, "kind") == "host" && Str(r.Json, "state") == "running", $"new session {nid} kind={Str(r.Json, "kind")} state={Str(r.Json, "state")}");
        ev.Check(Str(r.Json, "resumedFrom") == id, $"resumedFrom={Str(r.Json, "resumedFrom")}");
        ev.Check(Str(r.Json, "shell") == Str(h, "shell") && Str(r.Json, "title") == Str(h, "title"), $"same shell/title ({Str(r.Json, "shell")}/{Str(r.Json, "title")})");
        ev.Check(SamePath(Str(r.Json, "cwd"), dir), $"new session cwd={Str(r.Json, "cwd")}");
        await using (var w2 = await c.Open(nid))
        {
            ev.Check(await w2.WaitReplayEnd(8000), "replay-end received");
            await Ctx.Ready(w2);
            long split = w2.ReplayEndOffset < 0 ? w2.Length : w2.ReplayEndOffset;
            var replay = w2.Slice(0, split);
            ev.Check(replay.AsSpan().IndexOf(Encoding.UTF8.GetBytes(mOld)) >= 0, "replay contains old marker");
            ev.Check(replay.AsSpan().IndexOf(Encoding.UTF8.GetBytes("resumed")) >= 0, "replay contains 'resumed' separator");
            long off = w2.Length;
            await w2.Send(Ctx.CwdProbe(Str(r.Json, "shell")) + "\r");
            await w2.WaitIndex("QCWD=", 8000, off);
            await w2.WaitQuiet(400, 3000);
            var cwd = Ctx.LastCwd(w2.Slice(off));
            ev.Check(SamePath(cwd, dir), $"new shell's actual cwd = {cwd ?? "(none)"}");
        }
        var list2 = await c.ListSessions();
        ev.Check(Ctx.FindById(list2, id) == null, "old history entry replaced (no longer listed)");
        return ev.Result();
    }

    // ---------------------------------------------------------------- AC-12 / 13 / 14
    public static async Task<Outcome> AC12_14(Ctx c, Ev ev)
    {
        var dir = c.MakeDir("ext");
        var r = Ctx.R();
        var marker = "EXT_MARKER_" + r;
        int pid = c.SpawnExternal(dir, marker);
        ev.Info($"spawned external cmd.exe pid={pid} cwd={dir}");
        try
        {
            var e = await c.WaitInList($"ext-{pid}", 10000);
            ev.Check(e != null && Str(e, "kind") == "external" && Int(e, "pid") == pid, $"[12] listed: {Compact(e, 200)}");
            if (e == null) return Outcome.FAIL;
            var sw = Stopwatch.StartNew();
            await using (var ws = await c.Open($"ext-{pid}"))
            {
                long at = await ws.WaitIndex(marker, 6000);
                long ms = sw.ElapsedMilliseconds;
                ev.Check(at >= 0 && ms <= 3000, $"[13] marker frame received after {ms}ms (<=3000)");
                if (at >= 0) ev.Info($"[13] first frame head: {Esc(ws.Slice(0, Math.Min(ws.Length, 120)), 200)}");
                long off = ws.Length;
                var inMark = "EXT_INPUT_" + r;
                await ws.Send($"echo {inMark}\r");
                long ai = await ws.WaitIndex(inMark, 5000, off);
                ev.Check(ai >= 0, $"[13] injected input visible in later frame");
                var sgr16 = Regex.IsMatch(Encoding.Latin1.GetString(ws.Snapshot()), @"\x1b\[[0-9;]*(3[0-7]|9[0-7])[;m]");
                ev.Info($"[13] frames contain 16-color SGR: {sgr16}");

                var ad = await c.Local.Post($"/api/external/{pid}/adopt", c.Token);
                if (ev.Check(ad.Ok && ad.Json != null, $"[14] adopt -> {ad}"))
                {
                    var nid = Ctx.Id(ad.Json);
                    c.CreatedSessions.Add(nid);
                    ev.Check(Str(ad.Json, "kind") == "host", $"[14] adopted session {nid} kind={Str(ad.Json, "kind")} shell={Str(ad.Json, "shell")}");
                    ev.Check(SamePath(Str(ad.Json, "cwd"), dir), $"[14] adopted cwd={Str(ad.Json, "cwd")} (expect {dir})");
                    await using var nw = await c.Open(nid);
                    await Ctx.Ready(nw);
                    long o2 = nw.Length;
                    await nw.Send(Ctx.CwdProbe(Str(ad.Json, "shell")) + "\r");
                    await nw.WaitIndex("QCWD=", 8000, o2);
                    await nw.WaitQuiet(400, 3000);
                    var cwd = Ctx.LastCwd(nw.Slice(o2));
                    ev.Check(SamePath(cwd, dir), $"[14] adopted shell actual cwd = {cwd ?? "(none)"}");
                }
                await ws.Send("exit\r");
            }
            ev.Info($"external console exited on 'exit': {WaitPidExit(pid, 3000)}");
        }
        finally
        {
            if (PidAlive(pid)) KillPid(pid);
        }
        return ev.Result();
    }

    // ---------------------------------------------------------------- AC-16
    public static async Task<Outcome> AC16(Ctx c, Ev ev)
    {
        var id = Ctx.Id(await c.NewSession(ev, "qa-ac16"));
        await using var ws = await c.Open(id);
        await Ctx.Ready(ws);
        long off = ws.Length;
        // The typed echo never contains ESC[38;2;255;100;0m (255 is computed, ESC is [char]27).
        await ws.Send("Write-Host ([char]27+'[38;2;'+(250+5)+';100;0mTRUE'+'COLOR'+[char]27+'[0m')\r");
        long at = await ws.WaitIndex("TRUECOLOR", 10000, off);
        if (!ev.Check(at >= 0, "TRUECOLOR text emitted")) return Outcome.FAIL;
        await ws.WaitQuiet(300, 2000);
        var all = ws.Slice(off);
        int rel = (int)(at - off);
        int from = Math.Max(0, rel - 60);
        ev.Info($"bytes around marker: {Esc(all.AsSpan(from, Math.Min(all.Length - from, rel - from + 14)), 300)}");
        var latin = Encoding.Latin1.GetString(all);
        var m = Regex.Match(latin, @"\x1b\[[0-9;:]*38[;:]2[;:]+255[;:]100[;:]0[0-9;:]*m");
        ev.Check(m.Success, "raw stream contains SGR 38;2;255;100;0" + (m.Success ? $" as {Esc(Encoding.Latin1.GetBytes(m.Value))}" : " (ConPTY re-encoded; see bytes above)"));
        return ev.Result();
    }

    // ---------------------------------------------------------------- AC-17
    public static async Task<Outcome> AC17(Ctx c, Ev ev)
    {
        var id = Ctx.Id(await c.NewSession(ev, "qa-ac17"));
        await using var ws = await c.Open(id);
        await Ctx.Ready(ws);
        long off = ws.Length;
        await ws.Send("'한글'+'테스트'\r");
        var want = Encoding.UTF8.GetBytes("한글테스트");
        var echo = Encoding.UTF8.GetBytes("한글");
        var sw = Stopwatch.StartNew();
        long at = -1;
        while (sw.ElapsedMilliseconds < 10000 && (at = ws.IndexOf(want, off)) < 0) await Task.Delay(20);
        ev.Check(ws.IndexOf(echo, off) >= 0, "UTF-8 input echoed (한글 bytes ED959C EAB880 present)");
        ev.Check(at >= 0, $"output contains UTF-8 {Hex(want)} (한글테스트)");
        if (at < 0)
        {
            await ws.WaitQuiet(300, 2000);
            ev.Info("stream after send: " + Esc(ws.Slice(off), 300));
        }
        return ev.Result();
    }

    // ---------------------------------------------------------------- AC-20
    public static async Task<Outcome> AC20(Ctx c, Ev ev)
    {
        var id = Ctx.Id(await c.NewSession(ev, "qa-ac20"));
        await using var qa = await c.Open(id);
        await Ctx.Ready(qa);
        c.AttachIds.Add(id);
        using var pty = PtyProcess.Start($"\"{c.Exe}\" attach {id}", 120, 30, c.Home, c.Host.Env, c.Aux);
        ev.Info($"pkshell attach running in QA pseudoconsole pid={pty.Pid}");
        var s = await c.WaitSession(id, x => Ctx.ClientCount(x, "cli") >= 1, 10000);
        if (!ev.Check(s != null, "cli client registered on the session"))
        {
            ev.Info($"attach exited={pty.HasExited}; pty output: {Esc(pty.Snapshot(), 300)}");
            return Outcome.FAIL;
        }
        await Task.Delay(1000);
        var m = "AT_" + Ctx.R();
        await qa.Send(c.Expr(m) + "\r");
        var sw = Stopwatch.StartNew();
        while (sw.ElapsedMilliseconds < 10000 && !pty.Contains(m)) await Task.Delay(50);
        ev.Check(pty.Contains(m), $"attach view rendered {m} printed via another client");
        await qa.WaitQuiet(400, 3000);
        long off = qa.Length;
        var t = "TY_" + Ctx.R();
        pty.Write(c.Expr(t) + "\r");
        ev.Check(await qa.WaitContains(t, 10000, off), $"typing into attach reached the session ({t})");
        await qa.WaitQuiet(400, 3000);
        pty.Write("\x1d");
        await Task.Delay(150);
        pty.Write("d");
        ev.Check(pty.WaitExit(5000), "Ctrl+] d detaches (pkshell attach exited within 5s)");
        return ev.Result();
    }

    // ---------------------------------------------------------------- AC-21
    private static string? FindWt()
    {
        var wt = FindOnPath("wt.exe") ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Microsoft", "WindowsApps", "wt.exe");
        return File.Exists(wt) ? wt : null;
    }

    public static async Task<Outcome> AC21(Ctx c, Ev ev)
    {
        if (FindWt() == null) return ev.Skip("wt.exe not found");
        var id = Ctx.Id(await c.NewSession(ev, "qa-ac21"));
        c.AttachIds.Add(id);
        try
        {
            await using var a1 = await c.Open(id, client: "android", cols: 80, rows: 24, extra: "&openPcTab=1");
            await a1.WaitReplayEnd(5000);
            var sw = Stopwatch.StartNew();
            var s = await c.WaitSession(id, x => Ctx.ClientCount(x, "cli") >= 1, 15000);
            ev.Check(s != null, $"cli client (WT tab) attached within 15s ({sw.ElapsedMilliseconds}ms)");
            if (s == null)
            {
                var procs = (await ProcessesByName("psh.exe")).Where(p => p.Cmd.Contains(id)).ToList();
                ev.Info($"pkshell attach processes for {id}: {procs.Count} {Trunc(string.Join(" ; ", procs.Select(p => p.Cmd)), 200)}");
            }
            await using var a2 = await c.Open(id, client: "android", cols: 80, rows: 24, extra: "&openPcTab=1");
            await a2.WaitReplayEnd(5000);
            await Task.Delay(6000);
            var s2 = await c.GetSession(id);
            int cli = Ctx.ClientCount(s2, "cli");
            ev.Check(s != null && cli == 1, $"second android connect did not add a cli client (cli count={cli})");
        }
        finally
        {
            // Ending the session makes `pkshell attach` exit cleanly so the WT tab closes; then kill leftovers.
            await c.Local.Delete($"/api/sessions/{id}", c.Token);
            await Task.Delay(3000);
            var killed = await KillAttachProcesses(new[] { id });
            ev.Info($"cleanup: leftover attach processes killed={killed.Count}");
        }
        return ev.Result();
    }

    /// <summary>
    /// Focus half of AC-21: a known window (notepad) is made foreground, the WT tab is auto-opened, and the
    /// foreground window must be the same afterwards. SKIP when no foreground window is observable (locked or
    /// non-interactive desktop) since nothing can be asserted then.
    /// </summary>
    public static async Task<Outcome> AC21Focus(Ctx c, Ev ev)
    {
        if (FindWt() == null) return ev.Skip("wt.exe not found");
        var notepadsBefore = Process.GetProcessesByName("notepad").Select(p => p.Id).ToHashSet();
        Process? started = null;
        try
        {
            IntPtr np = IntPtr.Zero;
            try
            {
                started = Process.Start(new ProcessStartInfo("notepad.exe") { UseShellExecute = true });
                var sw = Stopwatch.StartNew();
                while (np == IntPtr.Zero && sw.ElapsedMilliseconds < 10000)
                {
                    await Task.Delay(250);
                    foreach (var p in Process.GetProcessesByName("notepad").Where(p => !notepadsBefore.Contains(p.Id)))
                    {
                        p.Refresh();
                        if (p.MainWindowHandle != IntPtr.Zero) { np = p.MainWindowHandle; break; }
                    }
                }
            }
            catch (System.ComponentModel.Win32Exception e) { ev.Info($"notepad.exe could not be started: {e.Message}"); }
            if (np != IntPtr.Zero)
            {
                Native.AllowSetForegroundWindow(Native.ASFW_ANY);
                bool set = Native.SetForegroundWindow(np);
                await Task.Delay(500);
                ev.Info($"notepad window {Native.WindowDesc(np)} SetForegroundWindow={set}");
            }
            else ev.Info("no notepad main window appeared within 10s");

            var fgBefore = Native.GetForegroundWindow();
            ev.Info("foreground before: " + Native.WindowDesc(fgBefore) + (np != IntPtr.Zero && fgBefore == np ? " (= notepad)" : ""));
            if (fgBefore == IntPtr.Zero)
                return ev.Skip("foreground window unobservable (GetForegroundWindow=0x0 even after trying SetForegroundWindow on notepad; desktop locked or non-interactive session) - run QA on an unlocked desktop");

            var id = Ctx.Id(await c.NewSession(ev, "qa-ac21f"));
            c.AttachIds.Add(id);
            try
            {
                await using var a1 = await c.Open(id, client: "android", cols: 80, rows: 24, extra: "&openPcTab=1");
                await a1.WaitReplayEnd(5000);
                var s = await c.WaitSession(id, x => Ctx.ClientCount(x, "cli") >= 1, 15000);
                if (!ev.Check(s != null, "cli client (WT tab) attached within 15s (precondition for focus check)")) return Outcome.FAIL;
                // The host restores focus right after launching wt.exe; give it a moment to settle.
                await Task.Delay(2000);
                var fgEnd = Native.GetForegroundWindow();
                ev.Check(fgEnd == fgBefore, $"foreground window unchanged/restored: before={Native.WindowDesc(fgBefore)} after={Native.WindowDesc(fgEnd)}");
            }
            finally
            {
                await c.Local.Delete($"/api/sessions/{id}", c.Token);
                await Task.Delay(3000);
                var killed = await KillAttachProcesses(new[] { id });
                ev.Info($"cleanup: leftover attach processes killed={killed.Count}");
            }
            return ev.Result();
        }
        finally
        {
            int killedNp = 0;
            foreach (var p in Process.GetProcessesByName("notepad").Where(p => !notepadsBefore.Contains(p.Id)))
            {
                try { p.Kill(); killedNp++; }
                catch (Exception e) when (e is InvalidOperationException or System.ComponentModel.Win32Exception) { }
            }
            try { if (started is { HasExited: false }) started.Kill(); }
            catch (Exception e) when (e is InvalidOperationException or System.ComponentModel.Win32Exception) { }
            started?.Dispose();
            ev.Info($"cleanup: notepad processes killed={killedNp}");
        }
    }

    // ---------------------------------------------------------------- AC-22
    public static async Task<Outcome> AC22(Ctx c, Ev ev)
    {
        var id = Ctx.Id(await c.NewSession(ev, "qa-ac22-list"));
        var env = c.Host.Env;
        var (lc, lo) = await Run(c.Exe, new[] { "list" }, c.Home, env, 20000);
        ev.Check(lc == 0 && lo.Contains(id), $"`pkshell list` exit={lc}, shows {id}: {Trunc(lo, 160)}");
        var (dc, dout) = await Run(c.Exe, new[] { "devices" }, c.Home, env, 20000);
        ev.Check(dc == 0, $"`pkshell devices` exit={dc}: {Trunc(dout, 120)}");
        var (pc, po) = await Run(c.Exe, new[] { "pair" }, c.Home, env, 20000);
        bool pinOut = System.Text.RegularExpressions.Regex.IsMatch(po, @"PIN:\s+\d{6}") && po.Contains("fingerprint:", StringComparison.Ordinal);
        ev.Check((pc == 0 || pc == -999) && pinOut, $"`pkshell pair` exit={(pc == -999 ? "timeout(killed)" : pc.ToString())}, prints PIN + fingerprint={pinOut}");
        // `pkshell revoke <deviceId>`: pair a device over REST, revoke it via the CLI, its token must then get 401.
        var ps = await c.Local.Post("/api/pair/start", c.Token);
        var cl = await c.Local.Post("/api/pair/claim", null, new { code = Str(ps.Json, "code"), deviceName = "qa-ac22-rev-" + Ctx.R() });
        var devId = Str(cl.Json, "deviceId");
        var devTok = Str(cl.Json, "token");
        if (ev.Check(ps.Ok && cl.Ok && devId != null && devTok != null, $"paired device for revoke: start={ps.Status} claim={cl.Status} deviceId={devId}"))
        {
            var pre = await c.Local.Get("/api/sessions", devTok);
            ev.Check(pre.Ok, $"device token works before revoke -> {pre.Status}");
            var (rc, ro) = await Run(c.Exe, new[] { "revoke", devId! }, c.Home, env, 20000);
            ev.Check(rc == 0, $"`pkshell revoke {devId}` exit={rc}: {Trunc(ro, 120)}");
            var post = await c.Local.Get("/api/sessions", devTok);
            ev.Check(post.Status == 401, $"revoked device token REST -> {post.Status}");
        }
        // `pkshell new` attaches to the console by default, so run it in a pseudoconsole and look for the new session.
        var title = "qa-ac22-new-" + Ctx.R();
        using (var pty = PtyProcess.Start($"\"{c.Exe}\" new --shell {c.Shell} --title {title}", 120, 30, c.Home, env, c.Aux))
        {
            JsonNode? hit = null;
            var sw = Stopwatch.StartNew();
            while (hit == null && sw.ElapsedMilliseconds < 15000)
            {
                hit = (await c.ListSessions()).FirstOrDefault(x => Str(x, "title") == title);
                if (hit == null) await Task.Delay(400);
            }
            ev.Check(hit != null, $"`pkshell new --title {title}` created a session {Str(hit, "id")}");
            if (hit != null)
            {
                c.CreatedSessions.Add(Ctx.Id(hit));
                c.AttachIds.Add(Ctx.Id(hit));
            }
            else ev.Info($"pkshell new output: {Esc(pty.Snapshot(), 200)}");
        }
        return ev.Result();
    }

    // ---------------------------------------------------------------- ANDROID
    public static async Task<Outcome> AndroidSmoke(string root, Ev ev)
    {
        var adb = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Android", "Sdk", "platform-tools", "adb.exe");
        if (!File.Exists(adb)) return ev.Skip($"adb not found at {adb}");
        var (dc, dout) = await Run(adb, new[] { "devices" }, null, null, 20000);
        try
        {
            return await AndroidSmokeOnDevice(root, adb, dc, dout, ev);
        }
        finally
        {
            // Do not leave a background adb server behind if this run started it.
            if (dout.Contains("daemon started", StringComparison.OrdinalIgnoreCase))
            {
                await Run(adb, new[] { "kill-server" }, null, null, 15000);
                ev.Info("adb server was started by this run -> kill-server");
            }
        }
    }

    private static async Task<Outcome> AndroidSmokeOnDevice(string root, string adb, int dc, string dout, Ev ev)
    {
        var serial = dout.Split('\n').Skip(1).Select(l => l.Trim()).Where(l => l.EndsWith("\tdevice") || Regex.IsMatch(l, @"\sdevice$"))
            .Select(l => l.Split('\t', ' ')[0]).FirstOrDefault();
        if (serial == null) return ev.Skip($"no adb device attached (adb devices exit={dc}: {Trunc(dout, 120)})");
        var apkDir = Path.Combine(root, "android", "app", "build", "outputs", "apk", "debug");
        var apk = Directory.Exists(apkDir) ? Directory.GetFiles(apkDir, "*.apk").OrderByDescending(File.GetLastWriteTimeUtc).FirstOrDefault() : null;
        if (apk == null) return ev.Skip($"device {serial} present but no debug APK in {apkDir} (run gradlew assembleDebug)");
        var (ic, io) = await Run(adb, new[] { "-s", serial, "install", "-r", apk }, null, null, 180000);
        ev.Check(ic == 0 && io.Contains("Success"), $"install {Path.GetFileName(apk)} on {serial}: exit={ic} {Trunc(io, 120)}");
        var (mc, mo) = await Run(adb, new[] { "-s", serial, "shell", "monkey", "-p", "com.neststudio.pocketshell", "-c", "android.intent.category.LAUNCHER", "1" }, null, null, 30000);
        ev.Info($"launch exit={mc} {Trunc(mo, 100)}");
        await Task.Delay(3000);
        var (_, pid) = await Run(adb, new[] { "-s", serial, "shell", "pidof", "com.neststudio.pocketshell" }, null, null, 15000);
        ev.Check(pid.Trim().Length > 0, $"com.neststudio.pocketshell process running (pid={pid.Trim()})");
        return ev.Result();
    }

    public static async Task<Outcome> AndroidUnit(string root, string outDir, int timeoutMs, Ev ev)
    {
        var android = Path.Combine(root, "android");
        var gradlew = Path.Combine(android, "gradlew.bat");
        if (!File.Exists(gradlew)) return ev.Skip("android/gradlew.bat not present");
        var env = new Dictionary<string, string> { ["JAVA_HOME"] = @"C:\Program Files\Microsoft\jdk-17.0.21.21-hotspot" };
        var (code, output) = await Run("cmd.exe", new[] { "/c", gradlew, "test", "--no-daemon", "--console=plain", "-Pkotlin.compiler.execution.strategy=in-process" }, android, env, timeoutMs);
        var log = Path.Combine(outDir, "gradle-test.log");
        File.WriteAllText(log, output, Encoding.UTF8);
        var lines = output.Split('\n').Select(l => l.Trim()).Where(l => l.Length > 0).ToList();
        int w = lines.FindIndex(l => l.StartsWith("* What went wrong"));
        var key = lines.Where(l => l.EndsWith(" FAILED") || Regex.IsMatch(l, @"\d+ tests? completed") || l.StartsWith("BUILD "))
            .Concat(w >= 0 ? lines.Skip(w + 1).Take(2) : Enumerable.Empty<string>()).Distinct().Take(8);
        ev.Check(code == 0, $"gradlew test exit={(code == -999 ? "timeout" : code.ToString())} (qa/out/gradle-test.log): {Trunc(string.Join(" / ", key), 400)}");
        return ev.Result();
    }
}
