using System.Text;
using System.Text.Json;
using Mpta.Core.Sessions;
using Mpta.Core.Util;

namespace Mpta.Core.Projects;

/// <summary>An AI CLI found on this PC that a new project session can start.</summary>
public sealed record ProjectAgent(string Id, string Name, string Command);

/// <summary>projects.json: folders (set in the tray app) under which the phone may create new project folders.</summary>
public sealed class ProjectSettings
{
    public List<string> Roots { get; set; } = new();
}

public sealed class CreateProjectRequest
{
    /// <summary>One of <see cref="ProjectSettings.Roots"/>; may be omitted when only one root is configured.</summary>
    public string? Root { get; set; }
    public string? Name { get; set; }
    /// <summary><see cref="ProjectAgent.Id"/>: "claude" | "codex".</summary>
    public string? Agent { get; set; }
    public int? Cols { get; set; }
    public int? Rows { get; set; }
}

public static class ProjectStore
{
    public static string File(string home) => Path.Combine(home, "projects.json");

    public static ProjectSettings Load(string home)
    {
        var path = File(home);
        if (!System.IO.File.Exists(path)) return new ProjectSettings();
        try
        {
            var s = JsonSerializer.Deserialize<ProjectSettings>(System.IO.File.ReadAllText(path, Encoding.UTF8), Json.Options);
            if (s == null) return new ProjectSettings();
            s.Roots = s.Roots.Where(r => !string.IsNullOrWhiteSpace(r)).Select(r => r.Trim()).Distinct(StringComparer.OrdinalIgnoreCase).ToList();
            return s;
        }
        catch (JsonException ex)
        {
            Log.Error($"projects.json invalid, ignoring it: {ex.Message}");
            return new ProjectSettings();
        }
    }

    public static void Save(string home, ProjectSettings s)
    {
        Directory.CreateDirectory(home);
        var tmp = File(home) + ".tmp";
        System.IO.File.WriteAllText(tmp, JsonSerializer.Serialize(s, Json.Indented), new UTF8Encoding(false));
        System.IO.File.Move(tmp, File(home), overwrite: true);
    }

    /// <summary>Claude Code and Codex (GPT), when their CLI is on PATH.</summary>
    public static List<ProjectAgent> DetectAgents()
    {
        var list = new List<ProjectAgent>();
        if (OnPath("claude")) list.Add(new ProjectAgent("claude", "Claude", "claude"));
        if (OnPath("codex")) list.Add(new ProjectAgent("codex", "GPT (Codex)", "codex"));
        return list;
    }

    private static readonly string[] ExecutableExtensions = { ".exe", ".cmd", ".bat" };

    private static bool OnPath(string name) =>
        ExecutableExtensions.Any(ext => ShellLauncher.FindOnPath(name + ext) != null);

    private static readonly HashSet<string> ReservedNames = new(StringComparer.OrdinalIgnoreCase)
    {
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    };

    /// <summary>A single folder name: no path separators or invalid characters, not "."/"..", not a device name.</summary>
    /// <returns>null when valid, else the reason (Korean, shown on the phone).</returns>
    public static string? ValidateName(string name)
    {
        if (string.IsNullOrWhiteSpace(name)) return "프로젝트 이름을 입력하세요";
        if (name != name.Trim()) return "이름 앞뒤에 공백을 넣을 수 없습니다";
        if (name.Length > 100) return "이름이 너무 깁니다 (100자 이하)";
        if (name is "." or ".." || name.EndsWith('.')) return "이름은 '.'으로 끝날 수 없습니다";
        if (name.IndexOfAny(Path.GetInvalidFileNameChars()) >= 0) return "이름에 쓸 수 없는 문자가 있습니다 (\\ / : * ? \" < > |)";
        var stem = name.Split('.')[0];
        if (ReservedNames.Contains(stem)) return $"'{name}'은 Windows 예약 이름이라 쓸 수 없습니다";
        return null;
    }

    /// <summary>The configured root equal to <paramref name="root"/> (case-insensitive, normalized), or null.</summary>
    public static string? FindRoot(ProjectSettings s, string? root)
    {
        if (string.IsNullOrWhiteSpace(root)) return s.Roots.Count == 1 ? s.Roots[0] : null;
        string Norm(string p) => Path.TrimEndingDirectorySeparator(Path.GetFullPath(p));
        try
        {
            var want = Norm(root);
            return s.Roots.FirstOrDefault(r => string.Equals(Norm(r), want, StringComparison.OrdinalIgnoreCase));
        }
        catch (Exception ex) when (ex is ArgumentException or NotSupportedException or PathTooLongException)
        {
            return null;
        }
    }
}
