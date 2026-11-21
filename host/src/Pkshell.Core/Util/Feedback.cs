using System.Text.Encodings.Web;
using System.Text.Json;

namespace Pkshell.Core.Util;

/// <summary>Body of <c>POST /api/feedback</c> and of the tray's 의견 보내기 form.</summary>
public sealed class FeedbackRequest
{
    /// <summary>One of <see cref="Feedback.Categories"/>.</summary>
    public string? Category { get; set; }
    public string? Message { get; set; }
    /// <summary>"android" | "pc".</summary>
    public string? Source { get; set; }
    /// <summary>Phone app version (the phone sends it).</summary>
    public string? AppVersion { get; set; }
    /// <summary>Phone model and Android version (the phone sends it).</summary>
    public string? Device { get; set; }
}

/// <summary>
/// Bug reports, improvement ideas, opinions and questions, saved one JSON file each. The folder is the shared
/// OneDrive <c>apppatch\PocketShell\feedback</c> when this PC syncs it, else <c>&lt;home&gt;\feedback</c>.
/// </summary>
public static class Feedback
{
    public const int MaxMessageLength = 5000;

    /// <summary>Category id → label shown to people (and kept in the file).</summary>
    public static readonly IReadOnlyDictionary<string, string> Categories = new Dictionary<string, string>(StringComparer.Ordinal)
    {
        ["bug"] = "버그 수정",
        ["improve"] = "개선",
        ["opinion"] = "의견",
        ["question"] = "문의",
    };

    private static readonly JsonSerializerOptions FileJson = new(JsonSerializerDefaults.Web)
    {
        WriteIndented = true,
        Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping, // Korean stays readable in the file
    };

    /// <summary>Null when the request is fine, else the reason (Korean, shown to the user).</summary>
    public static string? Validate(FeedbackRequest req)
    {
        if (req.Category == null || !Categories.ContainsKey(req.Category)) return "종류를 고르세요 (버그 수정 · 개선 · 의견 · 문의)";
        var msg = req.Message?.Trim() ?? "";
        if (msg.Length == 0) return "내용을 적어 주세요";
        if (msg.Length > MaxMessageLength) return $"내용은 {MaxMessageLength}자까지입니다";
        return null;
    }

    /// <summary>
    /// The shared folder feedback goes to: PKSHELL_FEEDBACK_DIR, else &lt;PKSHELL_RELEASE_COPY_DIR&gt;\feedback, else
    /// &lt;a OneDrive folder under the user profile&gt;\apppatch\PocketShell\feedback when that apppatch folder exists.
    /// Null when this PC has none of them.
    /// </summary>
    public static string? SharedDir(string? userProfile = null, Func<string, string?>? env = null)
    {
        env ??= UserEnv;
        var explicitDir = env("PKSHELL_FEEDBACK_DIR");
        if (!string.IsNullOrWhiteSpace(explicitDir)) return explicitDir.Trim();
        var release = env("PKSHELL_RELEASE_COPY_DIR");
        if (!string.IsNullOrWhiteSpace(release) && Directory.Exists(release.Trim())) return Path.Combine(release.Trim(), "feedback");
        var profile = userProfile ?? Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        if (string.IsNullOrEmpty(profile) || !Directory.Exists(profile)) return null;
        try
        {
            foreach (var od in Directory.EnumerateDirectories(profile, "OneDrive*").OrderBy(d => d, StringComparer.OrdinalIgnoreCase))
            {
                var app = Path.Combine(od, "apppatch", "PocketShell");
                if (Directory.Exists(app)) return Path.Combine(app, "feedback");
            }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { }
        return null;
    }

    /// <summary>Writes one feedback file. Returns its path and whether it went to the shared folder.</summary>
    public static (string Path, bool Shared) Save(string home, FeedbackRequest req, string hostVersion, string? deviceId = null,
        string? deviceName = null, DateTimeOffset? now = null, string? sharedDir = null)
    {
        var when = now ?? DateTimeOffset.Now;
        var shared = sharedDir ?? SharedDir();
        var dir = shared ?? Path.Combine(home, "feedback");
        Directory.CreateDirectory(dir);
        var id = Guid.NewGuid().ToString("N")[..8];
        var category = req.Category ?? "opinion";
        var item = new
        {
            id,
            time = when.ToString("yyyy-MM-ddTHH:mm:sszzz"),
            category,
            categoryLabel = Categories.TryGetValue(category, out var label) ? label : category,
            message = req.Message?.Trim() ?? "",
            source = req.Source == "pc" ? "pc" : "android",
            appVersion = req.AppVersion,
            device = req.Device,
            deviceId,
            deviceName,
            pc = Environment.MachineName,
            user = Environment.UserName,
            hostVersion,
        };
        var path = Path.Combine(dir, $"{when:yyyyMMdd-HHmmss}-{category}-{id}.json");
        File.WriteAllText(path, JsonSerializer.Serialize(item, FileJson), new System.Text.UTF8Encoding(false));
        return (path, shared != null);
    }

    private static string? UserEnv(string name) =>
        Environment.GetEnvironmentVariable(name) ?? Environment.GetEnvironmentVariable(name, EnvironmentVariableTarget.User);
}
