using System.Diagnostics;
using Pkshell.Core.Files;

namespace Pkshell.Tests;

public class GitChangesTests
{
    [Fact]
    public void ParsesStatusWithBranchRenameAndUntracked()
    {
        var z = "## main...origin/main [ahead 2, behind 1]\0 M src/a.cs\0R  new.txt\0old.txt\0?? 한글 파일.md\0A  added.cs\0 D gone.cs\0UU conflict.cs\0";
        var s = GitChanges.ParseStatus(z);
        Assert.Equal("main", s.Branch);
        Assert.Equal("origin/main", s.Upstream);
        Assert.Equal(2, s.Ahead);
        Assert.Equal(1, s.Behind);
        Assert.Equal(new[] { "src/a.cs", "new.txt", "한글 파일.md", "added.cs", "gone.cs", "conflict.cs" }, s.Files.Select(f => f.Path));
        Assert.Equal(new[] { "M", "R", "?", "A", "D", "U" }, s.Files.Select(f => f.Status));
        Assert.Equal("old.txt", s.Files[1].From);
        Assert.False(s.Files[0].Staged);
        Assert.True(s.Files[1].Staged);
        Assert.False(s.Files[2].Staged);
    }

    [Fact]
    public void ParsesBranchWithoutUpstreamOrCommits()
    {
        Assert.Equal("dev", GitChanges.ParseStatus("## dev\0").Branch);
        var fresh = GitChanges.ParseStatus("## No commits yet on main\0?? a.txt\0");
        Assert.Equal("main", fresh.Branch);
        Assert.Null(fresh.Upstream);
    }

    [Fact]
    public void ParsesNumstatIncludingRenamesAndBinary()
    {
        var list = GitChanges.ParseNumstat("3\t1\tsrc/a.cs\0-\t-\timg.png\0" + "0\t0\t\0old.txt\0new.txt\0");
        Assert.Equal(("src/a.cs", (int?)3, (int?)1), list[0]);
        Assert.Equal(("img.png", (int?)null, (int?)null), list[1]);
        Assert.Equal(("new.txt", (int?)0, (int?)0), list[2]);
    }

    [Fact]
    public void ReadsARealRepository()
    {
        var dir = Path.Combine(Path.GetTempPath(), "pkshell-git-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(dir);
        try
        {
            if (!Git(dir, "init", "-q", "-b", "main")) return; // no git on this machine: nothing to check
            Git(dir, "config", "user.email", "t@example.com");
            Git(dir, "config", "user.name", "t");
            File.WriteAllText(Path.Combine(dir, "a.txt"), "one\ntwo\n");
            Git(dir, "add", "a.txt");
            Git(dir, "commit", "-q", "-m", "first");
            File.WriteAllText(Path.Combine(dir, "a.txt"), "one\nTWO\nthree\n");
            File.WriteAllText(Path.Combine(dir, "새 파일.txt"), "hello\n");

            var s = GitChanges.Status(Path.Combine(dir));
            Assert.Equal("main", s.Branch);
            var a = Assert.Single(s.Files, f => f.Path == "a.txt");
            Assert.Equal("M", a.Status);
            Assert.Equal(2, a.Added);
            Assert.Equal(1, a.Removed);
            var n = Assert.Single(s.Files, f => f.Path == "새 파일.txt");
            Assert.Equal("?", n.Status);

            var d = GitChanges.Diff(dir, "a.txt", untracked: false);
            Assert.Contains("-two", d.Diff);
            Assert.Contains("+TWO", d.Diff);
            var u = GitChanges.Diff(dir, "새 파일.txt", untracked: true);
            Assert.Contains("+hello", u.Diff);
            var outside = Assert.Throws<GitException>(() => GitChanges.Diff(dir, "../x.txt", untracked: true));
            Assert.Equal(400, outside.Status);

            var notRepo = Path.Combine(Path.GetTempPath(), "pkshell-nogit-" + Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(notRepo);
            try { Assert.Equal(404, Assert.Throws<GitException>(() => GitChanges.Status(notRepo)).Status); }
            finally { Directory.Delete(notRepo, true); }
        }
        finally
        {
            foreach (var f in Directory.EnumerateFiles(dir, "*", SearchOption.AllDirectories)) File.SetAttributes(f, FileAttributes.Normal);
            Directory.Delete(dir, true);
        }
    }

    private static bool Git(string dir, params string[] args)
    {
        var psi = new ProcessStartInfo("git") { WorkingDirectory = dir, UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true };
        foreach (var a in args) psi.ArgumentList.Add(a);
        try
        {
            using var p = Process.Start(psi)!;
            p.WaitForExit(15000);
            return p.ExitCode == 0;
        }
        catch (System.ComponentModel.Win32Exception) { return false; }
    }
}
