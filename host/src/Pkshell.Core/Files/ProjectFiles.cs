namespace Pkshell.Core.Files;

/// <summary>One directory entry for the phone's file browser.</summary>
public sealed record FileEntry(string Name, bool Dir, long Size, string Modified);

/// <summary>Upload target exists and overwrite was not asked for.</summary>
public sealed class FileExistsException(string name) : IOException($"이미 있는 파일입니다: {name}");

/// <summary>A starting point of the phone's PC browser: a drive or a well-known folder.</summary>
public sealed record Place(string Name, string Path, string Kind);

/// <summary>Directory listing.</summary>
public sealed record FileListing(string Path, List<FileEntry> Entries);

/// <summary>
/// File access for the phone's project file browser (list / download / upload). Paths are absolute PC paths; the
/// phone starts at the session folder. A paired device already has a full shell, so there is no extra sandbox here,
/// only input validation (absolute paths, plain file names, no silent overwrite).
/// </summary>
public static class ProjectFiles
{
    /// <summary>Upload size limit.</summary>
    public const long MaxUploadBytes = 1L << 30;

    /// <summary>Full path when <paramref name="path"/> is an absolute local path, else null.</summary>
    public static string? Normalize(string? path)
    {
        if (string.IsNullOrWhiteSpace(path) || !Path.IsPathFullyQualified(path)) return null;
        try { return Path.GetFullPath(path); }
        catch (Exception ex) when (ex is ArgumentException or NotSupportedException or PathTooLongException) { return null; }
    }

    /// <summary>
    /// "내 PC" for the phone's PC browser: the user folder, desktop, documents, downloads, the project folders set in
    /// the tray app (kind "folder"), then the ready drives (kind "drive"). Missing folders and duplicates are left out.
    /// </summary>
    public static List<Place> Places(IEnumerable<string> projectRoots)
    {
        var list = new List<Place>();
        void Add(string name, string? path, string kind)
        {
            if (string.IsNullOrWhiteSpace(path)) return;
            if (list.Any(p => string.Equals(System.IO.Path.TrimEndingDirectorySeparator(p.Path), System.IO.Path.TrimEndingDirectorySeparator(path), StringComparison.OrdinalIgnoreCase))) return;
            list.Add(new Place(name, path, kind));
        }
        void Folder(string name, string? path)
        {
            if (!string.IsNullOrWhiteSpace(path) && Directory.Exists(path)) Add(name, path, "folder");
        }
        var home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        Folder("사용자 폴더", home);
        Folder("바탕 화면", Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory));
        Folder("문서", Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments));
        if (!string.IsNullOrEmpty(home)) Folder("다운로드", System.IO.Path.Combine(home, "Downloads"));
        foreach (var root in projectRoots)
        {
            var name = System.IO.Path.GetFileName(System.IO.Path.TrimEndingDirectorySeparator(root));
            Folder("프로젝트 폴더: " + (string.IsNullOrEmpty(name) ? root : name), root);
        }
        foreach (var d in DriveInfo.GetDrives())
        {
            try
            {
                if (d.DriveType is DriveType.CDRom or DriveType.NoRootDirectory or DriveType.Unknown || !d.IsReady) continue;
                var label = d.VolumeLabel;
                var letter = d.Name.TrimEnd('\\');
                Add(string.IsNullOrWhiteSpace(label) ? letter : $"{letter} {label}", d.Name, "drive");
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { /* not ready after all */ }
        }
        return list;
    }

    /// <summary>Folders first, then files; each by name (case-insensitive). Unreadable entries are skipped.</summary>
    public static FileListing List(string dir)
    {
        var info = new DirectoryInfo(dir);
        if (!info.Exists) throw new DirectoryNotFoundException(dir);
        var entries = new List<FileEntry>();
        foreach (var e in info.EnumerateFileSystemInfos("*", new EnumerationOptions { IgnoreInaccessible = true, AttributesToSkip = 0 }))
        {
            try
            {
                bool isDir = (e.Attributes & FileAttributes.Directory) != 0;
                long size = isDir ? 0 : ((FileInfo)e).Length;
                entries.Add(new FileEntry(e.Name, isDir, size, e.LastWriteTimeUtc.ToString("o")));
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { /* vanished or locked */ }
        }
        entries.Sort((a, b) => a.Dir != b.Dir ? (a.Dir ? -1 : 1) : StringComparer.OrdinalIgnoreCase.Compare(a.Name, b.Name));
        return new FileListing(info.FullName, entries);
    }

    /// <summary>Error text when <paramref name="name"/> is not a plain file name, else null.</summary>
    public static string? ValidateName(string? name)
    {
        if (string.IsNullOrWhiteSpace(name)) return "파일 이름이 없습니다";
        if (name is "." or ".." || name.IndexOfAny(Path.GetInvalidFileNameChars()) >= 0) return $"사용할 수 없는 파일 이름입니다: {name}";
        if (name.EndsWith(' ') || name.EndsWith('.')) return $"파일 이름이 공백이나 점으로 끝납니다: {name}";
        return null;
    }

    /// <summary>
    /// Writes <paramref name="body"/> to <paramref name="dir"/>\<paramref name="name"/> through a temp file (a broken
    /// upload leaves nothing behind). Returns the final path. Throws <see cref="IOException"/> (<see cref="FileExistsException"/>) when the file exists
    /// and <paramref name="overwrite"/> is false, <see cref="InvalidDataException"/> past <see cref="MaxUploadBytes"/>.
    /// </summary>
    public static async Task<string> SaveAsync(string dir, string name, Stream body, bool overwrite, CancellationToken ct)
    {
        if (!Directory.Exists(dir)) throw new DirectoryNotFoundException(dir);
        var target = Path.Combine(dir, name);
        if (!overwrite && File.Exists(target)) throw new FileExistsException(name);
        var tmp = Path.Combine(dir, $".{name}.{Guid.NewGuid():N}.upload");
        try
        {
            await using (var fs = new FileStream(tmp, FileMode.CreateNew, FileAccess.Write, FileShare.None, 81920, useAsync: true))
            {
                var buf = new byte[81920];
                long total = 0;
                int n;
                while ((n = await body.ReadAsync(buf, ct)) > 0)
                {
                    total += n;
                    if (total > MaxUploadBytes) throw new InvalidDataException($"{MaxUploadBytes >> 20} MB보다 큰 파일은 올릴 수 없습니다");
                    await fs.WriteAsync(buf.AsMemory(0, n), ct);
                }
            }
            File.Move(tmp, target, overwrite);
            return target;
        }
        finally
        {
            if (File.Exists(tmp)) File.Delete(tmp);
        }
    }
}
