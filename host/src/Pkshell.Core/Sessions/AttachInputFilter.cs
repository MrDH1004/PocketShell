using System.Text;

namespace Pkshell.Core.Sessions;

/// <summary>
/// Detects the attach detach chord (Ctrl+] then d) in console input characters and passes everything else through.
/// Input may be raw VT characters or win32-input-mode sequences (ESC [ Vk;Sc;Uc;Kd;Cs;Rc _), which conhost/WT
/// emit when the remote ConPTY output requested mode ?9001. Incomplete win32 sequences are held across calls.
/// </summary>
public sealed class AttachInputFilter
{
    public const char DetachPrefix = '\x1d'; // Ctrl+]

    private readonly StringBuilder _pending = new();
    private bool _prefix;

    /// <summary>Returns the text to forward, and whether the detach chord completed.</summary>
    public (string Forward, bool Detach) Process(string input)
    {
        _pending.Append(input);
        var s = _pending.ToString();
        _pending.Clear();
        var fwd = new StringBuilder();
        int i = 0;
        while (i < s.Length)
        {
            int len = UnitLength(s, i, out bool incomplete);
            if (incomplete) { _pending.Append(s, i, s.Length - i); break; }
            string unit = s.Substring(i, len);
            i += len;
            char? typed = TypedChar(unit);
            if (!_prefix)
            {
                if (typed == DetachPrefix) { _prefix = true; continue; }
                fwd.Append(unit);
                continue;
            }
            if (typed == null) continue; // key-ups / modifiers while waiting for the second key
            _prefix = false;
            if (typed is 'd' or 'D') return (fwd.ToString(), true);
            fwd.Append(DetachPrefix);
            if (typed != DetachPrefix) fwd.Append(unit); // Ctrl+] Ctrl+] sends a single literal Ctrl+]
        }
        return (fwd.ToString(), false);
    }

    /// <summary>Length of the next unit: a full win32-input-mode sequence, or one char.</summary>
    private static int UnitLength(string s, int i, out bool incomplete)
    {
        incomplete = false;
        if (s[i] != '\x1b' || i + 1 >= s.Length || s[i + 1] != '[') return 1;
        int j = i + 2;
        bool digit = false;
        while (j < s.Length && (char.IsAsciiDigit(s[j]) || s[j] == ';')) { digit |= char.IsAsciiDigit(s[j]); j++; }
        if (j >= s.Length)
        {
            // Hold only things that look like the start of a win32 sequence; otherwise forward as-is.
            incomplete = digit;
            return incomplete ? 0 : 1;
        }
        return s[j] == '_' ? j - i + 1 : 1;
    }

    /// <summary>Character typed by this unit (key-down with a non-zero char), or null.</summary>
    internal static char? TypedChar(string unit)
    {
        if (unit.Length == 1) return unit[0];
        if (!unit.EndsWith('_')) return null;
        var parts = unit[2..^1].Split(';');
        int Get(int idx, int def) => idx < parts.Length && int.TryParse(parts[idx], out var v) ? v : def;
        int uc = Get(2, 0), kd = Get(3, 0);
        return kd == 1 && uc != 0 ? (char)uc : null;
    }
}
