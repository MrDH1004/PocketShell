namespace Mpta.Cli;

/// <summary>5-row ASCII block font for digits (ASCII so it survives any console code page) so `mpta pair --pin` shows the PIN large (DESIGN §12.1).</summary>
internal static class BigDigits
{
    private static readonly string[][] Glyphs =
    {
        new[] { "#####", "#   #", "#   #", "#   #", "#####" }, // 0
        new[] { "  #  ", " ##  ", "  #  ", "  #  ", " ### " }, // 1
        new[] { "#####", "    #", "#####", "#    ", "#####" }, // 2
        new[] { "#####", "    #", " ####", "    #", "#####" }, // 3
        new[] { "#   #", "#   #", "#####", "    #", "    #" }, // 4
        new[] { "#####", "#    ", "#####", "    #", "#####" }, // 5
        new[] { "#####", "#    ", "#####", "#   #", "#####" }, // 6
        new[] { "#####", "    #", "   # ", "  #  ", "  #  " }, // 7
        new[] { "#####", "#   #", "#####", "#   #", "#####" }, // 8
        new[] { "#####", "#   #", "#####", "    #", "#####" }, // 9
    };

    public static IEnumerable<string> Render(string digits)
    {
        for (int row = 0; row < 5; row++)
            yield return string.Join("  ", digits.Where(char.IsAsciiDigit).Select((d, i) => (i == 3 ? "  " : "") + Glyphs[d - '0'][row]));
    }
}
