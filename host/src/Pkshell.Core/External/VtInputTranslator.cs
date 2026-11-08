using System.Text;
using Pkshell.Core.Native;

namespace Pkshell.Core.External;

/// <summary>One logical key press; expands to a key-down + key-up KEY_EVENT pair.</summary>
public readonly record struct KeyPress(ushort VirtualKey, char UnicodeChar, uint ControlKeyState);

/// <summary>Translates VT/UTF-8 input bytes into console key presses (DESIGN §6). Keeps UTF-8 decoder state across calls.</summary>
public sealed class VtInputTranslator
{
    public const ushort VK_BACK = 0x08, VK_TAB = 0x09, VK_RETURN = 0x0D, VK_ESCAPE = 0x1B,
        VK_PRIOR = 0x21, VK_NEXT = 0x22, VK_END = 0x23, VK_HOME = 0x24,
        VK_LEFT = 0x25, VK_UP = 0x26, VK_RIGHT = 0x27, VK_DOWN = 0x28, VK_INSERT = 0x2D, VK_DELETE = 0x2E;

    private readonly Decoder _decoder = new UTF8Encoding(false, false).GetDecoder();

    public List<KeyPress> Translate(ReadOnlySpan<byte> data)
    {
        var chars = new char[_decoder.GetCharCount(data, flush: false)];
        _decoder.GetChars(data, chars, flush: false);
        return TranslateChars(chars);
    }

    public static List<KeyPress> TranslateChars(ReadOnlySpan<char> s)
    {
        var keys = new List<KeyPress>();
        int i = 0;
        while (i < s.Length)
        {
            char c = s[i];
            if (c == '\x1b')
            {
                if (i + 1 >= s.Length) { keys.Add(new(VK_ESCAPE, '\x1b', 0)); i++; continue; }
                char n = s[i + 1];
                if (n == '[' || n == 'O')
                {
                    int j = i + 2;
                    // CSI: parameters/intermediates 0x20-0x3F, final 0x40-0x7E. SS3: single final char.
                    if (n == '[') while (j < s.Length && s[j] >= 0x20 && s[j] <= 0x3F) j++;
                    if (j >= s.Length) { keys.Add(new(VK_ESCAPE, '\x1b', 0)); i++; continue; }
                    char fin = s[j];
                    string param = n == '[' ? s[(i + 2)..j].ToString() : "";
                    var key = MapSequence(fin, param);
                    if (key is KeyPress kp) keys.Add(kp);
                    i = j + 1;
                    continue;
                }
                if (n > ' ' && n < '\x7f')
                {
                    // ESC + printable = Alt+key ("meta sends escape"), e.g. ESC v = Alt+V (paste image in Claude Code).
                    keys.Add(new(0, n, Win32.LEFT_ALT_PRESSED));
                    i += 2;
                    continue;
                }
                keys.Add(new(VK_ESCAPE, '\x1b', 0));
                i++;
                continue;
            }
            switch (c)
            {
                case '\r': keys.Add(new(VK_RETURN, '\r', 0)); break;
                case '\x7f':
                case '\b': keys.Add(new(VK_BACK, '\b', 0)); break;
                case '\t': keys.Add(new(VK_TAB, '\t', 0)); break;
                default:
                    if (c >= '\x01' && c <= '\x1a')
                        keys.Add(new((ushort)('A' + c - 1), c, Win32.LEFT_CTRL_PRESSED));
                    else if (c >= ' ' || char.IsSurrogate(c))
                        keys.Add(new(0, c, 0)); // printable: unicode KEY_EVENT (VK resolved to 0 / packet)
                    break;
            }
            i++;
        }
        return keys;
    }

    private static KeyPress? MapSequence(char fin, string param)
    {
        uint enh = Win32.ENHANCED_KEY;
        switch (fin)
        {
            case 'A': return new(VK_UP, '\0', enh);
            case 'B': return new(VK_DOWN, '\0', enh);
            case 'C': return new(VK_RIGHT, '\0', enh);
            case 'D': return new(VK_LEFT, '\0', enh);
            case 'H': return new(VK_HOME, '\0', enh);
            case 'F': return new(VK_END, '\0', enh);
            case '~':
                return param switch
                {
                    "1" or "7" => new(VK_HOME, '\0', enh),
                    "4" or "8" => new(VK_END, '\0', enh),
                    "2" => new(VK_INSERT, '\0', enh),
                    "3" => new(VK_DELETE, '\0', enh),
                    "5" => new(VK_PRIOR, '\0', enh),
                    "6" => new(VK_NEXT, '\0', enh),
                    _ => null,
                };
            default: return null;
        }
    }

    /// <summary>Expands key presses to INPUT_RECORDs (down+up). <paramref name="scanOf"/> maps VK→scan code.</summary>
    public static Win32.INPUT_RECORD[] ToInputRecords(IReadOnlyList<KeyPress> keys, Func<ushort, ushort> scanOf)
    {
        var recs = new Win32.INPUT_RECORD[keys.Count * 2];
        for (int k = 0; k < keys.Count; k++)
        {
            var kp = keys[k];
            ushort scan = kp.VirtualKey != 0 ? scanOf(kp.VirtualKey) : (ushort)0;
            for (int d = 0; d < 2; d++)
            {
                ref var r = ref recs[k * 2 + d];
                r.EventType = Win32.KEY_EVENT;
                r.KeyEvent.bKeyDown = d == 0 ? 1 : 0;
                r.KeyEvent.wRepeatCount = 1;
                r.KeyEvent.wVirtualKeyCode = kp.VirtualKey;
                r.KeyEvent.wVirtualScanCode = scan;
                r.KeyEvent.UnicodeChar = kp.UnicodeChar;
                r.KeyEvent.dwControlKeyState = kp.ControlKeyState;
            }
        }
        return recs;
    }
}
