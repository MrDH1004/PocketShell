namespace Pkshell.Core.External;

/// <summary>
/// Picks the Windows Terminal tab name for each console. The console API only knows the title the program set
/// (GetConsoleTitle); a tab the user renamed in WT shows a different name that only WT's UI has. Consoles are tied
/// to a WT window by <see cref="ConsoleInfo.OwnerWindow"/>; inside a window with several tabs a tab is tied to a
/// console when its name equals that console's title, and the last unmatched tab to the last unmatched console.
/// </summary>
public static class TerminalTabTitles
{
    /// <param name="consoles">Consoles to name.</param>
    /// <param name="tabsByWindow">Tab names per WT top-level window handle.</param>
    /// <returns>pid → tab name, only for consoles whose tab is known.</returns>
    public static Dictionary<int, string> Assign(IReadOnlyList<ConsoleInfo> consoles, IReadOnlyDictionary<long, IReadOnlyList<string>> tabsByWindow)
    {
        var result = new Dictionary<int, string>();
        foreach (var group in consoles.Where(c => c.OwnerWindow != 0).GroupBy(c => c.OwnerWindow))
        {
            if (!tabsByWindow.TryGetValue(group.Key, out var tabs) || tabs.Count == 0) continue;
            var members = group.ToList();
            // Only when every tab of the window is one of our consoles; otherwise elimination could mispair.
            if (members.Count != tabs.Count) continue;
            if (tabs.Count == 1)
            {
                result[members[0].Pid] = tabs[0];
                continue;
            }
            var freeTabs = tabs.ToList();
            var freeConsoles = new List<ConsoleInfo>();
            foreach (var c in members)
            {
                int i = freeTabs.FindIndex(t => string.Equals(Normalize(t), Normalize(c.Title), StringComparison.Ordinal));
                if (i < 0) { freeConsoles.Add(c); continue; }
                result[c.Pid] = freeTabs[i];
                freeTabs.RemoveAt(i);
            }
            if (freeConsoles.Count == 1 && freeTabs.Count == 1) result[freeConsoles[0].Pid] = freeTabs[0];
        }
        return result;
    }

    /// <summary>Drops a leading status glyph ("✳ ", "◐ " — Claude animates it) so a title read a moment apart still matches.</summary>
    internal static string Normalize(string title)
    {
        var t = title.Trim();
        if (t.Length >= 2 && !char.IsLetterOrDigit(t[0]) && char.IsWhiteSpace(t[1])) t = t[2..].TrimStart();
        return t;
    }
}
