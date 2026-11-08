using System.IO.Compression;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace Pkshell.QA;

/// <summary>
/// Minimal local GitHub-Releases stand-in (DESIGN §11.2) on 127.0.0.1: GET /feed returns the configured releases JSON,
/// GET /dl/&lt;name&gt; serves registered asset bytes. Plain TcpListener + HTTP/1.1 (no http.sys URL ACL needed).
/// </summary>
internal sealed class FakeReleaseServer : IDisposable
{
    private readonly TcpListener _listener;
    private readonly CancellationTokenSource _cts = new();
    private readonly Dictionary<string, byte[]> _files = new(StringComparer.Ordinal);
    private readonly object _lk = new();
    private string _feed = "[]";
    private readonly List<string> _requests = new();

    public int Port { get; }
    public string FeedUrl => $"http://127.0.0.1:{Port}/feed";

    public FakeReleaseServer()
    {
        _listener = new TcpListener(IPAddress.Loopback, 0);
        _listener.Start();
        Port = ((IPEndPoint)_listener.LocalEndpoint).Port;
        _ = Task.Run(AcceptLoop);
    }

    public string Url(string name) => $"http://127.0.0.1:{Port}/dl/{Uri.EscapeDataString(name)}";

    public void SetFile(string name, byte[] data)
    {
        lock (_lk) _files[name] = data;
    }

    public void SetFeed(string json)
    {
        lock (_lk) _feed = json;
    }

    public List<string> Requests
    {
        get { lock (_lk) return _requests.ToList(); }
    }

    public int Hits(string pathPrefix) => Requests.Count(r => r.StartsWith(pathPrefix, StringComparison.Ordinal));

    /// <summary>One release object as GitHub returns it (subset of fields pkshell reads).</summary>
    public object Release(string version, bool draft, IEnumerable<(string Name, string? Digest)> assets) => new Dictionary<string, object?>
    {
        ["tag_name"] = "v" + version,
        ["name"] = "v" + version,
        ["draft"] = draft,
        ["prerelease"] = version.Contains('-'),
        ["body"] = $"QA release {version}",
        ["assets"] = assets.Select(a => new Dictionary<string, object?>
        {
            ["name"] = a.Name,
            ["browser_download_url"] = Url(a.Name),
            ["digest"] = a.Digest,
        }).ToList(),
    };

    public void SetReleases(params object[] releases) => SetFeed(JsonSerializer.Serialize(releases));

    private async Task AcceptLoop()
    {
        while (!_cts.IsCancellationRequested)
        {
            TcpClient client;
            try { client = await _listener.AcceptTcpClientAsync(_cts.Token); }
            catch (Exception e) when (e is OperationCanceledException or ObjectDisposedException or SocketException) { return; }
            _ = Task.Run(() => Serve(client));
        }
    }

    private async Task Serve(TcpClient client)
    {
        using (client)
        {
            try
            {
                var stream = client.GetStream();
                var head = new StringBuilder();
                var buf = new byte[1];
                // Read the request head byte by byte up to the blank line (requests are tiny GETs).
                while (head.Length < 16384)
                {
                    int n = await stream.ReadAsync(buf);
                    if (n == 0) return;
                    head.Append((char)buf[0]);
                    if (head.Length >= 4 && head.ToString(head.Length - 4, 4) == "\r\n\r\n") break;
                }
                var parts = head.ToString().Split("\r\n")[0].Split(' ');
                var path = parts.Length > 1 ? parts[1] : "/";
                lock (_lk) _requests.Add(path);
                int status = 200;
                string type = "application/octet-stream";
                byte[] body;
                lock (_lk)
                {
                    if (path.StartsWith("/feed", StringComparison.Ordinal))
                    {
                        body = Encoding.UTF8.GetBytes(_feed);
                        type = "application/json";
                    }
                    else if (path.StartsWith("/dl/", StringComparison.Ordinal) && _files.TryGetValue(Uri.UnescapeDataString(path[4..]), out var f)) body = f;
                    else
                    {
                        status = 404;
                        body = Encoding.UTF8.GetBytes("not found");
                        type = "text/plain";
                    }
                }
                var resp = $"HTTP/1.1 {status} {(status == 200 ? "OK" : "Not Found")}\r\nContent-Type: {type}\r\nContent-Length: {body.Length}\r\nConnection: close\r\n\r\n";
                await stream.WriteAsync(Encoding.ASCII.GetBytes(resp));
                await stream.WriteAsync(body);
                await stream.FlushAsync();
            }
            catch (Exception e) when (e is IOException or SocketException or ObjectDisposedException) { }
        }
    }

    public void Dispose()
    {
        _cts.Cancel();
        _listener.Stop();
    }
}

/// <summary>Builds the fake "newer" host release once per QA run (dotnet publish with -p:VersionOverride).</summary>
internal static class UpdateFixture
{
    private static readonly SemaphoreSlim Gate = new(1, 1);
    private static (string Version, byte[] GoodZip, byte[] BrokenZip, string PublishDir)? _built;

    public static string ReadVersionName(string root)
    {
        foreach (var line in File.ReadAllLines(Path.Combine(root, "version.properties")))
        {
            var t = line.Trim();
            if (t.StartsWith("versionName=", StringComparison.Ordinal)) return t["versionName=".Length..].Trim();
        }
        throw new InvalidOperationException("version.properties has no versionName");
    }

    /// <summary>A strictly higher SemVer: drop the prerelease (0.1.1-alpha → 0.1.1) or bump patch (0.1.1 → 0.1.2).</summary>
    public static string NextVersion(string current)
    {
        var core = current.Split('+')[0];
        int dash = core.IndexOf('-');
        if (dash > 0) return core[..dash];
        var p = core.Split('.');
        return $"{p[0]}.{p[1]}.{int.Parse(p[2]) + 1}";
    }

    public static string AssetName(string version) => $"pocketshell-host-win-x64-v{version}.zip";

    public static string Sha256(byte[] data) => Convert.ToHexString(SHA256.HashData(data)).ToLowerInvariant();

    public static async Task<(string Version, byte[] GoodZip, byte[] BrokenZip, string PublishDir)> Ensure(Ctx c, Ev ev)
    {
        await Gate.WaitAsync();
        try
        {
            if (_built != null) return _built.Value;
            var version = NextVersion(c.VersionName);
            var dir = Path.Combine(c.Work, "fake-release");
            var pub = Path.Combine(dir, "publish");
            var art = Path.Combine(dir, "artifacts");
            if (Directory.Exists(dir)) Directory.Delete(dir, true);
            var proj = Path.Combine(c.Root, "host", "src", "Pkshell", "Pkshell.csproj");
            var sw = System.Diagnostics.Stopwatch.StartNew();
            // Separate artifacts path so the repo's bin/obj (used by the main QA host) are not touched.
            var (code, output) = await Util.Run("dotnet", new[] { "publish", proj, "-c", "Release", "-nologo", "-o", pub, "--artifacts-path", art, $"-p:VersionOverride={version}" },
                c.Root, null, 280_000);
            File.WriteAllText(Path.Combine(c.OutDir, "fake-release-build.log"), output, Encoding.UTF8);
            if (code != 0 || !File.Exists(Path.Combine(pub, "psh.exe")))
                throw new InvalidOperationException($"fake release publish failed (exit {code}, qa/out/fake-release-build.log): {Util.Trunc(output, 300)}");
            ev.Info($"built fake release {version} via dotnet publish in {sw.Elapsed.TotalSeconds:0.0}s");
            var good = Zip(pub, null);
            // Broken build: same files but pkshell.dll is garbage, so the apphost starts and then fails to load the app.
            var broken = Zip(pub, "psh.dll");
            _built = (version, good, broken, pub);
            return _built.Value;
        }
        finally { Gate.Release(); }
    }

    private static byte[] Zip(string dir, string? corrupt)
    {
        using var ms = new MemoryStream();
        using (var z = new ZipArchive(ms, ZipArchiveMode.Create, leaveOpen: true))
        {
            foreach (var f in Directory.GetFiles(dir, "*", SearchOption.AllDirectories))
            {
                var rel = Path.GetRelativePath(dir, f).Replace('\\', '/');
                var e = z.CreateEntry(rel, CompressionLevel.Fastest);
                using var es = e.Open();
                if (corrupt != null && string.Equals(rel, corrupt, StringComparison.OrdinalIgnoreCase))
                    es.Write(Encoding.ASCII.GetBytes("this is not a .NET assembly " + Guid.NewGuid()));
                else
                {
                    using var fs = File.OpenRead(f);
                    fs.CopyTo(es);
                }
            }
        }
        return ms.ToArray();
    }

    /// <summary>Copies the QA host build (current version) into a fresh install dir.</summary>
    public static string Install(string fromDir, string toDir)
    {
        if (Directory.Exists(toDir)) Directory.Delete(toDir, true);
        foreach (var f in Directory.GetFiles(fromDir, "*", SearchOption.AllDirectories))
        {
            var t = Path.Combine(toDir, Path.GetRelativePath(fromDir, f));
            Directory.CreateDirectory(Path.GetDirectoryName(t)!);
            File.Copy(f, t);
        }
        return Path.Combine(toDir, "psh.exe");
    }

    /// <summary>relative path → sha256 for every file under <paramref name="dir"/>.</summary>
    public static Dictionary<string, string> Snapshot(string dir) =>
        Directory.GetFiles(dir, "*", SearchOption.AllDirectories).ToDictionary(
            f => Path.GetRelativePath(dir, f), f =>
            {
                using var fs = new FileStream(f, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
                return Convert.ToHexString(SHA256.HashData(fs));
            }, StringComparer.OrdinalIgnoreCase);

    public static bool SameTree(Dictionary<string, string> a, Dictionary<string, string> b) =>
        a.Count == b.Count && a.All(kv => b.TryGetValue(kv.Key, out var v) && v == kv.Value);

    public static List<string> OldFiles(string dir) =>
        Directory.GetFiles(dir, "*.old", SearchOption.AllDirectories).Select(f => Path.GetRelativePath(dir, f)).ToList();
}
