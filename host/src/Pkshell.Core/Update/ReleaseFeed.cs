using System.Text.Json;
using System.Text.RegularExpressions;

namespace Pkshell.Core.Update;

public sealed record ReleaseAsset(string Name, string Url, string? Digest);

public sealed record ReleaseInfo(SemVer Version, string Tag, bool Prerelease, string Notes, IReadOnlyList<ReleaseAsset> Assets)
{
    public ReleaseAsset? Asset(string name) => Assets.FirstOrDefault(a => string.Equals(a.Name, name, StringComparison.Ordinal));
}

/// <summary>GitHub Releases feed parsing and selection (DESIGN §11.2).</summary>
public static class ReleaseFeed
{
    public const string DefaultUrl = "https://api.github.com/repos/MrDH1004/PocketShell/releases?per_page=10";
    public const string UserAgent = "pocketshell-updater";
    public const string SumsAssetName = "SHA256SUMS.txt";

    public static string HostAssetName(SemVer v) => $"pocketshell-host-win-x64-v{v}.zip";

    /// <summary>Parses the releases array. Drafts and tags that are not SemVer are dropped.</summary>
    public static List<ReleaseInfo> Parse(string json)
    {
        var list = new List<ReleaseInfo>();
        using var doc = JsonDocument.Parse(json);
        if (doc.RootElement.ValueKind != JsonValueKind.Array) throw new JsonException("releases feed is not a JSON array");
        foreach (var r in doc.RootElement.EnumerateArray())
        {
            if (r.ValueKind != JsonValueKind.Object) continue;
            if (r.TryGetProperty("draft", out var d) && d.ValueKind == JsonValueKind.True) continue;
            var tag = Str(r, "tag_name");
            if (!SemVer.TryParse(tag, out var ver)) continue;
            var assets = new List<ReleaseAsset>();
            if (r.TryGetProperty("assets", out var arr) && arr.ValueKind == JsonValueKind.Array)
                foreach (var a in arr.EnumerateArray())
                {
                    var name = Str(a, "name");
                    var url = Str(a, "browser_download_url");
                    if (name.Length == 0 || url.Length == 0) continue;
                    var digest = Str(a, "digest");
                    assets.Add(new ReleaseAsset(name, url, digest.Length > 0 ? digest : null));
                }
            bool pre = r.TryGetProperty("prerelease", out var p) && p.ValueKind == JsonValueKind.True;
            list.Add(new ReleaseInfo(ver, tag, pre, Str(r, "body"), assets));
        }
        return list;
    }

    /// <summary>
    /// A feed that is a local folder (a rooted path or a file: URI) instead of a releases URL: builds made on this PC are
    /// installed without publishing them (scripts/release.ps1 -Local drops the host zip there).
    /// </summary>
    public static bool TryLocalDir(string feedUrl, out string dir)
    {
        dir = "";
        if (string.IsNullOrWhiteSpace(feedUrl)) return false;
        if (!Uri.TryCreate(feedUrl.Trim(), UriKind.Absolute, out var u) || !u.IsFile) return false;
        dir = u.LocalPath;
        return true;
    }

    /// <summary>
    /// Releases in a local folder feed: every <c>pocketshell-host-win-x64-v&lt;ver&gt;.zip</c>. The asset URL is the file path
    /// and the digest is the file's own SHA-256 (the folder is local and trusted; the hash still guards the copy).
    /// </summary>
    public static List<ReleaseInfo> ScanDir(string dir)
    {
        if (!Directory.Exists(dir)) throw new DirectoryNotFoundException($"local update folder not found: {dir}");
        var list = new List<ReleaseInfo>();
        foreach (var path in Directory.EnumerateFiles(dir, "pocketshell-host-win-x64-v*.zip"))
        {
            var name = Path.GetFileName(path);
            var m = Regex.Match(name, @"^pocketshell-host-win-x64-v(.+)\.zip$");
            if (!m.Success || !SemVer.TryParse(m.Groups[1].Value, out var ver) || name != HostAssetName(ver)) continue;
            string hex;
            using (var fs = File.OpenRead(path))
                hex = Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(fs)).ToLowerInvariant();
            list.Add(new ReleaseInfo(ver, "v" + ver, ver.IsPrerelease, "local build",
                new[] { new ReleaseAsset(name, path, "sha256:" + hex) }));
        }
        return list;
    }

    /// <summary>Highest SemVer release (prerelease included) that carries an asset named by <paramref name="assetName"/>.</summary>
    public static ReleaseInfo? PickLatest(IEnumerable<ReleaseInfo> releases, Func<SemVer, string> assetName) =>
        releases.Where(r => r.Asset(assetName(r.Version)) != null).OrderByDescending(r => r.Version).FirstOrDefault();

    /// <summary>"sha256:&lt;64 hex&gt;" → lowercase hex; anything else → null.</summary>
    public static string? ParseDigest(string? digest)
    {
        if (string.IsNullOrWhiteSpace(digest)) return null;
        var m = Regex.Match(digest.Trim(), "^sha256:([0-9a-fA-F]{64})$");
        return m.Success ? m.Groups[1].Value.ToLowerInvariant() : null;
    }

    /// <summary>Looks up <paramref name="name"/> in a SHA256SUMS.txt body ("&lt;hex&gt;  &lt;name&gt;", also "&lt;hex&gt; *&lt;name&gt;").</summary>
    public static string? FindInSums(string sums, string name)
    {
        foreach (var raw in sums.Split('\n'))
        {
            var m = Regex.Match(raw.Trim(), @"^([0-9a-fA-F]{64})\s+\*?(.+)$");
            if (m.Success && string.Equals(m.Groups[2].Value.Trim(), name, StringComparison.Ordinal))
                return m.Groups[1].Value.ToLowerInvariant();
        }
        return null;
    }

    private static string Str(JsonElement e, string name) =>
        e.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() ?? "" : "";
}
