using Mpta.Core.External;

namespace Mpta.Tests;

public sealed class ConsoleActivityTests
{
    private const string Start = "2026-11-08T01:00:00.000Z";

    [Fact]
    public void FirstSightIsStartTimeAndUnchangedConsoleKeepsIt()
    {
        var a = new ConsoleActivity();
        Assert.Equal(Start, a.Observe(1, "t\nE:\\a", Start, null, "2026-11-08T02:00:00.000Z"));
        Assert.Equal(Start, a.Observe(1, "t\nE:\\a", Start, null, "2026-11-08T03:00:00.000Z"));
    }

    [Fact]
    public void TitleOrFolderChangeIsActivity()
    {
        var a = new ConsoleActivity();
        a.Observe(1, "✳ x\nE:\\a", Start, null, "2026-11-08T02:00:00.000Z");
        Assert.Equal("2026-11-08T03:00:00.000Z", a.Observe(1, "⠂ x\nE:\\a", Start, null, "2026-11-08T03:00:00.000Z"));
        Assert.Equal("2026-11-08T03:00:00.000Z", a.Observe(1, "⠂ x\nE:\\a", Start, null, "2026-11-08T04:00:00.000Z"));
    }

    [Fact]
    public void LaterScreenChangeWinsAndUnknownStartFallsBackToNow()
    {
        var a = new ConsoleActivity();
        Assert.Equal("2026-11-08T05:00:00.000Z", a.Observe(1, "t", Start, "2026-11-08T05:00:00.000Z", "2026-11-08T06:00:00.000Z"));
        Assert.Equal("2026-11-08T06:00:00.000Z", a.Observe(2, "t", "", null, "2026-11-08T06:00:00.000Z"));
    }

    [Fact]
    public void PrunedConsoleStartsOver()
    {
        var a = new ConsoleActivity();
        a.Observe(1, "a", Start, null, "2026-11-08T02:00:00.000Z");
        a.Observe(1, "b", Start, null, "2026-11-08T03:00:00.000Z");
        a.Prune(new HashSet<int>());
        Assert.Equal(Start, a.Observe(1, "b", Start, null, "2026-11-08T04:00:00.000Z"));
    }
}
