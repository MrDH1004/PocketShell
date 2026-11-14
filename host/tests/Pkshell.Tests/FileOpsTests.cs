using Pkshell.Core.Files;

namespace Pkshell.Tests;

public class FileOpsTests : IDisposable
{
    private readonly string _root = Directory.CreateDirectory(Path.Combine(Path.GetTempPath(), "pkshell-fileops-" + Guid.NewGuid().ToString("N"))).FullName;

    public void Dispose()
    {
        try { Directory.Delete(_root, recursive: true); } catch (IOException) { }
    }

    private string Dir(string rel) => Directory.CreateDirectory(Path.Combine(_root, rel)).FullName;

    private string FileAt(string rel, string text = "x")
    {
        var p = Path.Combine(_root, rel);
        Directory.CreateDirectory(Path.GetDirectoryName(p)!);
        File.WriteAllText(p, text);
        return p;
    }

    [Fact]
    public void CopyIntoTheSameFolderNamesItLikeExplorer()
    {
        var a = FileAt("a.txt", "hello");
        var r1 = FileOps.Copy([a], _root);
        var r2 = FileOps.Copy([a], _root);
        Assert.Equal(Path.Combine(_root, "a - 복사본.txt"), r1.Paths.Single());
        Assert.Equal(Path.Combine(_root, "a - 복사본 (2).txt"), r2.Paths.Single());
        Assert.Equal("hello", File.ReadAllText(r2.Paths.Single()));
        Assert.True(File.Exists(a));
    }

    [Fact]
    public void CopyAFolderWithItsContents()
    {
        FileAt("src/sub/b.txt", "b");
        var dest = Dir("dest");
        var r = FileOps.Copy([Path.Combine(_root, "src")], dest);
        Assert.Equal(1, r.Done);
        Assert.Equal("b", File.ReadAllText(Path.Combine(dest, "src", "sub", "b.txt")));
    }

    [Fact]
    public void MoveSeveralAndKeepWhatIsAlreadyThere()
    {
        var a = FileAt("a.txt", "new");
        var d = Dir("d");
        FileAt("x/c.txt");
        var dest = Dir("dest");
        FileAt("dest/a.txt", "old");
        var r = FileOps.Move([a, d, Path.Combine(_root, "x")], dest);
        Assert.Equal(3, r.Done);
        Assert.Empty(r.Errors);
        Assert.Equal("old", File.ReadAllText(Path.Combine(dest, "a.txt")));
        Assert.Equal("new", File.ReadAllText(Path.Combine(dest, "a (2).txt")));
        Assert.True(Directory.Exists(Path.Combine(dest, "d")));
        Assert.True(File.Exists(Path.Combine(dest, "x", "c.txt")));
        Assert.False(File.Exists(a));
    }

    [Fact]
    public void AFolderCannotGoIntoItself()
    {
        var f = Dir("f");
        var inner = Dir("f/inner");
        var r = FileOps.Move([f], inner);
        Assert.Equal(0, r.Done);
        Assert.Single(r.Errors);
        Assert.True(Directory.Exists(inner));
    }

    [Fact]
    public void MoveIntoItsOwnFolderIsANoOp()
    {
        var a = FileAt("a.txt");
        var r = FileOps.Move([a], _root);
        Assert.Equal(1, r.Done);
        Assert.True(File.Exists(a));
        Assert.False(File.Exists(Path.Combine(_root, "a (2).txt")));
    }

    [Fact]
    public void DeleteGoesThroughTheRecycleCallbackAndReportsFailures()
    {
        var a = FileAt("a.txt");
        var b = FileAt("b.txt");
        var sent = new List<string>();
        var r = FileOps.Delete([a, b, Path.Combine(_root, "missing")], p => { sent.Add(p); return p == b ? "잠김" : null; });
        Assert.Equal(1, r.Done);
        Assert.Equal([a, b], sent);
        Assert.Equal(2, r.Errors.Count);
    }

    [Fact]
    public void RenameRefusesATakenNameAndAllowsACaseChange()
    {
        var a = FileAt("a.txt");
        FileAt("b.txt");
        Assert.Throws<FileExistsException>(() => FileOps.Rename(a, "b.txt"));
        var to = FileOps.Rename(a, "A.txt");
        Assert.Equal("A.txt", Path.GetFileName(Directory.GetFiles(_root, "a.txt").Single()));
        Assert.Equal(Path.Combine(_root, "A.txt"), to);
    }

    [Fact]
    public void NewFolderGetsTheNextFreeName()
    {
        Assert.Equal(Path.Combine(_root, "새 폴더"), FileOps.MakeDir(_root, null));
        Assert.Equal(Path.Combine(_root, "새 폴더 (2)"), FileOps.MakeDir(_root, null));
        Assert.Throws<FileExistsException>(() => FileOps.MakeDir(_root, "새 폴더"));
    }
}
