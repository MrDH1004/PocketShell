using Mpta.Core.Projects;

namespace Mpta.Tests;

public class ProjectStoreTests
{
    [Theory]
    [InlineData("my-app")]
    [InlineData("한글 프로젝트")]
    [InlineData("v1.2")]
    public void ValidNames(string name) => Assert.Null(ProjectStore.ValidateName(name));

    [Theory]
    [InlineData("")]
    [InlineData("   ")]
    [InlineData(" lead")]
    [InlineData("..")]
    [InlineData("trail.")]
    [InlineData("a\\b")]
    [InlineData("a/b")]
    [InlineData("C:x")]
    [InlineData("what?")]
    [InlineData("CON")]
    [InlineData("nul.txt")]
    [InlineData("com1")]
    public void InvalidNames(string name) => Assert.NotNull(ProjectStore.ValidateName(name));

    [Fact]
    public void FindRootMatchesConfiguredRootOnly()
    {
        var s = new ProjectSettings { Roots = { @"E:\AIWork\RNDAIProjects", @"D:\Work" } };
        Assert.Equal(@"E:\AIWork\RNDAIProjects", ProjectStore.FindRoot(s, @"e:\aiwork\rndaiprojects\"));
        Assert.Null(ProjectStore.FindRoot(s, @"E:\AIWork"));
        Assert.Null(ProjectStore.FindRoot(s, null)); // two roots: must choose
        Assert.Equal(@"D:\Work", ProjectStore.FindRoot(new ProjectSettings { Roots = { @"D:\Work" } }, null));
    }

    [Fact]
    public void SaveAndLoadRoundTrip()
    {
        var home = Path.Combine(Path.GetTempPath(), "psh-proj-" + Guid.NewGuid().ToString("N"));
        try
        {
            ProjectStore.Save(home, new ProjectSettings { Roots = { @"E:\AIWork\RNDAIProjects", @"e:\aiwork\rndaiprojects", " " } });
            Assert.Equal(new[] { @"E:\AIWork\RNDAIProjects" }, ProjectStore.Load(home).Roots);
            Assert.Empty(ProjectStore.Load(Path.Combine(home, "missing")).Roots);
        }
        finally
        {
            if (Directory.Exists(home)) Directory.Delete(home, true);
        }
    }
}
