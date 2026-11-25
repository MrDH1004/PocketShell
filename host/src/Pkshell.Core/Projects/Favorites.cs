using System.Text;
using System.Text.Json;
using Pkshell.Core.Util;

namespace Pkshell.Core.Projects;

/// <summary>
/// favorites.json: project folders the user starred in the session list (phone app and web app share it). Kept by
/// folder, not session id: reopening a past session starts a new one in the same folder.
/// </summary>
public static class Favorites
{
    private static readonly object Gate = new();

    public static string File(string home) => Path.Combine(home, "favorites.json");

    /// <summary>A folder compared the Windows way: trimmed, no trailing separator, case ignored.</summary>
    public static string Key(string? cwd) => (cwd ?? "").Trim().TrimEnd('\\', '/').ToLowerInvariant();

    /// <summary>The starred folders, in the order they were added.</summary>
    public static List<string> Load(string home)
    {
        lock (Gate) return LoadUnlocked(home);
    }

    /// <summary>Stars (<paramref name="on"/>) or unstars a folder; returns the list after the change.</summary>
    public static List<string> Set(string home, string cwd, bool on)
    {
        var key = Key(cwd);
        if (key.Length == 0) throw new ArgumentException("folder is empty", nameof(cwd));
        lock (Gate)
        {
            var list = LoadUnlocked(home);
            int at = list.FindIndex(f => Key(f) == key);
            if (on && at < 0) list.Add(cwd.Trim());
            else if (!on && at >= 0) list.RemoveAt(at);
            else return list;
            Directory.CreateDirectory(home);
            var tmp = File(home) + ".tmp";
            System.IO.File.WriteAllText(tmp, JsonSerializer.Serialize(new Doc { Folders = list }, Json.Indented), new UTF8Encoding(false));
            System.IO.File.Move(tmp, File(home), overwrite: true);
            return list;
        }
    }

    private static List<string> LoadUnlocked(string home)
    {
        var path = File(home);
        if (!System.IO.File.Exists(path)) return new List<string>();
        try
        {
            var d = JsonSerializer.Deserialize<Doc>(System.IO.File.ReadAllText(path, Encoding.UTF8), Json.Options);
            var seen = new HashSet<string>(StringComparer.Ordinal);
            return (d?.Folders ?? new List<string>()).Where(f => !string.IsNullOrWhiteSpace(f) && seen.Add(Key(f))).ToList();
        }
        catch (JsonException ex)
        {
            Log.Error($"favorites.json invalid, ignoring it: {ex.Message}");
            return new List<string>();
        }
    }

    private sealed class Doc
    {
        public List<string> Folders { get; set; } = new();
    }
}
