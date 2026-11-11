using Pkshell.Core.Util;

namespace Pkshell.Core.Update;

/// <summary>
/// Install-dir file swap for self-update (DESIGN §11.3): every file present in staging replaces its counterpart,
/// whose old copy is renamed to <c>*.old</c> first (Windows allows renaming a running exe/dll, not deleting it).
/// Files not present in staging are left alone, so <see cref="Rollback"/> restores the exact previous set.
/// The swapped relative paths are recorded in <see cref="ManifestName"/> (itself swapped the same way), and
/// <see cref="CleanupOld"/> only deletes backups listed there, never unrelated <c>*.old</c> files.
/// </summary>
public sealed class InstallTransaction
{
    public const string OldSuffix = ".old";
    public const string ManifestName = "pocketshell.update-files.txt";
    private readonly List<(string Target, bool HadOld)> _ops = new();

    public IReadOnlyList<string> Targets => _ops.Select(o => o.Target).ToList();

    private InstallTransaction() { }

    public static InstallTransaction Apply(string stagingDir, string installDir)
    {
        var tx = new InstallTransaction();
        try
        {
            var rels = Directory.EnumerateFiles(stagingDir, "*", SearchOption.AllDirectories)
                .Select(f => Path.GetRelativePath(stagingDir, f))
                .Where(r => !string.Equals(r, ManifestName, StringComparison.OrdinalIgnoreCase))
                .ToList();
            tx.Swap(installDir, ManifestName, t => File.WriteAllLines(t, rels.Append(ManifestName)));
            foreach (var rel in rels) tx.Swap(installDir, rel, t => File.Copy(Path.Combine(stagingDir, rel), t, overwrite: false));
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            tx.Rollback();
            throw;
        }
        return tx;
    }

    private void Swap(string installDir, string rel, Action<string> write)
    {
        var target = Path.Combine(installDir, rel);
        Directory.CreateDirectory(Path.GetDirectoryName(target)!);
        bool hadOld = File.Exists(target);
        if (hadOld)
        {
            var old = target + OldSuffix;
            if (File.Exists(old)) RemoveLeftover(old); // leftover from an earlier update whose cleanup failed
            File.Move(target, old);
        }
        _ops.Add((target, hadOld));
        write(target);
    }

    /// <summary>Marker between "X.old" and a random suffix for leftovers moved aside: "X.old.stale-&lt;hex&gt;".</summary>
    public const string StaleMarker = ".stale-";

    /// <summary>
    /// Deletes a leftover <c>X.old</c>. A process still running the old build (e.g. the tray from before the previous
    /// update) keeps it loaded: Windows refuses the delete but allows a rename, so it is moved aside to
    /// <c>X.old.stale-&lt;hex&gt;</c> and <see cref="CleanupOld"/> removes it once nothing holds it.
    /// </summary>
    private static void RemoveLeftover(string old)
    {
        if (TryDelete(old)) return;
        var aside = old + StaleMarker + Guid.NewGuid().ToString("N")[..8];
        File.Move(old, aside);
        Log.Warn($"update: moved the in-use {Path.GetFileName(old)} aside to {Path.GetFileName(aside)}");
    }

    /// <summary>
    /// Removes the copied files and renames every <c>*.old</c> back. Best effort per file; returns failures. A target whose
    /// backup has gone missing is kept as is (a working new file beats no file) and reported as a failure.
    /// </summary>
    public List<string> Rollback()
    {
        var failures = new List<string>();
        for (int i = _ops.Count - 1; i >= 0; i--)
        {
            var (target, hadOld) = _ops[i];
            if (hadOld && !File.Exists(target + OldSuffix))
            {
                failures.Add($"{target}: backup {Path.GetFileName(target)}{OldSuffix} is missing; kept the current file");
                continue;
            }
            try
            {
                if (File.Exists(target)) File.Delete(target);
                if (hadOld) File.Move(target + OldSuffix, target);
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                failures.Add($"{target}: {ex.Message}");
            }
        }
        _ops.Clear();
        return failures;
    }

    /// <summary>
    /// Deletes the <c>X.old</c> backups of the files listed in <see cref="ManifestName"/> whose counterpart <c>X</c> exists
    /// (i.e. left by a completed update). Other <c>*.old</c> files are never touched; without a manifest nothing is deleted.
    /// Only call this once the update is committed (no process can roll it back any more). Files still locked by an
    /// exiting process are skipped. Returns (deleted, remaining).
    /// </summary>
    public static (int Deleted, int Remaining) CleanupOld(string installDir)
    {
        int deleted = 0, remaining = 0;
        var manifest = Path.Combine(installDir, ManifestName);
        if (!File.Exists(manifest)) return (0, 0);
        var root = Path.GetFullPath(installDir).TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
        foreach (var rel in File.ReadAllLines(manifest).Select(l => l.Trim()).Where(l => l.Length > 0).Distinct(StringComparer.OrdinalIgnoreCase))
        {
            var target = Path.GetFullPath(Path.Combine(installDir, rel));
            if (!target.StartsWith(root, StringComparison.OrdinalIgnoreCase)) continue;
            var old = target + OldSuffix;
            if (!File.Exists(target) || !File.Exists(old)) continue;
            if (TryDelete(old)) deleted++;
            else remaining++;
        }
        // Moved-aside leftovers are never needed for a rollback; they may belong to files a later update no longer
        // ships (not in the manifest any more), so sweep the whole install dir for the marker.
        foreach (var stale in Directory.EnumerateFiles(installDir, "*" + OldSuffix + StaleMarker + "*", SearchOption.AllDirectories))
        {
            if (TryDelete(stale)) deleted++;
            else remaining++;
        }
        return (deleted, remaining);
    }

    /// <summary>
    /// Deletes program files an earlier version shipped and the current one no longer does: libraries and runtime
    /// configs in the install folder and APKs under android\ that the manifest does not list (a renamed library, the
    /// previous releases' APKs). Nothing is touched without a manifest. Returns how many were deleted.
    /// </summary>
    public static int RemoveObsolete(string installDir)
    {
        var manifest = Path.Combine(installDir, ManifestName);
        if (!File.Exists(manifest)) return 0;
        var listed = new HashSet<string>(
            File.ReadAllLines(manifest).Select(l => l.Trim().Replace('/', Path.DirectorySeparatorChar)).Where(l => l.Length > 0),
            StringComparer.OrdinalIgnoreCase);
        var candidates = new List<string>();
        foreach (var pattern in new[] { "*.dll", "*.deps.json", "*.runtimeconfig.json" })
            candidates.AddRange(Directory.EnumerateFiles(installDir, pattern, SearchOption.TopDirectoryOnly));
        var apkDir = Path.Combine(installDir, "android");
        if (Directory.Exists(apkDir)) candidates.AddRange(Directory.EnumerateFiles(apkDir, "*.apk", SearchOption.TopDirectoryOnly));
        int deleted = 0;
        foreach (var file in candidates)
        {
            if (listed.Contains(Path.GetRelativePath(installDir, file))) continue;
            if (TryDelete(file)) deleted++;
        }
        return deleted;
    }

    private static bool TryDelete(string path)
    {
        try
        {
            File.Delete(path);
            return true;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            Log.Warn($"update cleanup: cannot delete {path} yet: {ex.Message}");
            return false;
        }
    }
}
