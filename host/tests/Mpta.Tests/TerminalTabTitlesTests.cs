using Mpta.Core.External;

namespace Mpta.Tests;

public class TerminalTabTitlesTests
{
    private static ConsoleInfo C(int pid, long owner, string title) => new() { Pid = pid, OwnerWindow = owner, Title = title };

    private static Dictionary<long, IReadOnlyList<string>> Tabs(params (long Win, string[] Names)[] w) =>
        w.ToDictionary(x => x.Win, x => (IReadOnlyList<string>)x.Names);

    [Fact]
    public void SingleTabWindowUsesTheTabName()
    {
        var r = TerminalTabTitles.Assign(new[] { C(1, 100, "◑ Flowdesk 깃 연동"), C(2, 200, "✳ QA") },
            Tabs((100, new[] { "✳ flowdesk" }), (200, new[] { "kaia" })));
        Assert.Equal("✳ flowdesk", r[1]);
        Assert.Equal("kaia", r[2]);
    }

    [Fact]
    public void MultiTabWindowMatchesByTitleThenByElimination()
    {
        // Tab 1 not renamed (spinner glyph differs between reads), tab 2 renamed by the user.
        var r = TerminalTabTitles.Assign(new[] { C(1, 100, "◐ Claude Code"), C(2, 100, "✳ Some task") },
            Tabs((100, new[] { "◑ Claude Code", "my renamed tab" })));
        Assert.Equal("◑ Claude Code", r[1]);
        Assert.Equal("my renamed tab", r[2]);
    }

    [Fact]
    public void UnknownWhenTabsAndConsolesDoNotLineUp()
    {
        // Window has a tab whose console is not in our list: no guessing.
        var r = TerminalTabTitles.Assign(new[] { C(1, 100, "a") }, Tabs((100, new[] { "x", "y" })));
        Assert.Empty(r);
        // No owner window / window not found.
        Assert.Empty(TerminalTabTitles.Assign(new[] { C(1, 0, "a"), C(2, 300, "b") }, Tabs((100, new[] { "x" }))));
    }

    [Fact]
    public void TwoRenamedTabsInOneWindowStayUnknown()
    {
        var r = TerminalTabTitles.Assign(new[] { C(1, 100, "a"), C(2, 100, "b") }, Tabs((100, new[] { "x", "y" })));
        Assert.Empty(r);
    }
}
