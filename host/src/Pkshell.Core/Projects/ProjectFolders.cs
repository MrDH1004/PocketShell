using System.Runtime.InteropServices;
using Pkshell.Core.Util;

namespace Pkshell.Core.Projects;

/// <summary>
/// Deleting a session's project folder from the phone. Only a folder directly under a registered project root
/// (what "새 프로젝트" creates) qualifies, and it goes to the Recycle Bin, so a wrong tap can be undone on the PC.
/// </summary>
public static class ProjectFolders
{
    /// <returns>null when <paramref name="folder"/> may be deleted, else the reason (Korean, shown on the phone).</returns>
    public static string? WhyNotDeletable(IReadOnlyCollection<string> roots, string? folder)
    {
        if (string.IsNullOrWhiteSpace(folder)) return "이 세션의 폴더를 알 수 없습니다";
        var full = Normalize(folder);
        if (full == null) return "폴더 경로가 올바르지 않습니다";
        if (roots.Any(r => Same(r, full))) return "등록된 프로젝트 폴더 자체는 지울 수 없습니다";
        var parent = Path.GetDirectoryName(full);
        if (parent == null || !roots.Any(r => Same(r, parent)))
            return "PC 앱의 '프로젝트 폴더'에 등록한 폴더 바로 아래에 있는 폴더만 지울 수 있습니다";
        if (!Directory.Exists(full)) return "폴더가 이미 없습니다";
        try
        {
            if ((File.GetAttributes(full) & FileAttributes.ReparsePoint) != 0) return "다른 곳을 가리키는 링크 폴더는 지울 수 없습니다";
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { return "폴더를 읽을 수 없습니다: " + ex.Message; }
        return null;
    }

    /// <summary>True when <paramref name="path"/> is <paramref name="folder"/> or somewhere below it.</summary>
    public static bool IsInside(string folder, string? path)
    {
        var f = Normalize(folder);
        var p = Normalize(path);
        if (f == null || p == null) return false;
        return p.Equals(f, StringComparison.OrdinalIgnoreCase)
            || p.StartsWith(f + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase);
    }

    /// <summary>Absolute path without a trailing separator ("C:\" stays), or null when it is not an absolute path.</summary>
    public static string? Normalize(string? path)
    {
        if (string.IsNullOrWhiteSpace(path) || !Path.IsPathFullyQualified(path)) return null;
        try
        {
            var full = Path.GetFullPath(path);
            var root = Path.GetPathRoot(full) ?? "";
            return full.Length > root.Length ? full.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar) : full;
        }
        catch (Exception ex) when (ex is ArgumentException or NotSupportedException or PathTooLongException) { return null; }
    }

    private static bool Same(string a, string b)
    {
        var x = Normalize(a);
        var y = Normalize(b);
        return x != null && y != null && x.Equals(y, StringComparison.OrdinalIgnoreCase);
    }

    /// <summary>
    /// Moves the folder to the Recycle Bin, retrying for a few seconds (a shell that was just terminated may still hold
    /// it). Returns null on success, else the reason.
    /// </summary>
    public static string? Recycle(string folder)
    {
        string last = "";
        for (int i = 0; i < 6; i++)
        {
            if (i > 0) Thread.Sleep(500);
            if (!Directory.Exists(folder)) return null;
            last = RecycleBin.Send(folder) ?? "";
            if (last.Length == 0 && !Directory.Exists(folder)) return null;
        }
        return last.Length > 0 ? last : "폴더가 아직 남아 있습니다 (다른 프로그램이 쓰고 있을 수 있습니다)";
    }
}

/// <summary>SHFileOperation(FO_DELETE, FOF_ALLOWUNDO): the shell's own "move to Recycle Bin", without any dialog.</summary>
public static class RecycleBin
{
    // shellapi.h
    private const uint FO_DELETE = 0x0003;
    private const ushort FOF_SILENT = 0x0004;
    private const ushort FOF_NOCONFIRMATION = 0x0010;
    private const ushort FOF_ALLOWUNDO = 0x0040;
    private const ushort FOF_NOERRORUI = 0x0400;

    // 64-bit layout (default packing). The 32-bit SDK header packs this struct to 1 byte; the host ships as win-x64 only.
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct SHFILEOPSTRUCT
    {
        public IntPtr hwnd;
        public uint wFunc;
        [MarshalAs(UnmanagedType.LPWStr)] public string pFrom;
        [MarshalAs(UnmanagedType.LPWStr)] public string? pTo;
        public ushort fFlags;
        [MarshalAs(UnmanagedType.Bool)] public bool fAnyOperationsAborted;
        public IntPtr hNameMappings;
        [MarshalAs(UnmanagedType.LPWStr)] public string? lpszProgressTitle;
    }

    [DllImport("shell32.dll", CharSet = CharSet.Unicode, EntryPoint = "SHFileOperationW")]
    private static extern int SHFileOperation(ref SHFILEOPSTRUCT op);

    /// <summary>Returns null on success, else the reason. A drive without a Recycle Bin (network share) deletes for good.</summary>
    public static string? Send(string path)
    {
        if (!Environment.Is64BitProcess) return "이 PC 앱 빌드에서는 휴지통으로 보낼 수 없습니다";
        var op = new SHFILEOPSTRUCT
        {
            wFunc = FO_DELETE,
            pFrom = path + '\0', // the list ends with a second NUL, which the marshaller appends
            fFlags = FOF_ALLOWUNDO | FOF_NOCONFIRMATION | FOF_SILENT | FOF_NOERRORUI,
        };
        int rc = SHFileOperation(ref op);
        if (rc != 0)
        {
            Log.Warn($"recycle {path}: SHFileOperation returned 0x{rc:X}");
            return $"휴지통으로 옮기지 못했습니다 (오류 0x{rc:X}). 폴더 안의 파일을 다른 프로그램이 쓰고 있을 수 있습니다";
        }
        return op.fAnyOperationsAborted ? "휴지통으로 옮기는 작업이 중단되었습니다" : null;
    }
}
