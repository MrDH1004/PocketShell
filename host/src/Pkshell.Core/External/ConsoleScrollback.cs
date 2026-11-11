using System.Text;
using System.Text.RegularExpressions;
using Pkshell.Core.Util;

namespace Pkshell.Core.External;

/// <summary>
/// Lines that scrolled off the top of an external console window. Windows Terminal / ConPTY consoles keep no
/// scrollback in the console buffer (buffer == window), so successive frames are compared and the rows that left the
/// top are kept here (per console, across phone connections). Same algorithm as the Android ConsoleHistory.
/// Row bodies are the VtFrame row strings (text + SGR) so a client can re-emit them anywhere.
/// </summary>
public sealed partial class ConsoleScrollback
{
    public const int DefaultMaxLines = 3000;

    private readonly int _maxLines;
    private readonly LinkedList<string> _lines = new();
    private List<string> _window = new();
    private readonly object _lock = new();
    private readonly string? _file;
    private bool _dirty;

    public ConsoleScrollback(int maxLines = DefaultMaxLines) => _maxLines = maxLines;

    /// <summary>
    /// Scrollback kept in <paramref name="file"/> too, so it survives a host restart: loaded here, written by
    /// <see cref="Save"/>. The saved window becomes the previous frame, so the first frame after the restart adds the
    /// rows that scrolled off while the host was down (or nothing, when the screen was redrawn).
    /// </summary>
    public ConsoleScrollback(string file, int maxLines = DefaultMaxLines) : this(maxLines)
    {
        _file = file;
        try
        {
            if (!File.Exists(file)) return;
            var all = File.ReadAllLines(file, Encoding.UTF8);
            if (all.Length == 0 || !int.TryParse(all[0], System.Globalization.NumberStyles.None, System.Globalization.CultureInfo.InvariantCulture, out int n) || n > all.Length - 1) return;
            _window = all.Skip(1).Take(n).ToList();
            foreach (var line in all.Skip(1 + n).TakeLast(maxLines)) _lines.AddLast(line);
        }
        catch (IOException ex) { Log.Warn($"scrollback load {file}: {ex.Message}"); }
        catch (UnauthorizedAccessException ex) { Log.Warn($"scrollback load {file}: {ex.Message}"); }
    }

    /// <summary>Writes the file (when there is one and something changed since the last save).</summary>
    public void Save()
    {
        if (_file == null) return;
        string text;
        lock (_lock)
        {
            if (!_dirty) return;
            _dirty = false;
            var sb = new StringBuilder();
            sb.Append(_window.Count.ToString(System.Globalization.CultureInfo.InvariantCulture)).Append('\n');
            foreach (var r in _window) sb.Append(r).Append('\n');
            foreach (var l in _lines) sb.Append(l).Append('\n');
            text = sb.ToString();
        }
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(_file)!);
            var tmp = _file + ".tmp";
            File.WriteAllText(tmp, text, new UTF8Encoding(false));
            File.Move(tmp, _file, overwrite: true);
        }
        catch (IOException ex) { Log.Warn($"scrollback save {_file}: {ex.Message}"); lock (_lock) _dirty = true; }
        catch (UnauthorizedAccessException ex) { Log.Warn($"scrollback save {_file}: {ex.Message}"); lock (_lock) _dirty = true; }
    }

    /// <summary>Deletes the file (the console is gone).</summary>
    public void DeleteFile()
    {
        if (_file == null) return;
        try { File.Delete(_file); }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }

    public int Count { get { lock (_lock) return _lines.Count; } }

    /// <summary>When the window content last changed (ISO UTC), null before the first frame.</summary>
    public string? LastChange { get { lock (_lock) return _lastChange; } }
    private string? _lastChange;

    /// <summary>Takes one VtFrame; returns how many lines scrolled into the scrollback (-1 if not a frame).</summary>
    public int Accept(byte[] vtFrame)
    {
        var rows = ParseRows(Encoding.UTF8.GetString(vtFrame));
        if (rows == null) return -1;
        lock (_lock)
        {
            if (_lastChange == null || !_window.SequenceEqual(rows, StringComparer.Ordinal))
            {
                _lastChange = Ids.IsoNow();
                _dirty = true;
            }
            int k = _window.Count == rows.Count ? ScrollShift(_window, rows) : 0;
            if (k > 0)
            {
                // Claude Code re-renders a growing reply; a partly drawn copy that scrolled off earlier is replaced.
                var tail = _lines.TakeLast(Math.Min(_lines.Count, 60)).ToList();
                for (int r = RedrawnTail(tail, _window.Take(k).ToList()); r > 0; r--) _lines.RemoveLast();
                for (int i = 0; i < k; i++) _lines.AddLast(_window[i]);
            }
            // The screen moved back down (the program's bottom area got shorter): the rows that came back at the top
            // are the last ones that scrolled off. Seen as a move, so a single line or blank lines count too.
            if (k == 0 && _window.Count == rows.Count)
            {
                int back = ScrollShift(rows, _window);
                if (back > 0 && back <= _lines.Count && _lines.TakeLast(back).SequenceEqual(rows.Take(back), StringComparer.Ordinal))
                    for (int i = 0; i < back; i++) _lines.RemoveLast();
            }
            // Redraws can bring lines that already scrolled off back onto the screen: drop that overlap from the tail.
            for (int m = OverlapWithTop(_lines.TakeLast(Math.Min(_lines.Count, 40)).ToList(), rows); m > 0; m--) _lines.RemoveLast();
            while (_lines.Count > _maxLines) _lines.RemoveFirst();
            _window = rows;
            return k;
        }
    }

    /// <summary>The window as plain text, one row per line (what the console shows now).</summary>
    public string WindowText()
    {
        lock (_lock) return string.Join('\n', _window.Select(Pkshell.Core.Sessions.AnsiText.Strip));
    }

    public List<string> Snapshot()
    {
        lock (_lock) return _lines.ToList();
    }

    /// <summary>Row bodies 1..n of a VtFrame (missing rows = ""), or null if <paramref name="vt"/> is not a frame.</summary>
    public static List<string>? ParseRows(string vt)
    {
        if (!vt.StartsWith("\x1b[?25l\x1b[H", StringComparison.Ordinal)) return null;
        var found = new List<(int Y, string Body)>();
        foreach (Match m in RowRegex().Matches(vt)) found.Add((int.Parse(m.Groups[1].Value, System.Globalization.CultureInfo.InvariantCulture), m.Groups[2].Value));
        if (found.Count == 0) return null;
        var rows = Enumerable.Repeat("", found.Max(f => f.Y)).ToList();
        foreach (var (y, body) in found) rows[y - 1] = body;
        return rows;
    }

    /// <summary>
    /// Lines <paramref name="next"/> scrolled up relative to <paramref name="prev"/>: the smallest k whose top run
    /// next[i] == prev[i + k] covers at least min(8, half the remaining rows) rows including 3 non-blank ones; 0 when
    /// the top did not move or nothing lines up. The bottom (input box, status) may differ freely.
    /// </summary>
    public static int ScrollShift(IReadOnlyList<string> prev, IReadOnlyList<string> next)
    {
        int h = prev.Count;
        if (h != next.Count || h < 4) return 0;
        bool Ok(int k)
        {
            int n = 0, nonBlank = 0;
            while (n + k < h && string.Equals(next[n], prev[n + k], StringComparison.Ordinal))
            {
                if (next[n].Length > 0) nonBlank++;
                n++;
            }
            return n >= Math.Min(8, (h - k) / 2) && nonBlank >= 3;
        }
        if (Ok(0)) return 0;
        for (int k = 1; k < h; k++) if (Ok(k)) return k;
        return 0;
    }

    /// <summary>
    /// Lines at the end of <paramref name="tail"/> that are an earlier render of the start of <paramref name="added"/>:
    /// the first line equal (at least 4 visible characters), every later one equal or a prefix of the new line (a reply
    /// still being written; blank = not drawn yet). 0 when there is none; the longest run wins. Same as Android.
    /// </summary>
    public static int RedrawnTail(IReadOnlyList<string> tail, IReadOnlyList<string> added)
    {
        if (added.Count == 0 || SgrRegex().Replace(added[0], "").Trim().Length < 4) return 0;
        for (int j = 0; j < tail.Count; j++)
        {
            int n = tail.Count - j;
            if (n > added.Count || !string.Equals(tail[j], added[0], StringComparison.Ordinal)) continue;
            bool ok = true;
            for (int i = 1; i < n && ok; i++) ok = added[i].StartsWith(tail[j + i], StringComparison.Ordinal);
            if (ok) return n;
        }
        return 0;
    }

    [GeneratedRegex("\x1b\\[[0-9;]*m")]
    private static partial Regex SgrRegex();

    /// <summary>
    /// Largest m (≥ 2, with ≥ 2 non-blank lines) such that the last m lines of <paramref name="tail"/> equal the first
    /// m rows of <paramref name="window"/>; 0 if none.
    /// </summary>
    public static int OverlapWithTop(IReadOnlyList<string> tail, IReadOnlyList<string> window)
    {
        for (int m = Math.Min(tail.Count, window.Count); m >= 2; m--)
        {
            int nonBlank = 0;
            bool same = true;
            for (int i = 0; i < m && same; i++)
            {
                var line = tail[tail.Count - m + i];
                same = string.Equals(line, window[i], StringComparison.Ordinal);
                if (line.Length > 0) nonBlank++;
            }
            if (same && nonBlank >= 2) return m;
        }
        return 0;
    }

    [GeneratedRegex("\x1b\\[(\\d+);1H(.*?)\x1b\\[0m\x1b\\[K")]
    private static partial Regex RowRegex();
}
