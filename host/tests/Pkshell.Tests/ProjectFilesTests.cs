using System.Text;
using Pkshell.Core.Files;

namespace Pkshell.Tests;

public sealed class ProjectFilesTests : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "pkshell-files-" + Guid.NewGuid().ToString("N"));

    public ProjectFilesTests() => Directory.CreateDirectory(_dir);

    public void Dispose()
    {
        if (Directory.Exists(_dir)) Directory.Delete(_dir, recursive: true);
    }

    [Fact]
    public void ListPutsFoldersFirstSortedByName()
    {
        File.WriteAllText(Path.Combine(_dir, "b.txt"), "12345");
        File.WriteAllText(Path.Combine(_dir, "A.md"), "");
        Directory.CreateDirectory(Path.Combine(_dir, "zsrc"));
        Directory.CreateDirectory(Path.Combine(_dir, "Docs"));

        var l = ProjectFiles.List(_dir);
        Assert.Equal(new[] { "Docs", "zsrc", "A.md", "b.txt" }, l.Entries.Select(e => e.Name));
        Assert.True(l.Entries[0].Dir);
        Assert.Equal(5, l.Entries[3].Size);
        Assert.Throws<DirectoryNotFoundException>(() => ProjectFiles.List(Path.Combine(_dir, "missing")));
    }

    [Theory]
    [InlineData(null, false)]
    [InlineData("", false)]
    [InlineData("relative\\x", false)]
    [InlineData("C:x", false)]
    [InlineData("C:\\Work\\..\\Work\\a", true)]
    public void NormalizeNeedsAbsolutePath(string? path, bool ok)
    {
        var n = ProjectFiles.Normalize(path);
        Assert.Equal(ok, n != null);
        if (ok) Assert.Equal("C:\\Work\\a", n);
    }

    [Theory]
    [InlineData("a.txt", true)]
    [InlineData("사진 1.png", true)]
    [InlineData("..", false)]
    [InlineData("a\\b", false)]
    [InlineData("a/b", false)]
    [InlineData("x:y", false)]
    [InlineData("a.", false)]
    [InlineData("", false)]
    public void ValidateName(string name, bool ok) => Assert.Equal(ok, ProjectFiles.ValidateName(name) == null);

    [Fact]
    public async Task SaveWritesAndRefusesSilentOverwrite()
    {
        using (var s = new MemoryStream(Encoding.UTF8.GetBytes("one")))
            await ProjectFiles.SaveAsync(_dir, "f.txt", s, overwrite: false, CancellationToken.None);
        Assert.Equal("one", File.ReadAllText(Path.Combine(_dir, "f.txt")));

        using (var s = new MemoryStream(Encoding.UTF8.GetBytes("two")))
            await Assert.ThrowsAsync<FileExistsException>(() => ProjectFiles.SaveAsync(_dir, "f.txt", s, overwrite: false, CancellationToken.None));
        using (var s = new MemoryStream(Encoding.UTF8.GetBytes("two")))
            await ProjectFiles.SaveAsync(_dir, "f.txt", s, overwrite: true, CancellationToken.None);
        Assert.Equal("two", File.ReadAllText(Path.Combine(_dir, "f.txt")));
        Assert.Single(Directory.GetFiles(_dir)); // no temp file left
    }
}
