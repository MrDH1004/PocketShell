namespace Pkshell.Core.Sessions;

/// <summary>
/// Input a terminal sends on its own, not typed by a person: replies to the program's queries (device attributes,
/// cursor position, kitty keyboard flags, mode reports, colour queries, XTVERSION) and focus in/out. Claude Code asks
/// these at start; Windows Terminal answering them must not make the PC tab "the window in use" (session size).
/// </summary>
public static class TerminalReports
{
    /// <summary>True when every byte of <paramref name="data"/> belongs to such replies (and there is at least one).</summary>
    public static bool IsOnlyReports(ReadOnlySpan<byte> data)
    {
        if (data.IsEmpty) return false;
        var i = 0;
        while (i < data.Length)
        {
            if (data[i] != 0x1b || i + 1 >= data.Length) return false;
            var kind = data[i + 1];
            if (kind == (byte)'[')
            {
                var end = CsiEnd(data, i + 2);
                if (end < 0 || !IsReportCsi(data.Slice(i + 2, end - (i + 2)), data[end])) return false;
                i = end + 1;
            }
            else if (kind is (byte)']' or (byte)'P' or (byte)'_')
            {
                // OSC (colour replies), DCS (XTVERSION, DECRQSS), APC: up to BEL or ST (ESC \).
                var j = i + 2;
                while (j < data.Length && data[j] != 0x07 && !(data[j] == 0x1b && j + 1 < data.Length && data[j + 1] == (byte)'\\')) j++;
                if (j >= data.Length) return false;
                i = data[j] == 0x07 ? j + 1 : j + 2;
            }
            else return false;
        }
        return true;
    }

    /// <summary>Index of a CSI's final byte (0x40–0x7E) at or after <paramref name="from"/>, or -1.</summary>
    private static int CsiEnd(ReadOnlySpan<byte> data, int from)
    {
        for (var j = from; j < data.Length; j++)
        {
            var b = data[j];
            if (b is >= 0x40 and <= 0x7e) return j;
            if (b is < 0x20 or > 0x3f) return -1; // only parameter / intermediate bytes before the final one
        }
        return -1;
    }

    private static bool IsReportCsi(ReadOnlySpan<byte> body, byte final)
    {
        var lead = body.IsEmpty ? (byte)0 : body[0];
        return final switch
        {
            (byte)'c' => true,                              // DA1 / DA2 / DA3: ESC[?..c, ESC[>..c
            (byte)'R' => true,                              // cursor position report: ESC[row;colR
            (byte)'n' => true,                              // status report: ESC[0n, ESC[?..n
            (byte)'u' => lead == (byte)'?',                 // kitty keyboard flags: ESC[?flagsu (a key is ESC[codeu)
            (byte)'y' => body.IndexOf((byte)'$') >= 0,      // DECRPM mode report: ESC[?2026;2$y
            (byte)'t' => true,                              // window size / XTWINOPS reports
            (byte)'I' or (byte)'O' => body.IsEmpty,         // focus in / out
            _ => false,
        };
    }
}
