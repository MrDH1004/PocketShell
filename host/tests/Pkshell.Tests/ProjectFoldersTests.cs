using Pkshell.Core.Projects;
using Pkshell.Core.Update;

namespace Pkshell.Tests;

public class ProjectFoldersTests
{
    private static string NewRoot()
    {
        var root = Path.Combine(Path.GetTempPath(), "pkshell-folders-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        return root;
    }

    [Fact]
    public void OnlyAFolderRightUnderARegisteredRootMayBeDeleted()
    {
        var root = NewRoot();
        try
        {
            var project = Directory.CreateDirectory(Path.Combine(root, "my-app")).FullName;
            var nested = Directory.CreateDirectory(Path.Combine(project, "src")).FullName;
            var roots = new[] { root + Path.DirectorySeparatorChar }; // as typed in the tray, with a trailing separator

            Assert.Null(ProjectFolders.WhyNotDeletable(roots, project));
            Assert.Null(ProjectFolders.WhyNotDeletable(roots, project.ToUpperInvariant() + Path.DirectorySeparatorChar));
            Assert.NotNull(ProjectFolders.WhyNotDeletable(roots, root));                          // the root itself
            Assert.NotNull(ProjectFolders.WhyNotDeletable(roots, nested));                        // deeper than one level
            Assert.NotNull(ProjectFolders.WhyNotDeletable(roots, Path.GetDirectoryName(root)));   // above the root
            Assert.NotNull(ProjectFolders.WhyNotDeletable(roots, Path.Combine(root, "missing"))); // already gone
            Assert.NotNull(ProjectFolders.WhyNotDeletable(roots, Path.Combine(root, "my-app", "..", "..", "x")));
            Assert.NotNull(ProjectFolders.WhyNotDeletable(roots, "my-app"));                      // not absolute
            Assert.NotNull(ProjectFolders.WhyNotDeletable(roots, ""));
            Assert.NotNull(ProjectFolders.WhyNotDeletable(Array.Empty<string>(), project));       // no roots registered
        }
        finally { Directory.Delete(root, true); }
    }

    [Fact]
    public void ARootRegisteredUnderAnotherRootIsNotDeletable()
    {
        var root = NewRoot();
        try
        {
            var inner = Directory.CreateDirectory(Path.Combine(root, "work")).FullName;
            Assert.NotNull(ProjectFolders.WhyNotDeletable(new[] { root, inner }, inner));
        }
        finally { Directory.Delete(root, true); }
    }

    [Theory]
    [InlineData(@"C:\work\app", @"C:\work\app", true)]
    [InlineData(@"C:\work\app", @"c:\WORK\app\src\x", true)]
    [InlineData(@"C:\work\app", @"C:\work\app2", false)] // a name that only starts the same
    [InlineData(@"C:\work\app", @"C:\work", false)]
    [InlineData(@"C:\work\app", "", false)]
    public void IsInsideComparesWholeFolderNames(string folder, string path, bool expected) =>
        Assert.Equal(expected, ProjectFolders.IsInside(folder, path));
}

public class UpdateProgressTests
{
    private static UpdateStatus St(string state, string? step = null, long? got = null, long? total = null, string current = "1.0.0", string? error = null) =>
        new() { Current = current, Latest = "1.1.0", State = state, Step = step, Downloaded = got, Total = total, Error = error };

    [Fact]
    public void DownloadShowsBytesAndPercent()
    {
        var v = UpdateProgress.Describe(St(UpdateStates.Downloading, UpdateSteps.Download, 5 * 1048576, 20 * 1048576), "1.1.0", false);
        Assert.Equal(1, v.Stage);
        Assert.Equal(25, v.Percent);
        Assert.Contains("5.0 / 20.0 MB (25%)", v.Text);
    }

    [Fact]
    public void DownloadWithoutAKnownSizeHasNoPercent()
    {
        var v = UpdateProgress.Describe(St(UpdateStates.Downloading, UpdateSteps.Download, 3 * 1048576), "1.1.0", false);
        Assert.Null(v.Percent);
        Assert.Contains("3.0 MB", v.Text);
    }

    [Fact]
    public void StagesFollowTheInstall()
    {
        Assert.Equal(2, UpdateProgress.Describe(St(UpdateStates.Downloading, UpdateSteps.Verify), "1.1.0", false).Stage);
        Assert.Equal(2, UpdateProgress.Describe(St(UpdateStates.Downloading, UpdateSteps.Extract), "1.1.0", false).Stage);
        Assert.Equal(3, UpdateProgress.Describe(St(UpdateStates.Applying), "1.1.0", false).Stage);
        // The host stops answering while it swaps files, then the new build starts.
        Assert.Equal(3, UpdateProgress.Describe(null, "1.1.0", installedIsNew: false).Stage);
        Assert.Equal(4, UpdateProgress.Describe(null, "1.1.0", installedIsNew: true).Stage);
    }

    [Fact]
    public void DoneOnceTheHostRunsTheTargetAndFailedOnError()
    {
        var done = UpdateProgress.Describe(St(UpdateStates.None, current: "1.1.0"), "1.1.0", true);
        Assert.True(done.Done);
        Assert.Equal(100, done.Percent);
        var failed = UpdateProgress.Describe(St(UpdateStates.Error, error: "sha256 mismatch"), "1.1.0", false);
        Assert.True(failed.Failed);
        Assert.Contains("sha256 mismatch", failed.Text);
    }
}
