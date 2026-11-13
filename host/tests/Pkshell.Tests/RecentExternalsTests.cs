using Pkshell.Core.External;
using Pkshell.Core.Sessions;

namespace Pkshell.Tests;

public sealed class RecentExternalsTests : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "psh-recent-" + Guid.NewGuid().ToString("N"));
    private string FilePath => Path.Combine(_dir, "recent-externals.json");

    public void Dispose()
    {
        if (Directory.Exists(_dir)) Directory.Delete(_dir, recursive: true);
    }

    private static SessionDto Ext(string cwd, string? agent = null, string title = "") =>
        new() { Kind = "external", Cwd = cwd, Agent = agent, Title = title, Shell = "cmd" };

    [Fact]
    public void ClosedConsoleAppearsAndAliveOneDoesNot()
    {
        var r = new RecentExternals(FilePath);
        r.Observe(new[] { Ext(@"E:\Work\a", "claude", "✳ alpha"), Ext(@"E:\Work\b") });
        Assert.Empty(r.Closed());

        r.Observe(new[] { Ext(@"E:\Work\b") });
        var closed = Assert.Single(r.Closed());
        Assert.Equal(RecentExternals.Kind, closed.Kind);
        Assert.Equal("alpha", closed.Title);
        Assert.Equal("claude", closed.Agent);
        Assert.Equal(@"E:\Work\a", closed.Cwd);
        Assert.Equal(RecentExternals.IdFor(@"E:\Work\a", "claude"), closed.Id);
    }

    [Fact]
    public void PersistsAcrossInstancesAndRemoveDeletes()
    {
        var r = new RecentExternals(FilePath);
        r.Observe(new[] { Ext(@"E:\Work\a", "gpt") });
        r.Observe(Array.Empty<SessionDto>());

        var reloaded = new RecentExternals(FilePath);
        var e = Assert.Single(reloaded.Closed());
        Assert.Equal("a", e.Title); // no console title seen: folder name
        Assert.True(reloaded.Remove(e.Id));
        Assert.Empty(new RecentExternals(FilePath).Closed());
        Assert.False(reloaded.Remove(e.Id));
    }

    [Fact]
    public void EmptyTitleKeepsKnownTitle()
    {
        var r = new RecentExternals(FilePath);
        r.Observe(new[] { Ext(@"E:\Work\a", null, "my console") });
        r.Observe(new[] { Ext(@"E:\Work\a") }); // light snapshot (no title)
        r.Observe(Array.Empty<SessionDto>());
        Assert.Equal("my console", Assert.Single(r.Closed()).Title);
    }

    [Fact]
    public void IdIgnoresCaseAndTrailingSeparatorButNotAgent()
    {
        Assert.Equal(RecentExternals.IdFor(@"E:\Work\A\", "claude"), RecentExternals.IdFor(@"e:\work\a", "claude"));
        Assert.NotEqual(RecentExternals.IdFor(@"E:\Work\a", "claude"), RecentExternals.IdFor(@"E:\Work\a", null));
        Assert.StartsWith(RecentExternals.IdPrefix, RecentExternals.IdFor(@"E:\Work\a", null), StringComparison.Ordinal);
    }

    [Fact]
    public void OneClosedRecordPerFolder()
    {
        static SessionDto Win(int pid, string start, string? agent, string cwd = @"E:\Work\a") =>
            new() { Kind = "external", Cwd = cwd, Agent = agent, Shell = "cmd", Pid = pid, CreatedAt = start };
        var r = new RecentExternals(FilePath);
        r.Observe(new[] { Win(10, "2026-11-08T01:00:00Z", "claude"), Win(20, "2026-11-08T02:00:00Z", null) });
        r.Observe(new[] { Win(20, "2026-11-08T02:00:00Z", null) });
        Assert.Empty(r.Closed()); // its folder is still open in window 20
        r.Observe(Array.Empty<SessionDto>());
        Assert.Equal(RecentExternals.IdForConsole(20, "2026-11-08T02:00:00Z"), Assert.Single(r.Closed()).Id);

        // reopening the folder (another case/trailing separator) and closing it again replaces the record
        r.Observe(new[] { Win(30, "2026-11-08T03:00:00Z", "claude", @"e:\work\A\") });
        Assert.Empty(r.Closed());
        r.Observe(Array.Empty<SessionDto>());
        Assert.Equal(RecentExternals.IdForConsole(30, "2026-11-08T03:00:00Z"), Assert.Single(new RecentExternals(FilePath).Closed()).Id);
        // pid reused by a later process = another window
        Assert.NotEqual(RecentExternals.IdForConsole(10, "a"), RecentExternals.IdForConsole(10, "b"));
    }

    [Fact]
    public void AgentSeenOnceIsKeptAndCwdFollowsTheConsole()
    {
        var w = new SessionDto { Kind = "external", Cwd = @"E:\Work\a", Agent = "claude", Shell = "cmd", Pid = 5, CreatedAt = "t" };
        var r = new RecentExternals(FilePath);
        r.Observe(new[] { w });
        r.Observe(new[] { new SessionDto { Kind = "external", Cwd = @"E:\Work\b", Agent = null, Shell = "cmd", Pid = 5, CreatedAt = "t" } });
        r.Observe(Array.Empty<SessionDto>());
        var e = Assert.Single(new RecentExternals(FilePath).Closed()); // id survives a reload
        Assert.Equal("claude", e.Agent);
        Assert.Equal(@"E:\Work\b", e.Cwd);
    }

    [Fact]
    public void KeepsNewestEntriesOnly()
    {
        var r = new RecentExternals(FilePath);
        for (int i = 0; i < RecentExternals.MaxEntries + 5; i++)
        {
            r.Observe(new[] { Ext($@"E:\Work\p{i}") });
            Thread.Sleep(2); // distinct last-seen times (ms resolution)
        }
        r.Observe(Array.Empty<SessionDto>());
        var closed = r.Closed();
        Assert.Equal(RecentExternals.MaxEntries, closed.Count);
        Assert.Equal($@"E:\Work\p{RecentExternals.MaxEntries + 4}", closed[0].Cwd);
    }

    [Theory]
    [InlineData("✳ kaia_axsprint", "kaia_axsprint")]
    [InlineData("⠂ flowdesk", "flowdesk")]
    [InlineData("plain title", "plain title")]
    [InlineData("✳", "")]
    public void CleanTitleDropsLeadingGlyphs(string raw, string expected) => Assert.Equal(expected, RecentExternals.CleanTitle(raw));

    [Fact]
    public void ResumeCommandPerAgent()
    {
        Assert.Equal("claude --continue", RecentExternals.ResumeCommand("claude"));
        Assert.Equal("codex resume --last", RecentExternals.ResumeCommand("gpt"));
        Assert.Null(RecentExternals.ResumeCommand(null));
    }
}
