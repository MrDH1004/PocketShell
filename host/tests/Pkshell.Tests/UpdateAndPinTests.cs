using System.IO.Compression;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using Pkshell.Core.Auth;
using Pkshell.Core.Net;
using Pkshell.Core.Update;

namespace Pkshell.Tests;

public class SemVerTests
{
    [Theory]
    [InlineData("0.1.1-alpha", "0.1.1")]
    [InlineData("0.1.1", "0.1.2-alpha")]
    [InlineData("1.0.0-alpha", "1.0.0-alpha.1")]
    [InlineData("1.0.0-alpha.1", "1.0.0-alpha.beta")]
    [InlineData("1.0.0-alpha.beta", "1.0.0-beta")]
    [InlineData("1.0.0-beta", "1.0.0-beta.2")]
    [InlineData("1.0.0-beta.2", "1.0.0-beta.11")]
    [InlineData("1.0.0-beta.11", "1.0.0-rc.1")]
    [InlineData("1.0.0-rc.1", "1.0.0")]
    [InlineData("1.9.0", "1.10.0")]
    [InlineData("1.10.0", "2.0.0")]
    [InlineData("0.1.0", "0.1.1-alpha")]
    [InlineData("1.0.0-9", "1.0.0-10")]
    [InlineData("1.0.0-99999999999999999999", "1.0.0-100000000000000000000")]
    public void OrderedPairs(string lower, string higher)
    {
        var a = SemVer.Parse(lower);
        var b = SemVer.Parse(higher);
        Assert.True(a < b, $"{lower} < {higher}");
        Assert.True(b > a);
        Assert.Equal(-1, Math.Sign(a.CompareTo(b)));
        Assert.Equal(1, Math.Sign(b.CompareTo(a)));
    }

    [Fact]
    public void BuildMetadataIgnoredAndTagPrefixAccepted()
    {
        Assert.Equal(0, SemVer.Parse("1.2.3+abc").CompareTo(SemVer.Parse("v1.2.3")));
        Assert.Equal("1.2.3-rc.1", SemVer.Parse("V1.2.3-rc.1+build.7").ToString());
        Assert.True(SemVer.Parse("0.1.1-alpha").IsPrerelease);
    }

    [Fact]
    public void OperatorsMatchCompareToAndAreNullSafe()
    {
        var a = SemVer.Parse("1.2.3-rc.1");
        var b = SemVer.Parse("1.2.3");
        var b2 = SemVer.Parse("v1.2.3+build.9");
        SemVer? none = null;

        Assert.True(a < b);
        Assert.True(a <= b);
        Assert.True(b > a);
        Assert.True(b >= a);
        Assert.True(b == b2);
        Assert.False(b != b2);
        Assert.True(a != b);
        Assert.True(b <= b2 && b >= b2);
        Assert.Equal(b.GetHashCode(), b2.GetHashCode());

        Assert.True(none == null);
        Assert.False(a == none);
        Assert.True(a != none);
        Assert.True(none < a);
        Assert.True(a > none);
    }

    [Theory]
    [InlineData("")]
    [InlineData("1.2")]
    [InlineData("01.2.3")]
    [InlineData("1.2.3-01")]
    [InlineData("1.2.3-")]
    [InlineData("1.2.3.4")]
    [InlineData("latest")]
    public void RejectsInvalid(string s) => Assert.False(SemVer.TryParse(s, out _));
}

public class ReleaseFeedTests
{
    private const string Hex = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private static string Feed() => """
        [
          {"tag_name":"v9.0.0","draft":true,"prerelease":false,"body":"draft",
           "assets":[{"name":"pocketshell-host-win-x64-v9.0.0.zip","browser_download_url":"http://x/9.zip","digest":null}]},
          {"tag_name":"v0.2.0-beta","draft":false,"prerelease":true,"body":"beta notes",
           "assets":[{"name":"pocketshell-host-win-x64-v0.2.0-beta.zip","browser_download_url":"http://x/b.zip","digest":"sha256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}]},
          {"tag_name":"v0.3.0","draft":false,"prerelease":false,"body":"android only",
           "assets":[{"name":"pocketshell-android-v0.3.0.apk","browser_download_url":"http://x/a.apk"}]},
          {"tag_name":"v0.1.1","draft":false,"prerelease":false,"body":"",
           "assets":[{"name":"pocketshell-host-win-x64-v0.1.1.zip","browser_download_url":"http://x/1.zip"},
                     {"name":"SHA256SUMS.txt","browser_download_url":"http://x/sums"}]},
          {"tag_name":"nightly","draft":false,"prerelease":true,"assets":[]}
        ]
        """;

    private static readonly string[] ExpectedFeedVersions = { "0.2.0-beta", "0.3.0", "0.1.1" };

    [Fact]
    public void DraftsAndNonSemverTagsAreDropped()
    {
        var list = ReleaseFeed.Parse(Feed());
        Assert.Equal(ExpectedFeedVersions, list.Select(r => r.Version.ToString()));
    }

    [Fact]
    public void PicksHighestReleaseThatHasTheHostAsset()
    {
        var latest = ReleaseFeed.PickLatest(ReleaseFeed.Parse(Feed()), ReleaseFeed.HostAssetName);
        Assert.NotNull(latest);
        Assert.Equal("0.2.0-beta", latest!.Version.ToString()); // 0.3.0 has no host zip, 9.0.0 is a draft
        Assert.Equal("pocketshell-host-win-x64-v0.2.0-beta.zip", ReleaseFeed.HostAssetName(latest.Version));
        Assert.Equal(Hex, ReleaseFeed.ParseDigest(latest.Asset("pocketshell-host-win-x64-v0.2.0-beta.zip")!.Digest));
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("sha1:aaaa")]
    [InlineData("sha256:abc")]
    [InlineData("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")]
    public void DigestMustBeSha256Prefixed(string? d) => Assert.Null(ReleaseFeed.ParseDigest(d));

    [Fact]
    public void Sha256SumsLookup()
    {
        var sums = $"{Hex.ToUpperInvariant()}  pocketshell-host-win-x64-v0.1.1.zip\r\n{new string('b', 64)} *pocketshell-android-v0.1.1.apk\n";
        Assert.Equal(Hex, ReleaseFeed.FindInSums(sums, "pocketshell-host-win-x64-v0.1.1.zip"));
        Assert.Equal(new string('b', 64), ReleaseFeed.FindInSums(sums, "pocketshell-android-v0.1.1.apk"));
        Assert.Null(ReleaseFeed.FindInSums(sums, "other.zip"));
    }
}

public class InstallTransactionTests
{
    private static string TempDir()
    {
        var d = Path.Combine(Path.GetTempPath(), "pkshell-tx-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(d);
        return d;
    }

    [Fact]
    public void ApplyRenamesToOldThenRollbackRestores()
    {
        var install = TempDir();
        var staging = TempDir();
        try
        {
            File.WriteAllText(Path.Combine(install, "psh.exe"), "old-exe");
            File.WriteAllText(Path.Combine(install, "keep.txt"), "untouched");
            File.WriteAllText(Path.Combine(staging, "psh.exe"), "new-exe");
            Directory.CreateDirectory(Path.Combine(staging, "sub"));
            File.WriteAllText(Path.Combine(staging, "sub", "new.dll"), "new-dll");

            var tx = InstallTransaction.Apply(staging, install);
            Assert.Equal("new-exe", File.ReadAllText(Path.Combine(install, "psh.exe")));
            Assert.Equal("old-exe", File.ReadAllText(Path.Combine(install, "psh.exe.old")));
            Assert.Equal("new-dll", File.ReadAllText(Path.Combine(install, "sub", "new.dll")));

            Assert.Empty(tx.Rollback());
            Assert.Equal("old-exe", File.ReadAllText(Path.Combine(install, "psh.exe")));
            Assert.False(File.Exists(Path.Combine(install, "psh.exe.old")));
            Assert.False(File.Exists(Path.Combine(install, "sub", "new.dll")));
            Assert.Equal("untouched", File.ReadAllText(Path.Combine(install, "keep.txt")));
        }
        finally
        {
            Directory.Delete(install, true);
            Directory.Delete(staging, true);
        }
    }

    [Fact]
    public void CleanupDeletesOnlyBackupsListedInManifest()
    {
        var install = TempDir();
        var staging = TempDir();
        try
        {
            File.WriteAllText(Path.Combine(install, "a.dll"), "old");
            File.WriteAllText(Path.Combine(install, "notes.txt"), "user");
            File.WriteAllText(Path.Combine(install, "notes.txt.old"), "user backup");
            File.WriteAllText(Path.Combine(staging, "a.dll"), "new");
            InstallTransaction.Apply(staging, install);
            Assert.Equal(new[] { "a.dll", InstallTransaction.ManifestName },
                File.ReadAllLines(Path.Combine(install, InstallTransaction.ManifestName)));

            var (deleted, remaining) = InstallTransaction.CleanupOld(install);
            Assert.Equal(1, deleted);
            Assert.Equal(0, remaining);
            Assert.False(File.Exists(Path.Combine(install, "a.dll.old")));
            Assert.Equal("new", File.ReadAllText(Path.Combine(install, "a.dll")));
            Assert.True(File.Exists(Path.Combine(install, "notes.txt.old")));
        }
        finally
        {
            Directory.Delete(install, true);
            Directory.Delete(staging, true);
        }
    }

    [Fact]
    public void LoadedLeftoverBackupIsMovedAsideAndCleanedLater()
    {
        var install = TempDir();
        var staging = TempDir();
        IntPtr lib = IntPtr.Zero;
        try
        {
            // a.dll.old left by an earlier update and still loaded (like the tray running the previous build):
            // Windows refuses to delete a mapped image but allows renaming it.
            var leftover = Path.Combine(install, "a.dll.old");
            File.Copy(Path.Combine(Environment.SystemDirectory, "version.dll"), leftover);
            lib = System.Runtime.InteropServices.NativeLibrary.Load(leftover);
            Assert.ThrowsAny<UnauthorizedAccessException>(() => File.Delete(leftover));

            File.WriteAllText(Path.Combine(install, "a.dll"), "current");
            File.WriteAllText(Path.Combine(staging, "a.dll"), "new");
            InstallTransaction.Apply(staging, install);
            Assert.Equal("new", File.ReadAllText(Path.Combine(install, "a.dll")));
            Assert.Equal("current", File.ReadAllText(Path.Combine(install, "a.dll.old")));
            var aside = Assert.Single(Directory.GetFiles(install, "a.dll.old" + InstallTransaction.StaleMarker + "*"));

            Assert.Equal((1, 1), InstallTransaction.CleanupOld(install)); // a.dll.old deleted, the loaded one not yet
            System.Runtime.InteropServices.NativeLibrary.Free(lib);
            lib = IntPtr.Zero;
            Assert.Equal((1, 0), InstallTransaction.CleanupOld(install));
            Assert.False(File.Exists(aside));
        }
        finally
        {
            if (lib != IntPtr.Zero) System.Runtime.InteropServices.NativeLibrary.Free(lib);
            Directory.Delete(install, true);
            Directory.Delete(staging, true);
        }
    }

    [Fact]
    public void CleanupSweepsStaleLeftoversOfFilesNoLongerShipped()
    {
        var install = TempDir();
        var staging = TempDir();
        try
        {
            Directory.CreateDirectory(Path.Combine(install, "sub"));
            var orphan = Path.Combine(install, "sub", "gone.dll.old" + InstallTransaction.StaleMarker + "ab12cd34");
            File.WriteAllText(orphan, "moved aside by an earlier update");
            File.WriteAllText(Path.Combine(install, "a.dll"), "old");
            File.WriteAllText(Path.Combine(staging, "a.dll"), "new"); // gone.dll is not in this update's manifest
            InstallTransaction.Apply(staging, install);

            Assert.Equal((2, 0), InstallTransaction.CleanupOld(install)); // a.dll.old + the orphan
            Assert.False(File.Exists(orphan));
        }
        finally
        {
            Directory.Delete(install, true);
            Directory.Delete(staging, true);
        }
    }

    [Fact]
    public void CleanupWithoutManifestDeletesNothing()
    {
        var install = TempDir();
        try
        {
            File.WriteAllText(Path.Combine(install, "a.dll"), "new");
            File.WriteAllText(Path.Combine(install, "a.dll.old"), "old");
            Assert.Equal((0, 0), InstallTransaction.CleanupOld(install));
            Assert.True(File.Exists(Path.Combine(install, "a.dll.old")));
        }
        finally { Directory.Delete(install, true); }
    }

    [Fact]
    public void RollbackKeepsTargetWhenBackupIsMissing()
    {
        var install = TempDir();
        var staging = TempDir();
        try
        {
            File.WriteAllText(Path.Combine(install, "a.json"), "old-a");
            File.WriteAllText(Path.Combine(install, "b.dll"), "old-b");
            File.WriteAllText(Path.Combine(staging, "a.json"), "new-a");
            File.WriteAllText(Path.Combine(staging, "b.dll"), "new-b");
            var tx = InstallTransaction.Apply(staging, install);
            File.Delete(Path.Combine(install, "a.json.old")); // backup lost before the rollback

            var failures = tx.Rollback();
            Assert.Single(failures);
            Assert.Contains("a.json", failures[0]);
            Assert.Equal("new-a", File.ReadAllText(Path.Combine(install, "a.json")));
            Assert.Equal("old-b", File.ReadAllText(Path.Combine(install, "b.dll")));
            Assert.False(File.Exists(Path.Combine(install, InstallTransaction.ManifestName)));
        }
        finally
        {
            Directory.Delete(install, true);
            Directory.Delete(staging, true);
        }
    }
}

public class UpdateStagingTests
{
    /// <summary>Tiny HTTP/1.1 server on 127.0.0.1: /feed → releases JSON, /dl/zip → zip bytes; counts zip downloads.</summary>
    private sealed class Feed : IDisposable
    {
        private readonly TcpListener _l = new(IPAddress.Loopback, 0);
        private readonly byte[] _zip;
        private readonly string _feed;
        public int ZipHits;

        public Feed(string version, byte[] zip)
        {
            _zip = zip;
            _l.Start();
            int port = ((IPEndPoint)_l.LocalEndpoint).Port;
            var digest = "sha256:" + Convert.ToHexString(SHA256.HashData(zip)).ToLowerInvariant();
            _feed = $$"""[{"tag_name":"v{{version}}","assets":[{"name":"pocketshell-host-win-x64-v{{version}}.zip","browser_download_url":"http://127.0.0.1:{{port}}/dl/zip","digest":"{{digest}}"}]}]""";
            Url = $"http://127.0.0.1:{port}/feed";
            _ = Task.Run(Loop);
        }

        public string Url { get; }

        private async Task Loop()
        {
            while (true)
            {
                TcpClient c;
                try { c = await _l.AcceptTcpClientAsync(); }
                catch (Exception e) when (e is ObjectDisposedException or SocketException) { return; }
                using (c)
                {
                    var st = c.GetStream();
                    var head = new StringBuilder();
                    var b = new byte[1];
                    while (!head.ToString().EndsWith("\r\n\r\n", StringComparison.Ordinal) && await st.ReadAsync(b) == 1) head.Append((char)b[0]);
                    var path = head.ToString().Split(' ')[1];
                    byte[] body = path.StartsWith("/dl/", StringComparison.Ordinal) ? _zip : Encoding.UTF8.GetBytes(_feed);
                    if (path.StartsWith("/dl/", StringComparison.Ordinal)) Interlocked.Increment(ref ZipHits);
                    await st.WriteAsync(Encoding.ASCII.GetBytes($"HTTP/1.1 200 OK\r\nContent-Length: {body.Length}\r\nConnection: close\r\n\r\n"));
                    await st.WriteAsync(body);
                }
            }
        }

        public void Dispose() => _l.Stop();
    }

    private static byte[] ZipWithTopFolder()
    {
        using var ms = new MemoryStream();
        using (var z = new ZipArchive(ms, ZipArchiveMode.Create, leaveOpen: true))
        {
            foreach (var name in new[] { "pocketshell-host/psh.exe", "pocketshell-host/psh.dll" })
            {
                using var w = new StreamWriter(z.CreateEntry(name).Open());
                w.Write("content of " + name);
            }
        }
        return ms.ToArray();
    }

    private static async Task<UpdateService> Check(string home, string feed)
    {
        var svc = new UpdateService(home, SemVer.Parse("1.0.0"), feed, UpdateModes.Auto, TimeSpan.FromHours(1), () => 0);
        await svc.CheckAsync();
        return svc;
    }

    [Fact]
    public async Task ReadyStagingIsReusedAfterReverifyingCachedZip()
    {
        var home = Path.Combine(Path.GetTempPath(), "pkshell-stage-" + Guid.NewGuid().ToString("N"));
        using var feed = new Feed("1.1.0", ZipWithTopFolder());
        try
        {
            using (var s1 = await Check(home, feed.Url))
            {
                Assert.Equal(UpdateStates.Ready, s1.Status.State);
                Assert.True(File.Exists(Path.Combine(s1.StagingDir!, "psh.exe")));
            }
            Assert.Equal(1, feed.ZipHits);

            // Restart (new service, same home): the single-top-folder staging is reused without downloading again.
            using (var s2 = await Check(home, feed.Url))
            {
                Assert.Equal(UpdateStates.Ready, s2.Status.State);
                Assert.Equal("content of pocketshell-host/psh.exe", File.ReadAllText(Path.Combine(s2.StagingDir!, "psh.exe")));
            }
            Assert.Equal(1, feed.ZipHits);

            // Cached zip tampered: re-verification fails, so it is downloaded and verified again.
            var zip = Path.Combine(home, "updates", "1.1.0", "pocketshell-host-win-x64-v1.1.0.zip");
            File.AppendAllText(zip, "x");
            using (var s3 = await Check(home, feed.Url))
                Assert.Equal(UpdateStates.Ready, s3.Status.State);
            Assert.Equal(2, feed.ZipHits);
        }
        finally
        {
            if (Directory.Exists(home)) Directory.Delete(home, true);
        }
    }
}

public class PinPairingTests
{
    [Fact]
    public void PinIsSixDigitsAndOneTime()
    {
        var p = new PinPairing();
        var (pin, exp) = p.Start();
        Assert.Matches("^[0-9]{6}$", pin);
        Assert.True(exp > DateTimeOffset.UtcNow.AddMinutes(4.9) && exp <= DateTimeOffset.UtcNow.AddMinutes(5).AddSeconds(1));
        Assert.Equal(PinClaimResult.Ok, p.TryConsume(pin));
        Assert.Equal(PinClaimResult.Invalid, p.TryConsume(pin));
    }

    [Fact]
    public void PinsAreRandom()
    {
        var seen = Enumerable.Range(0, 50).Select(_ => PinPairing.NewPin()).ToHashSet();
        Assert.True(seen.Count > 40, $"only {seen.Count} distinct PINs out of 50");
        Assert.All(seen, s => Assert.Matches("^[0-9]{6}$", s));
    }

    [Fact]
    public void ExpiresAfterFiveMinutes()
    {
        var now = DateTimeOffset.UtcNow;
        var p = new PinPairing(() => now);
        var (pin, _) = p.Start();
        now = now.AddMinutes(5);
        Assert.Equal(PinClaimResult.Invalid, p.TryConsume(pin));
    }

    [Fact]
    public void ValidJustBeforeExpiry()
    {
        var now = DateTimeOffset.UtcNow;
        var p = new PinPairing(() => now);
        var (pin, _) = p.Start();
        now = now.AddMinutes(5).AddSeconds(-1);
        Assert.Equal(PinClaimResult.Ok, p.TryConsume(pin));
    }

    [Fact]
    public void NewPinRevokesPrevious()
    {
        var p = new PinPairing();
        var (first, _) = p.Start();
        string second;
        do { (second, _) = p.Start(); } while (second == first);
        Assert.Equal(PinClaimResult.Invalid, p.TryConsume(first));
        Assert.Equal(PinClaimResult.Ok, p.TryConsume(second));
    }

    [Fact]
    public void FiveWrongAttemptsKillThePin()
    {
        var p = new PinPairing();
        var (pin, _) = p.Start();
        var wrong = pin == "000000" ? "111111" : "000000";
        for (int i = 0; i < 4; i++) Assert.Equal(PinClaimResult.Invalid, p.TryConsume(wrong));
        Assert.True(p.HasActivePin, "still alive after 4 wrong attempts");
        Assert.Equal(PinClaimResult.Invalid, p.TryConsume(wrong));
        Assert.False(p.HasActivePin);
        Assert.Equal(PinClaimResult.Invalid, p.TryConsume(pin)); // correct PIN no longer accepted
    }

    [Fact]
    public void FourWrongThenCorrectStillWorks()
    {
        var p = new PinPairing();
        var (pin, _) = p.Start();
        var wrong = pin == "000000" ? "111111" : "000000";
        for (int i = 0; i < 4; i++) p.TryConsume(wrong);
        Assert.Equal(PinClaimResult.Ok, p.TryConsume(pin));
    }

    [Fact]
    public void FpShortFormat()
    {
        const string fp = "086b420d7b50dbdc0123456789abcdef0123456789abcdef0123456789abcdef";
        Assert.Equal("086B-420D-7B50-DBDC", PinPairing.FpShort(fp));
        Assert.Matches("^[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}$", PinPairing.FpShort(fp));
        Assert.Throws<FormatException>(() => PinPairing.FpShort("abc"));
    }

    [Fact]
    public void RateLimiterBlocksAfterTenFailuresInTenMinutes()
    {
        var now = DateTimeOffset.UtcNow;
        var rl = new ClaimRateLimiter(() => now);
        for (int i = 0; i < 9; i++) rl.RecordFailure("10.0.0.5");
        Assert.False(rl.IsLimited("10.0.0.5"));
        rl.RecordFailure("10.0.0.5");
        Assert.True(rl.IsLimited("10.0.0.5"));
        Assert.False(rl.IsLimited("10.0.0.6")); // per IP
        now = now.AddMinutes(10).AddSeconds(1);
        Assert.False(rl.IsLimited("10.0.0.5")); // window slid past
    }
}

public class DiscoveryTests
{
    private static readonly string[] CandidateAddresses = { "127.0.0.1", "192.168.1.5", "10.1.2.3", "172.20.0.1", "100.80.1.2", "8.8.8.8", "::1" };
    private static readonly string[] ExpectedPickedAddresses = { "127.0.0.1", "192.168.1.5", "10.1.2.3", "172.20.0.1" };

    [Fact]
    public void ReplyHasFieldsAndNoSecrets()
    {
        var json = Encoding.UTF8.GetString(DiscoveryResponder.BuildReply("PC", ["192.168.0.2"],7380, "AAAA-BBBB-CCCC-DDDD"));
        Assert.Equal("""{"v":1,"name":"PC","hosts":["192.168.0.2"],"port":7380,"fpShort":"AAAA-BBBB-CCCC-DDDD"}""", json);
        Assert.DoesNotContain("token", json, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public void BindsOnlyPrivateLanAndLoopback()
    {
        var picked = DiscoveryResponder.SelectAddresses(CandidateAddresses
            .Select(System.Net.IPAddress.Parse)).Select(a => a.ToString());
        Assert.Equal(ExpectedPickedAddresses, picked);
        Assert.True(DiscoveryResponder.IsRequest(Encoding.ASCII.GetBytes("PKSHELL?1")));
        Assert.True(DiscoveryResponder.IsRequest(Encoding.ASCII.GetBytes("MPTA?1"))); // apps from before the rename
        Assert.False(DiscoveryResponder.IsRequest(Encoding.ASCII.GetBytes("PKSHELL?2")));
    }
}
