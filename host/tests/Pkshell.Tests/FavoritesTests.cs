using Pkshell.Core.Projects;

namespace Pkshell.Tests;

public class FavoritesTests
{
    private static string Home() => Directory.CreateDirectory(Path.Combine(Path.GetTempPath(), "pkfav-" + Guid.NewGuid())).FullName;

    [Fact]
    public void StarsAFolderOnceWhateverItsCaseOrTrailingSeparator()
    {
        var home = Home();
        Assert.Empty(Favorites.Load(home));
        Favorites.Set(home, @"E:\Work\App", true);
        Favorites.Set(home, @"e:\work\app\", true);
        Favorites.Set(home, @"E:\Work\Other", true);
        Assert.Equal(new[] { @"E:\Work\App", @"E:\Work\Other" }, Favorites.Load(home));
    }

    [Fact]
    public void UnstarsByTheSameFolderKey()
    {
        var home = Home();
        Favorites.Set(home, @"E:\Work\App", true);
        var left = Favorites.Set(home, @"E:\WORK\APP\", false);
        Assert.Empty(left);
        Assert.Empty(Favorites.Load(home));
    }

    [Fact]
    public void BrokenFileIsIgnored()
    {
        var home = Home();
        File.WriteAllText(Favorites.File(home), "{not json");
        Assert.Empty(Favorites.Load(home));
        Assert.Single(Favorites.Set(home, @"C:\x", true));
    }
}
