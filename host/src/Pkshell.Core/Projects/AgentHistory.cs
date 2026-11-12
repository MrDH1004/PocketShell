using System.Text.Json;
using System.Text.RegularExpressions;
using Pkshell.Core.Util;

namespace Pkshell.Core.Projects;

/// <summary>
/// The AI CLI conversations that belong to a project folder, so "세션과 폴더 삭제" discards the project completely:
/// Claude Code keeps them in <c>&lt;claude&gt;\projects\&lt;folder with every non-alphanumeric char as '-'&gt;\</c>
/// (one folder per working directory, *.jsonl lines carry "cwd"); Codex keeps one
/// <c>&lt;codex&gt;\sessions\YYYY\MM\DD\rollout-*.jsonl</c> per conversation whose first line (session_meta) has the cwd.
/// Only conversations whose recorded cwd is the folder or below it are picked.
/// </summary>
public static class AgentHistory
{
    private static readonly Regex CwdField = new("\"cwd\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"", RegexOptions.CultureInvariant);

    /// <summary>Claude Code's data folder (CLAUDE_CONFIG_DIR, else %USERPROFILE%\.claude).</summary>
    public static string ClaudeDir() =>
        Environment.GetEnvironmentVariable("CLAUDE_CONFIG_DIR") is { Length: > 0 } d ? d
            : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".claude");

    /// <summary>Codex's data folder (CODEX_HOME, else %USERPROFILE%\.codex).</summary>
    public static string CodexDir() =>
        Environment.GetEnvironmentVariable("CODEX_HOME") is { Length: > 0 } d ? d
            : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".codex");

    /// <summary>Claude Code's folder name for a working directory: every character other than A-Z a-z 0-9 becomes '-'.</summary>
    public static string ClaudeName(string folder) => Regex.Replace(folder, "[^A-Za-z0-9]", "-");

    /// <summary>Claude project folders and Codex conversation files of <paramref name="folder"/> (and its subfolders).</summary>
    public static List<string> Find(string folder, string claudeDir, string codexDir)
    {
        var found = new List<string>();
        var full = ProjectFolders.Normalize(folder);
        if (full == null) return found;

        var projects = Path.Combine(claudeDir, "projects");
        if (Directory.Exists(projects))
        {
            var name = ClaudeName(full);
            foreach (var dir in SafeDirs(projects))
            {
                var dn = Path.GetFileName(dir);
                bool exact = string.Equals(dn, name, StringComparison.OrdinalIgnoreCase);
                if (!exact && !dn.StartsWith(name + "-", StringComparison.OrdinalIgnoreCase)) continue;
                // The name alone can match another folder (kaia_app vs kaia-app, or app vs app-2): the recorded cwd decides.
                var cwds = ClaudeCwds(dir);
                bool mine = cwds.Count > 0 ? cwds.All(c => ProjectFolders.IsInside(full, c)) : exact;
                if (mine) found.Add(dir);
            }
        }

        foreach (var sub in new[] { "sessions", "archived_sessions" })
        {
            var root = Path.Combine(codexDir, sub);
            if (!Directory.Exists(root)) continue;
            IEnumerable<string> files;
            try { files = Directory.EnumerateFiles(root, "*.jsonl", SearchOption.AllDirectories).ToList(); }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { Log.Warn($"agent history: cannot list {root}: {ex.Message}"); continue; }
            foreach (var f in files)
                if (CodexCwd(f) is { } cwd && ProjectFolders.IsInside(full, cwd)) found.Add(f);
        }
        return found;
    }

    private static IEnumerable<string> SafeDirs(string dir)
    {
        try { return Directory.GetDirectories(dir); }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { Log.Warn($"agent history: cannot list {dir}: {ex.Message}"); return Array.Empty<string>(); }
    }

    private static HashSet<string> ClaudeCwds(string dir)
    {
        var cwds = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        try
        {
            foreach (var f in Directory.EnumerateFiles(dir, "*.jsonl", SearchOption.AllDirectories))
                foreach (var line in File.ReadLines(f))
                    foreach (Match m in CwdField.Matches(line))
                        if (Unescape(m.Groups[1].Value) is { } c) cwds.Add(c);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            Log.Warn($"agent history: cannot read {dir}: {ex.Message}");
            cwds.Add("?"); // unreadable: never counts as this folder's
        }
        return cwds;
    }

    private static string? CodexCwd(string file)
    {
        try
        {
            using var r = new StreamReader(file);
            var first = r.ReadLine();
            if (first == null) return null;
            using var doc = JsonDocument.Parse(first);
            return doc.RootElement.TryGetProperty("payload", out var p) && p.ValueKind == JsonValueKind.Object
                   && p.TryGetProperty("cwd", out var c) && c.ValueKind == JsonValueKind.String ? c.GetString() : null;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or JsonException) { return null; }
    }

    private static string? Unescape(string jsonBody)
    {
        try { return JsonSerializer.Deserialize<string>("\"" + jsonBody + "\""); }
        catch (JsonException) { return null; }
    }

    /// <summary>Moves each item to the Recycle Bin. Returns the ones that could not be moved, with the reason.</summary>
    public static List<string> Recycle(IEnumerable<string> items)
    {
        var failed = new List<string>();
        foreach (var item in items)
        {
            if (!Directory.Exists(item) && !File.Exists(item)) continue;
            var err = RecycleBin.Send(item);
            if (err != null || Directory.Exists(item) || File.Exists(item)) failed.Add($"{item}: {err ?? "still there"}");
        }
        return failed;
    }
}
