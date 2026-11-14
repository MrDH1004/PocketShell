namespace Pkshell.Core.Files;

/// <summary>Body of POST /api/files/op.</summary>
public sealed class FileOpRequest
{
    /// <summary>"copy" | "move" | "delete" | "rename" | "mkdir".</summary>
    public string? Op { get; set; }
    /// <summary>Absolute paths the operation works on (copy, move, delete; rename takes the first).</summary>
    public List<string>? Paths { get; set; }
    /// <summary>Target folder (copy, move, mkdir).</summary>
    public string? Dest { get; set; }
    /// <summary>New name (rename, mkdir; mkdir without one makes "새 폴더").</summary>
    public string? Name { get; set; }
}

/// <summary>What an operation did: finished items, the paths they now have, and one message per failed item.</summary>
public sealed record FileOpResult(int Done, List<string> Paths, List<string> Errors);

/// <summary>
/// The phone file browser's Explorer basics: copy, move, delete (to the Recycle Bin), rename, new folder.
/// Nothing is overwritten: a name already taken gets " - 복사본" (copy) or " (2)" (move, new folder), as in Explorer.
/// </summary>
public static class FileOps
{
    public const string NewFolderName = "새 폴더";

    public static FileOpResult Copy(IEnumerable<string> sources, string destDir) => Transfer(sources, destDir, move: false);

    public static FileOpResult Move(IEnumerable<string> sources, string destDir) => Transfer(sources, destDir, move: true);

    private static FileOpResult Transfer(IEnumerable<string> sources, string destDir, bool move)
    {
        var done = 0;
        var paths = new List<string>();
        var errors = new List<string>();
        if (!Directory.Exists(destDir)) throw new DirectoryNotFoundException(destDir);
        foreach (var raw in sources)
        {
            var src = Path.TrimEndingDirectorySeparator(raw);
            var name = Path.GetFileName(src);
            try
            {
                bool isDir = Directory.Exists(src);
                if (!isDir && !File.Exists(src)) { errors.Add($"없습니다: {src}"); continue; }
                if (string.IsNullOrEmpty(name)) { errors.Add($"드라이브는 옮기거나 복사할 수 없습니다: {src}"); continue; }
                if (isDir && IsSameOrInside(destDir, src)) { errors.Add($"폴더를 자기 안으로 넣을 수 없습니다: {name}"); continue; }
                var sameFolder = string.Equals(Path.TrimEndingDirectorySeparator(Path.GetDirectoryName(src) ?? ""),
                    Path.TrimEndingDirectorySeparator(destDir), StringComparison.OrdinalIgnoreCase);
                if (move && sameFolder) { done++; paths.Add(src); continue; } // already there
                var target = Path.Combine(destDir, UniqueName(destDir, name, copy: !move));
                if (move) MovePath(src, target, isDir);
                else if (isDir) CopyDir(src, target);
                else File.Copy(src, target);
                done++;
                paths.Add(target);
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                errors.Add($"{name}: {ex.Message}");
            }
        }
        return new FileOpResult(done, paths, errors);
    }

    /// <summary>Each path to the Recycle Bin through <paramref name="recycle"/> (returns an error text or null).</summary>
    public static FileOpResult Delete(IEnumerable<string> paths, Func<string, string?> recycle)
    {
        var done = 0;
        var gone = new List<string>();
        var errors = new List<string>();
        foreach (var raw in paths)
        {
            var p = Path.TrimEndingDirectorySeparator(raw);
            if (string.IsNullOrEmpty(Path.GetFileName(p))) { errors.Add($"드라이브는 삭제할 수 없습니다: {raw}"); continue; }
            if (!File.Exists(p) && !Directory.Exists(p)) { errors.Add($"없습니다: {p}"); continue; }
            var err = recycle(p);
            if (err != null) errors.Add($"{Path.GetFileName(p)}: {err}");
            else { done++; gone.Add(p); }
        }
        return new FileOpResult(done, gone, errors);
    }

    /// <summary>Renames a file or folder in place; returns the new path. Throws IOException when the name is taken.</summary>
    public static string Rename(string path, string newName)
    {
        var src = Path.TrimEndingDirectorySeparator(path);
        var dir = Path.GetDirectoryName(src) ?? throw new IOException("드라이브 이름은 바꿀 수 없습니다");
        var target = Path.Combine(dir, newName);
        bool isDir = Directory.Exists(src);
        if (!isDir && !File.Exists(src)) throw new FileNotFoundException($"없습니다: {src}");
        if (string.Equals(src, target, StringComparison.Ordinal)) return target;
        // Only the case changes: Windows sees the same name, so go through a temporary one.
        if (string.Equals(src, target, StringComparison.OrdinalIgnoreCase))
        {
            var tmp = Path.Combine(dir, $".{Guid.NewGuid():N}.rename");
            MovePath(src, tmp, isDir);
            MovePath(tmp, target, isDir);
            return target;
        }
        if (File.Exists(target) || Directory.Exists(target)) throw new FileExistsException(newName);
        MovePath(src, target, isDir);
        return target;
    }

    /// <summary>Creates a folder (default "새 폴더", made unique) and returns its path.</summary>
    public static string MakeDir(string dir, string? name)
    {
        if (!Directory.Exists(dir)) throw new DirectoryNotFoundException(dir);
        string target;
        if (string.IsNullOrWhiteSpace(name)) target = Path.Combine(dir, UniqueName(dir, NewFolderName, copy: false));
        else
        {
            target = Path.Combine(dir, name);
            if (File.Exists(target) || Directory.Exists(target)) throw new FileExistsException(name);
        }
        Directory.CreateDirectory(target);
        return target;
    }

    /// <summary>
    /// <paramref name="name"/> when free in <paramref name="dir"/>, else Explorer's next name: "a - 복사본.txt",
    /// "a - 복사본 (2).txt" for a copy, "a (2).txt" otherwise. A folder keeps dots in its name as they are.
    /// </summary>
    public static string UniqueName(string dir, string name, bool copy)
    {
        bool Taken(string n) => File.Exists(Path.Combine(dir, n)) || Directory.Exists(Path.Combine(dir, n));
        if (!Taken(name)) return name;
        var isDir = Directory.Exists(Path.Combine(dir, name));
        var ext = isDir ? "" : Path.GetExtension(name);
        var stem = name[..^ext.Length];
        var baseName = copy ? $"{stem} - 복사본" : stem;
        if (copy && !Taken(baseName + ext)) return baseName + ext;
        for (var i = 2; ; i++)
        {
            var n = $"{baseName} ({i}){ext}";
            if (!Taken(n)) return n;
        }
    }

    private static bool IsSameOrInside(string path, string folder)
    {
        var p = Path.TrimEndingDirectorySeparator(Path.GetFullPath(path)) + Path.DirectorySeparatorChar;
        var f = Path.TrimEndingDirectorySeparator(Path.GetFullPath(folder)) + Path.DirectorySeparatorChar;
        return p.StartsWith(f, StringComparison.OrdinalIgnoreCase);
    }

    private static void MovePath(string src, string target, bool isDir)
    {
        if (!isDir) { File.Move(src, target); return; }
        if (string.Equals(Path.GetPathRoot(src), Path.GetPathRoot(target), StringComparison.OrdinalIgnoreCase))
        {
            Directory.Move(src, target);
            return;
        }
        CopyDir(src, target); // another drive: Directory.Move cannot cross volumes
        Directory.Delete(src, recursive: true);
    }

    private static void CopyDir(string src, string target)
    {
        Directory.CreateDirectory(target);
        foreach (var f in Directory.EnumerateFiles(src)) File.Copy(f, Path.Combine(target, Path.GetFileName(f)));
        foreach (var d in Directory.EnumerateDirectories(src))
        {
            // A junction or symlinked folder may point back up the tree (endless copy): leave it out.
            if ((File.GetAttributes(d) & FileAttributes.ReparsePoint) != 0) continue;
            CopyDir(d, Path.Combine(target, Path.GetFileName(d)));
        }
    }
}
