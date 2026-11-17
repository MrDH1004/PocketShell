using System.ComponentModel;
using System.Diagnostics;
using System.Globalization;
using System.Text;

namespace Pkshell.Core.Files;

/// <summary>One changed file of a working tree (git status).</summary>
public sealed class GitFileDto
{
    /// <summary>Path relative to the repository root, '/' separated.</summary>
    public string Path { get; set; } = "";
    /// <summary>Renamed from (relative), else null.</summary>
    public string? From { get; set; }
    /// <summary>"M" modified, "A" added (staged new), "D" deleted, "R" renamed, "?" untracked, "U" conflict, "T" type change.</summary>
    public string Status { get; set; } = "M";
    /// <summary>Some of the change is staged (index).</summary>
    public bool Staged { get; set; }
    /// <summary>Lines added / removed against HEAD (null for binary files or untracked ones).</summary>
    public int? Added { get; set; }
    public int? Removed { get; set; }
}

public sealed class GitStatusDto
{
    public string Root { get; set; } = "";
    public string Branch { get; set; } = "";
    public string? Upstream { get; set; }
    public int Ahead { get; set; }
    public int Behind { get; set; }
    public List<GitFileDto> Files { get; set; } = new();
    /// <summary>More files than <see cref="GitChanges.MaxFiles"/>: the rest are left out.</summary>
    public bool Truncated { get; set; }
}

public sealed class GitDiffDto
{
    public string Path { get; set; } = "";
    public string Diff { get; set; } = "";
    public bool Binary { get; set; }
    public bool Truncated { get; set; }
}

public sealed class GitException : Exception
{
    public int Status { get; }
    public GitException(int status, string message) : base(message) { Status = status; }
}

/// <summary>
/// Read-only view of a project's uncommitted changes for the phone (git status / diff). Never writes: optional locks
/// are off so a running Claude/Codex in the same repo is not blocked by an index.lock.
/// </summary>
public static class GitChanges
{
    public const int MaxFiles = 500;
    public const int MaxDiffChars = 400_000;
    private const int MaxUntrackedBytes = 256 * 1024;
    private static readonly TimeSpan Timeout = TimeSpan.FromSeconds(15);

    public static GitStatusDto Status(string dir)
    {
        var root = Root(dir);
        var status = Run(root, "status", "--porcelain=v1", "-b", "-z", "--untracked-files=all");
        var dto = ParseStatus(status.Output);
        dto.Root = root;
        // Line counts: staged + unstaged against HEAD (a repository without commits: against the empty index).
        var counts = new Dictionary<string, (int? A, int? R)>(StringComparer.Ordinal);
        bool hasHead = TryRun(root, out _, "rev-parse", "--verify", "-q", "HEAD");
        var numstat = hasHead ? Run(root, "diff", "HEAD", "--numstat", "-z", "-M").Output : Run(root, "diff", "--cached", "--numstat", "-z").Output;
        foreach (var (path, a, r) in ParseNumstat(numstat)) counts[path] = (a, r);
        foreach (var f in dto.Files)
            if (counts.TryGetValue(f.Path, out var c)) { f.Added = c.A; f.Removed = c.R; }
        return dto;
    }

    public static GitDiffDto Diff(string dir, string file, bool untracked)
    {
        var root = Root(dir);
        var rel = file.Replace('\\', '/').TrimStart('/');
        var full = System.IO.Path.GetFullPath(System.IO.Path.Combine(root, rel));
        if (!full.StartsWith(root.TrimEnd('\\') + "\\", StringComparison.OrdinalIgnoreCase)) throw new GitException(400, "저장소 밖의 파일입니다");
        if (untracked) return Untracked(full, rel);
        bool hasHead = TryRun(root, out _, "rev-parse", "--verify", "-q", "HEAD");
        var r = hasHead ? Run(root, "diff", "HEAD", "-M", "--", rel) : Run(root, "diff", "--cached", "--", rel);
        var text = r.Output;
        return new GitDiffDto
        {
            Path = rel,
            Diff = text.Length > MaxDiffChars ? text[..MaxDiffChars] : text,
            Truncated = r.Truncated || text.Length > MaxDiffChars,
            Binary = text.Contains("\nBinary files ", StringComparison.Ordinal) || text.StartsWith("Binary files ", StringComparison.Ordinal),
        };
    }

    /// <summary>A new file git does not track yet: shown as all added lines.</summary>
    private static GitDiffDto Untracked(string full, string rel)
    {
        if (!File.Exists(full)) throw new GitException(404, "파일이 없습니다: " + rel);
        var info = new FileInfo(full);
        var len = (int)Math.Min(info.Length, MaxUntrackedBytes);
        var bytes = new byte[len];
        using (var fs = new FileStream(full, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete))
        {
            int read = 0;
            while (read < len)
            {
                int n = fs.Read(bytes, read, len - read);
                if (n <= 0) break;
                read += n;
            }
            len = read;
        }
        var dto = new GitDiffDto { Path = rel, Truncated = info.Length > MaxUntrackedBytes };
        if (Array.IndexOf(bytes, (byte)0, 0, len) >= 0)
        {
            dto.Binary = true;
            dto.Diff = $"new file {rel}\nBinary file ({info.Length.ToString(CultureInfo.InvariantCulture)} bytes)\n";
            return dto;
        }
        var text = new UTF8Encoding(false).GetString(bytes, 0, len).Replace("\r\n", "\n");
        var lines = text.Split('\n');
        if (lines.Length > 0 && lines[^1].Length == 0) lines = lines[..^1];
        var sb = new StringBuilder();
        sb.Append("new file ").Append(rel).Append('\n');
        sb.Append("@@ -0,0 +1,").Append(lines.Length.ToString(CultureInfo.InvariantCulture)).Append(" @@\n");
        foreach (var l in lines) sb.Append('+').Append(l).Append('\n');
        dto.Diff = sb.ToString();
        return dto;
    }

    /// <summary>The repository's top folder (Windows form), or 404 when the folder is not in a git repository.</summary>
    private static string Root(string dir)
    {
        if (!Directory.Exists(dir)) throw new GitException(404, "폴더가 없습니다: " + dir);
        if (!TryRun(dir, out var top, "rev-parse", "--show-toplevel")) throw new GitException(404, "Git 저장소가 아닙니다: " + dir);
        var root = top.Trim().Replace('/', '\\');
        return System.IO.Path.GetFullPath(root);
    }

    // ---- parsing (pure, unit-tested)

    /// <summary>`git status --porcelain=v1 -b -z` output.</summary>
    public static GitStatusDto ParseStatus(string z)
    {
        var dto = new GitStatusDto();
        var parts = z.Split('\0');
        for (int i = 0; i < parts.Length; i++)
        {
            var e = parts[i];
            if (e.Length == 0) continue;
            if (e.StartsWith("## ", StringComparison.Ordinal))
            {
                ParseBranch(e[3..], dto);
                continue;
            }
            if (e.Length < 4) continue;
            char x = e[0], y = e[1];
            var path = e[3..];
            string? from = null;
            if (x == 'R' || x == 'C' || y == 'R' || y == 'C')
            {
                // -z: the original path follows as its own entry.
                if (i + 1 < parts.Length) from = parts[++i];
            }
            if (dto.Files.Count >= MaxFiles) { dto.Truncated = true; continue; }
            dto.Files.Add(new GitFileDto { Path = path, From = from, Status = StatusOf(x, y), Staged = x != ' ' && x != '?' && x != '!' });
        }
        return dto;
    }

    private static string StatusOf(char x, char y)
    {
        if (x == '?' && y == '?') return "?";
        if (x == 'U' || y == 'U' || (x == 'A' && y == 'A') || (x == 'D' && y == 'D')) return "U";
        if (x == 'R' || y == 'R' || x == 'C' || y == 'C') return "R";
        if (x == 'D' || y == 'D') return "D";
        if (x == 'A') return "A";
        if (x == 'T' || y == 'T') return "T";
        return "M";
    }

    /// <summary>"main...origin/main [ahead 1, behind 2]", "No commits yet on main", "HEAD (no branch)".</summary>
    private static void ParseBranch(string s, GitStatusDto dto)
    {
        const string noCommits = "No commits yet on ";
        if (s.StartsWith(noCommits, StringComparison.Ordinal)) { dto.Branch = s[noCommits.Length..].Trim(); return; }
        var bracket = s.IndexOf(" [", StringComparison.Ordinal);
        var head = bracket >= 0 ? s[..bracket] : s;
        if (bracket >= 0)
        {
            foreach (var part in s[(bracket + 2)..].TrimEnd(']').Split(", "))
            {
                var kv = part.Split(' ');
                if (kv.Length == 2 && int.TryParse(kv[1], NumberStyles.Integer, CultureInfo.InvariantCulture, out var n))
                {
                    if (kv[0] == "ahead") dto.Ahead = n;
                    else if (kv[0] == "behind") dto.Behind = n;
                }
            }
        }
        var dots = head.IndexOf("...", StringComparison.Ordinal);
        dto.Branch = (dots >= 0 ? head[..dots] : head).Trim();
        dto.Upstream = dots >= 0 ? head[(dots + 3)..].Trim() : null;
    }

    /// <summary>`git diff --numstat -z`: "a\tr\tpath\0" or, for a rename, "a\tr\t\0from\0to\0". Binary: "-\t-".</summary>
    public static List<(string Path, int? Added, int? Removed)> ParseNumstat(string z)
    {
        var list = new List<(string, int?, int?)>();
        var parts = z.Split('\0');
        for (int i = 0; i < parts.Length; i++)
        {
            var e = parts[i];
            if (e.Length == 0) continue;
            var cols = e.Split('\t');
            if (cols.Length < 3) continue;
            int? a = int.TryParse(cols[0], NumberStyles.Integer, CultureInfo.InvariantCulture, out var av) ? av : null;
            int? r = int.TryParse(cols[1], NumberStyles.Integer, CultureInfo.InvariantCulture, out var rv) ? rv : null;
            var path = cols[2];
            if (path.Length == 0 && i + 2 < parts.Length)
            {
                i++; // from
                path = parts[++i];
            }
            list.Add((path, a, r));
        }
        return list;
    }

    // ---- running git

    private sealed record Result(string Output, bool Truncated);

    private static bool TryRun(string dir, out string output, params string[] args)
    {
        try
        {
            output = Run(dir, args).Output;
            return true;
        }
        catch (GitException ex) when (ex.Status == 500)
        {
            output = "";
            return false;
        }
    }

    private static Result Run(string dir, params string[] args)
    {
        var psi = new ProcessStartInfo("git")
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            StandardOutputEncoding = new UTF8Encoding(false),
            StandardErrorEncoding = new UTF8Encoding(false),
            WorkingDirectory = dir,
        };
        foreach (var a in new[] { "-c", "core.quotepath=false", "-c", "color.ui=false", "-c", "core.pager=" }) psi.ArgumentList.Add(a);
        foreach (var a in args) psi.ArgumentList.Add(a);
        psi.Environment["GIT_OPTIONAL_LOCKS"] = "0";
        psi.Environment["GIT_TERMINAL_PROMPT"] = "0";
        Process p;
        try { p = Process.Start(psi) ?? throw new GitException(503, "Git을 실행하지 못했습니다"); }
        catch (Win32Exception) { throw new GitException(503, "이 PC에 Git이 설치되어 있지 않습니다 (PATH에 git 없음)"); }
        using (p)
        {
            var errTask = p.StandardError.ReadToEndAsync();
            var buf = new char[16 * 1024];
            var sb = new StringBuilder();
            bool truncated = false;
            var readTask = Task.Run(() =>
            {
                int n;
                while ((n = p.StandardOutput.Read(buf, 0, buf.Length)) > 0)
                {
                    if (sb.Length < MaxDiffChars + 1) sb.Append(buf, 0, Math.Min(n, MaxDiffChars + 1 - sb.Length));
                    else truncated = true;
                }
            });
            if (!p.WaitForExit(Timeout))
            {
                try { p.Kill(entireProcessTree: true); } catch (InvalidOperationException) { }
                throw new GitException(504, "Git이 응답하지 않습니다");
            }
            readTask.Wait(Timeout);
            if (p.ExitCode != 0) throw new GitException(500, errTask.Result.Trim());
            return new Result(sb.ToString(), truncated);
        }
    }
}
