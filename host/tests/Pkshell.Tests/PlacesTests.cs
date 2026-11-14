using Pkshell.Core.Files;

namespace Pkshell.Tests;

public class PlacesTests
{
    [Fact]
    public void UserFolderFirstThenProjectFoldersThenDrivesWithoutDuplicates()
    {
        var home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        var tmp = Directory.CreateDirectory(Path.Combine(Path.GetTempPath(), "pkshell-places-" + Guid.NewGuid().ToString("N"))).FullName;
        try
        {
            var places = ProjectFiles.Places(new[] { tmp, tmp + Path.DirectorySeparatorChar, Path.Combine(tmp, "missing") });

            Assert.Equal(home, places[0].Path);
            Assert.Equal("folder", places[0].Kind);
            var project = Assert.Single(places, p => p.Path.StartsWith(tmp, StringComparison.OrdinalIgnoreCase));
            Assert.StartsWith("프로젝트 폴더: ", project.Name, StringComparison.Ordinal);
            var system = Path.GetPathRoot(Environment.SystemDirectory)!;
            var drive = Assert.Single(places, p => string.Equals(p.Path, system, StringComparison.OrdinalIgnoreCase));
            Assert.Equal("drive", drive.Kind);
            Assert.StartsWith(system[..2], drive.Name, StringComparison.OrdinalIgnoreCase);
            Assert.True(places.FindIndex(p => p.Kind == "drive") > places.FindLastIndex(p => p.Kind == "folder"));
        }
        finally { Directory.Delete(tmp, true); }
    }
}
