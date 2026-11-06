using System.Text;
using System.Text.RegularExpressions;

namespace Mpta.Core.External;

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

    public ConsoleScrollback(int maxLines = DefaultMaxLines) => _maxLines = maxLines;

    public int Count { get { lock (_lock) return _lines.Count; } }

    /// <summary>Takes one VtFrame; returns how many lines scrolled into the scrollback (-1 if not a frame).</summary>
    public int Accept(byte[] vtFrame)
    {
        var rows = ParseRows(Encoding.UTF8.GetString(vtFrame));
        if (rows == null) return -1;
        lock (_lock)
        {
            int k = _window.Count == rows.Count ? ScrollShift(_window, rows) : 0;
            for (int i = 0; i < k; i++) _lines.AddLast(_window[i]);
            // Redraws can bring lines that already scrolled off back onto the screen: drop that overlap from the tail.
            for (int m = OverlapWithTop(_lines.TakeLast(Math.Min(_lines.Count, 40)).ToList(), rows); m > 0; m--) _lines.RemoveLast();
            while (_lines.Count > _maxLines) _lines.RemoveFirst();
            _window = rows;
            return k;
        }
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
