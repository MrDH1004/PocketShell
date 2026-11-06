using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Mpta.Core.Sessions;
using Mpta.Core.Util;

namespace Mpta.Core.External;

/// <summary>
/// External consoles seen while the host runs, remembered after they close (recent-externals.json) so the phone's
/// 최근 list can reopen the folder and continue the AI CLI. One entry per folder + AI CLI; the newest
/// <see cref="MaxEntries"/> are kept.
/// </summary>
public sealed class RecentExternals
{
    public const int MaxEntries = 50;
    public const string IdPrefix = "rec-";
    public const string Kind = "recent";

    public sealed class Entry
    {
        public string Id { get; set; } = "";
        public string Title { get; set; } = "";
        public string Shell { get; set; } = "";
        public string Cwd { get; set; } = "";
        public string? Agent { get; set; }
        public string LastSeen { get; set; } = "";
    }

    private readonly string _path;
    private readonly object _gate = new();
    private readonly Dictionary<string, Entry> _entries = new(StringComparer.Ordinal);
    private HashSet<string> _alive = new(StringComparer.Ordinal);

    public RecentExternals(string path)
    {
        _path = path;
        Load();
    }

    public static string FileIn(string home) => Path.Combine(home, "recent-externals.json");

    /// <summary>Stable id for a folder + AI CLI (case-insensitive folder, trailing separator ignored).</summary>
    public static string IdFor(string cwd, string? agent)
    {
        var key = Path.TrimEndingDirectorySeparator(cwd.Trim()).ToUpperInvariant() + "|" + (agent ?? "");
        var hash = SHA256.HashData(Encoding.UTF8.GetBytes(key));
        return IdPrefix + Convert.ToHexString(hash, 0, 6).ToLowerInvariant();
    }

    /// <summary>Drops the status glyphs AI CLIs put in front of the console title ("✳ name", braille spinners).</summary>
    public static string CleanTitle(string title)
    {
        int i = 0;
        while (i < title.Length && (char.IsSymbol(title[i]) || char.IsWhiteSpace(title[i]))) i++;
        return title[i..].Trim();
    }

    /// <summary>Command typed into the reopened shell to continue the folder's last AI conversation, or null.</summary>
    public static string? ResumeCommand(string? agent) => agent switch
    {
        "claude" => "claude --continue",
        "gpt" => "codex resume --last", // codex filters sessions by cwd unless --all
        _ => null,
    };

    private static string FolderName(string cwd)
    {
        var name = Path.GetFileName(Path.TrimEndingDirectorySeparator(cwd));
        return string.IsNullOrEmpty(name) ? cwd : name;
    }

    /// <summary>
    /// Records the consoles alive now. A console missing from <paramref name="alive"/> that was alive before is
    /// treated as closed; its entry then shows in <see cref="Closed"/>. Titles may be empty (light snapshot): the
    /// known title is kept.
    /// </summary>
    public void Observe(IEnumerable<SessionDto> alive)
    {
        var now = Ids.IsoNow();
        lock (_gate)
        {
            bool dirty = false;
            var aliveIds = new HashSet<string>(StringComparer.Ordinal);
            foreach (var s in alive)
            {
                if (string.IsNullOrWhiteSpace(s.Cwd)) continue;
                var id = IdFor(s.Cwd, s.Agent);
                aliveIds.Add(id);
                var title = CleanTitle(s.Title);
                if (!_entries.TryGetValue(id, out var e))
                {
                    _entries[id] = e = new Entry { Id = id, Cwd = s.Cwd, Agent = s.Agent, Title = title.Length > 0 ? title : FolderName(s.Cwd) };
                    dirty = true;
                }
                else if (title.Length > 0 && !string.Equals(e.Title, title, StringComparison.Ordinal))
                {
                    e.Title = title;
                    dirty = true;
                }
                if (!string.Equals(e.Shell, s.Shell, StringComparison.Ordinal)) { e.Shell = s.Shell; dirty = true; }
                e.LastSeen = now;
            }
            if (_alive.Any(id => !aliveIds.Contains(id))) dirty = true; // closed: persist its last-seen time
            _alive = aliveIds;
            dirty |= Trim();
            if (dirty) Save();
        }
    }

    /// <summary>Entries whose console is not running now, newest first, as kind=recent sessions.</summary>
    public List<SessionDto> Closed()
    {
        lock (_gate)
        {
            return _entries.Values.Where(e => !_alive.Contains(e.Id))
                .OrderByDescending(e => e.LastSeen, StringComparer.Ordinal)
                .Select(e => new SessionDto
                {
                    Id = e.Id, Kind = Kind, Title = e.Title, Shell = e.Shell, Cwd = e.Cwd, State = "exited",
                    CreatedAt = e.LastSeen, LastActivity = e.LastSeen, Agent = e.Agent,
                })
                .ToList();
        }
    }

    public Entry? Get(string id)
    {
        lock (_gate)
        {
            if (!_entries.TryGetValue(id, out var e)) return null;
            return new Entry { Id = e.Id, Title = e.Title, Shell = e.Shell, Cwd = e.Cwd, Agent = e.Agent, LastSeen = e.LastSeen };
        }
    }

    public bool Remove(string id)
    {
        lock (_gate)
        {
            if (!_entries.Remove(id)) return false;
            Save();
            return true;
        }
    }

    /// <summary>Writes the current last-seen times (called on shutdown).</summary>
    public void Flush()
    {
        lock (_gate) Save();
    }

    private bool Trim()
    {
        if (_entries.Count <= MaxEntries) return false;
        foreach (var e in _entries.Values.Where(e => !_alive.Contains(e.Id))
                     .OrderByDescending(e => e.LastSeen, StringComparer.Ordinal).Skip(MaxEntries).ToList())
            _entries.Remove(e.Id);
        return true;
    }

    private void Load()
    {
        if (!File.Exists(_path)) return;
        try
        {
            var list = JsonSerializer.Deserialize<List<Entry>>(File.ReadAllText(_path, Encoding.UTF8), Json.Options);
            if (list == null) return;
            foreach (var e in list.Where(e => !string.IsNullOrWhiteSpace(e.Cwd)))
            {
                e.Id = IdFor(e.Cwd, e.Agent);
                _entries[e.Id] = e;
            }
        }
        catch (Exception ex) when (ex is JsonException or IOException or UnauthorizedAccessException)
        {
            Log.Error($"recent-externals.json unreadable, starting empty: {ex.Message}");
        }
    }

    private void Save()
    {
        try
        {
            var dir = Path.GetDirectoryName(_path);
            if (!string.IsNullOrEmpty(dir)) Directory.CreateDirectory(dir);
            var tmp = _path + ".tmp";
            var list = _entries.Values.OrderByDescending(e => e.LastSeen, StringComparer.Ordinal).ToList();
            File.WriteAllText(tmp, JsonSerializer.Serialize(list, Json.Indented), new UTF8Encoding(false));
            File.Move(tmp, _path, overwrite: true);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            Log.Error($"recent-externals.json save failed: {ex.Message}");
        }
    }
}
