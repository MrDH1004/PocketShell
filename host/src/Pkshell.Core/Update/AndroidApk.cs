using System.Security.Cryptography;
using System.Text.RegularExpressions;

namespace Pkshell.Core.Update;

/// <summary>
/// The phone app shipped with the host (release zip: <c>android\pocketshell-android-v&lt;version&gt;.apk</c>), so a paired
/// phone can update over the LAN without GitHub (DESIGN.md §11.4). The newest version in the folder wins.
/// </summary>
public sealed partial record AndroidApk(string Path, string Name, string Version, long Size, string Sha256)
{
    public const string Folder = "android";

    private static readonly object Gate = new();
    private static (string Path, DateTime Written, string Sha)? _shaCache;

    /// <summary>The newest APK under <paramref name="baseDir"/>\android, or null when there is none.</summary>
    public static AndroidApk? Find(string baseDir)
    {
        var dir = System.IO.Path.Combine(baseDir, Folder);
        if (!Directory.Exists(dir)) return null;
        (FileInfo File, SemVer Version)? best = null;
        foreach (var f in new DirectoryInfo(dir).EnumerateFiles("*.apk"))
        {
            var m = NameRegex().Match(f.Name);
            if (!m.Success || !SemVer.TryParse(m.Groups[1].Value, out var v)) continue;
            if (best == null || v > best.Value.Version) best = (f, v);
        }
        if (best is not { } b) return null;
        return new AndroidApk(b.File.FullName, b.File.Name, b.Version.ToString(), b.File.Length, Sha256Of(b.File));
    }

    /// <summary>SHA-256 hex of the file, cached per path + write time (the APK is several MB and rarely changes).</summary>
    private static string Sha256Of(FileInfo f)
    {
        lock (Gate)
        {
            if (_shaCache is { } c && c.Path == f.FullName && c.Written == f.LastWriteTimeUtc) return c.Sha;
        }
        string sha;
        using (var s = f.OpenRead()) sha = Convert.ToHexString(SHA256.HashData(s)).ToLowerInvariant();
        lock (Gate) _shaCache = (f.FullName, f.LastWriteTimeUtc, sha);
        return sha;
    }

    [GeneratedRegex(@"^pocketshell-android-v(.+)\.apk$", RegexOptions.IgnoreCase)]
    private static partial Regex NameRegex();
}
