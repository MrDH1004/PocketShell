using System.Text;

namespace Pkshell.Core.Sessions;

/// <summary>
/// The text a pseudoconsole's output leaves on screen: enough of a terminal to read what an AI CLI is showing
/// (DESIGN §14), not to draw it. ConPTY sends rows with cursor positioning, uses "cursor forward" for runs of spaces
/// and erases what a TUI removes, so the raw bytes do not read as text; replaying cursor moves, erases and scrolling
/// onto a grid does. Colours, modes and everything else are ignored. Not thread-safe: callers lock.
/// </summary>
public sealed class VtScreen
{
    private readonly char[][] _cells;
    private readonly Decoder _decoder = Encoding.UTF8.GetDecoder();
    private int _row, _col;
    private State _state = State.Text;
    private readonly StringBuilder _params = new();

    private enum State { Text, Escape, Csi, Osc, OscEscape, Charset }

    public int Cols { get; }
    public int Rows { get; }

    public VtScreen(int cols, int rows)
    {
        Cols = Math.Clamp(cols, 1, 1000);
        Rows = Math.Clamp(rows, 1, 1000);
        _cells = new char[Rows][];
        for (int r = 0; r < Rows; r++) _cells[r] = Blank();
    }

    private char[] Blank()
    {
        var line = new char[Cols];
        Array.Fill(line, ' ');
        return line;
    }

    public void Feed(ReadOnlySpan<byte> data)
    {
        var chars = new char[_decoder.GetCharCount(data, flush: false)];
        int n = _decoder.GetChars(data, chars, flush: false);
        for (int i = 0; i < n; i++) Put(chars[i]);
    }

    /// <summary>The screen as text: one line per row, trailing spaces removed, trailing blank rows dropped.</summary>
    public string Text()
    {
        int last = Rows - 1;
        while (last >= 0 && IsBlank(_cells[last])) last--;
        var sb = new StringBuilder();
        for (int r = 0; r <= last; r++)
        {
            if (r > 0) sb.Append('\n');
            // A wide character occupies two cells; the second holds '\0' and is not text.
            foreach (var c in new string(_cells[r]).TrimEnd()) if (c != '\0') sb.Append(c);
        }
        return sb.ToString();
    }

    private static bool IsBlank(char[] line)
    {
        foreach (var c in line) if (c != ' ' && c != '\0') return false;
        return true;
    }

    private void Put(char c)
    {
        switch (_state)
        {
            case State.Escape:
                if (c == '[') { _state = State.Csi; _params.Clear(); }
                else if (c == ']') _state = State.Osc;
                else if (c is '(' or ')' or '*' or '+') _state = State.Charset;
                else { if (c == 'M') Up(scroll: true); _state = State.Text; } // RI; other two-character escapes: ignored
                return;
            case State.Charset:
                _state = State.Text;
                return;
            case State.Osc:
                if (c == '\a') _state = State.Text;
                else if (c == '\u001b') _state = State.OscEscape;
                return;
            case State.OscEscape:
                _state = c == '\\' ? State.Text : State.Osc;
                return;
            case State.Csi:
                if (c >= '@' && c <= '~') { Csi(c); _state = State.Text; }
                else if (_params.Length < 64) _params.Append(c);
                return;
        }
        switch (c)
        {
            case '\u001b': _state = State.Escape; break;
            case '\r': _col = 0; break;
            case '\n': Down(); break;
            case '\b': if (_col > 0) _col--; break;
            case '\t': _col = Math.Min(Cols - 1, (_col / 8 + 1) * 8); break;
            default:
                if (c < ' ') break;
                if (char.IsLowSurrogate(c)) break; // the high half already took the cell
                Write(c);
                break;
        }
    }

    private void Write(char c)
    {
        int width = IsWide(c) ? 2 : 1;
        if (_col + width > Cols) { _col = 0; Down(); }
        _cells[_row][_col] = c;
        if (width == 2) _cells[_row][_col + 1] = '\0';
        _col += width;
        // The cursor may rest past the last column until the next character (deferred wrap).
    }

    /// <summary>Hangul, CJK and full-width forms take two columns (the ranges a console draws double width).</summary>
    public static bool IsWide(char c) =>
        c >= 0x1100 && (c <= 0x115F || (c >= 0x2E80 && c <= 0xA4CF) || (c >= 0xAC00 && c <= 0xD7A3) ||
                        (c >= 0xF900 && c <= 0xFAFF) || (c >= 0xFE30 && c <= 0xFE4F) || (c >= 0xFF00 && c <= 0xFF60) ||
                        (c >= 0xFFE0 && c <= 0xFFE6));

    private void Down()
    {
        if (_row < Rows - 1) { _row++; return; }
        ScrollUp(1);
    }

    private void Up(bool scroll)
    {
        if (_row > 0) { _row--; return; }
        if (!scroll) return;
        for (int r = Rows - 1; r > 0; r--) _cells[r] = _cells[r - 1];
        _cells[0] = Blank();
    }

    private void ScrollUp(int n)
    {
        n = Math.Clamp(n, 1, Rows);
        for (int r = 0; r < Rows - n; r++) _cells[r] = _cells[r + n];
        for (int r = Rows - n; r < Rows; r++) _cells[r] = Blank();
    }

    private void Csi(char final)
    {
        var text = _params.ToString();
        if (text.Length > 0 && text[0] is '?' or '>' or '<' or '=') return; // private modes: nothing to do with text
        var parts = text.Split(';');
        int Arg(int i, int def)
        {
            if (i >= parts.Length || !int.TryParse(parts[i], System.Globalization.NumberStyles.None, System.Globalization.CultureInfo.InvariantCulture, out var v)) return def;
            return v == 0 ? def : v;
        }
        if (_col >= Cols) _col = Cols - 1;
        switch (final)
        {
            case 'H' or 'f':
                _row = Math.Clamp(Arg(0, 1) - 1, 0, Rows - 1);
                _col = Math.Clamp(Arg(1, 1) - 1, 0, Cols - 1);
                break;
            case 'A': _row = Math.Max(0, _row - Arg(0, 1)); break;
            case 'B': _row = Math.Min(Rows - 1, _row + Arg(0, 1)); break;
            case 'C': _col = Math.Min(Cols - 1, _col + Arg(0, 1)); break;
            case 'D': _col = Math.Max(0, _col - Arg(0, 1)); break;
            case 'E': _row = Math.Min(Rows - 1, _row + Arg(0, 1)); _col = 0; break;
            case 'F': _row = Math.Max(0, _row - Arg(0, 1)); _col = 0; break;
            case 'G': _col = Math.Clamp(Arg(0, 1) - 1, 0, Cols - 1); break;
            case 'd': _row = Math.Clamp(Arg(0, 1) - 1, 0, Rows - 1); break;
            case 'J':
                switch (parts.Length > 0 && parts[0] == "" ? 0 : Arg(0, 0))
                {
                    case 0: EraseLine(_row, _col, Cols); for (int r = _row + 1; r < Rows; r++) _cells[r] = Blank(); break;
                    case 1: EraseLine(_row, 0, _col + 1); for (int r = 0; r < _row; r++) _cells[r] = Blank(); break;
                    default: for (int r = 0; r < Rows; r++) _cells[r] = Blank(); break;
                }
                break;
            case 'K':
                switch (parts.Length > 0 && parts[0] == "" ? 0 : Arg(0, 0))
                {
                    case 0: EraseLine(_row, _col, Cols); break;
                    case 1: EraseLine(_row, 0, _col + 1); break;
                    default: EraseLine(_row, 0, Cols); break;
                }
                break;
            case 'X': EraseLine(_row, _col, Math.Min(Cols, _col + Arg(0, 1))); break;
            case 'S': ScrollUp(Arg(0, 1)); break;
            case 'L':
            {
                int n = Math.Min(Arg(0, 1), Rows - _row);
                for (int r = Rows - 1; r >= _row + n; r--) _cells[r] = _cells[r - n];
                for (int r = _row; r < _row + n; r++) _cells[r] = Blank();
                break;
            }
            case 'M':
            {
                int n = Math.Min(Arg(0, 1), Rows - _row);
                for (int r = _row; r < Rows - n; r++) _cells[r] = _cells[r + n];
                for (int r = Rows - n; r < Rows; r++) _cells[r] = Blank();
                break;
            }
        }
    }

    private void EraseLine(int row, int from, int to)
    {
        for (int c = Math.Max(0, from); c < Math.Min(Cols, to); c++) _cells[row][c] = ' ';
    }
}
