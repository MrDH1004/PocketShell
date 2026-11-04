using System.Text;
using Mpta.Core.Native;

namespace Mpta.Core.External;

/// <summary>Converts console cell attributes to 16-color SGR and builds full-screen VT frames (DESIGN §5/§6).</summary>
public static class VtFrame
{
    public static int FgCode(ushort attr)
    {
        int idx = ((attr & Win32.FOREGROUND_RED) != 0 ? 1 : 0) | ((attr & Win32.FOREGROUND_GREEN) != 0 ? 2 : 0) | ((attr & Win32.FOREGROUND_BLUE) != 0 ? 4 : 0);
        return ((attr & Win32.FOREGROUND_INTENSITY) != 0 ? 90 : 30) + idx;
    }

    public static int BgCode(ushort attr)
    {
        int idx = ((attr & Win32.BACKGROUND_RED) != 0 ? 1 : 0) | ((attr & Win32.BACKGROUND_GREEN) != 0 ? 2 : 0) | ((attr & Win32.BACKGROUND_BLUE) != 0 ? 4 : 0);
        return ((attr & Win32.BACKGROUND_INTENSITY) != 0 ? 100 : 40) + idx;
    }

    /// <summary>Full SGR for an attribute, e.g. "\x1b[0;97;44;4m".</summary>
    public static string AttrToSgr(ushort attr)
    {
        var sb = new StringBuilder("\x1b[0;");
        sb.Append(FgCode(attr)).Append(';').Append(BgCode(attr));
        if ((attr & Win32.COMMON_LVB_UNDERSCORE) != 0) sb.Append(";4");
        if ((attr & Win32.COMMON_LVB_REVERSE_VIDEO) != 0) sb.Append(";7");
        sb.Append('m');
        return sb.ToString();
    }

    private const ushort ColorMask = 0x00FF | Win32.COMMON_LVB_UNDERSCORE | Win32.COMMON_LVB_REVERSE_VIDEO;

    /// <summary>
    /// Frame = ESC[?25l ESC[H, then per row: position, SGR runs + text, ESC[0m ESC[K; then cursor ESC[r;cH and ESC[?25h.
    /// <paramref name="cursorX"/>/<paramref name="cursorY"/> are 0-based relative to the window; cursor is hidden if outside.
    /// </summary>
    public static byte[] Build(char[] chars, ushort[] attrs, int width, int height, int cursorX, int cursorY)
    {
        var sb = new StringBuilder(width * height + 64);
        sb.Append("\x1b[?25l\x1b[H");
        for (int y = 0; y < height; y++)
        {
            int rowStart = y * width;
            // Trim trailing blank cells on black background without underline/reverse; ESC[K clears the rest.
            int end = width;
            while (end > 0)
            {
                int i = rowStart + end - 1;
                ushort a = attrs[i];
                bool blank = (chars[i] == ' ' || chars[i] == '\0') && (a & 0x00F0) == 0 && (a & (Win32.COMMON_LVB_UNDERSCORE | Win32.COMMON_LVB_REVERSE_VIDEO)) == 0;
                if (!blank) break;
                end--;
            }
            sb.Append("\x1b[").Append(y + 1).Append(";1H");
            int current = -1;
            for (int x = 0; x < end; x++)
            {
                int i = rowStart + x;
                ushort a = attrs[i];
                if ((a & Win32.COMMON_LVB_TRAILING_BYTE) != 0) continue; // second half of a wide char
                int colorAttr = a & ColorMask;
                if (colorAttr != current)
                {
                    sb.Append(AttrToSgr((ushort)colorAttr));
                    current = colorAttr;
                }
                char ch = chars[i];
                sb.Append(ch == '\0' || char.IsControl(ch) ? ' ' : ch);
            }
            sb.Append("\x1b[0m\x1b[K");
        }
        if (cursorX >= 0 && cursorY >= 0 && cursorX < width && cursorY < height)
            sb.Append("\x1b[").Append(cursorY + 1).Append(';').Append(cursorX + 1).Append('H').Append("\x1b[?25h");
        return Encoding.UTF8.GetBytes(sb.ToString());
    }
}
