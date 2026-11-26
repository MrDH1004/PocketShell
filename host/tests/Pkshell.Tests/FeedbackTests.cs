using System.Text.Json;
using Pkshell.Core.Util;

namespace Pkshell.Tests;

public class FeedbackTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), "pkfb-" + Guid.NewGuid().ToString("N")[..8]);

    public FeedbackTests() => Directory.CreateDirectory(_root);

    public void Dispose()
    {
        try { Directory.Delete(_root, true); } catch (IOException) { }
    }

    [Fact]
    public void ValidateNeedsAKnownKindAndText()
    {
        Assert.NotNull(Feedback.Validate(new FeedbackRequest { Category = "x", Message = "hi" }));
        Assert.NotNull(Feedback.Validate(new FeedbackRequest { Category = "bug", Message = "  " }));
        Assert.NotNull(Feedback.Validate(new FeedbackRequest { Category = "bug", Message = new string('a', Feedback.MaxMessageLength + 1) }));
        Assert.Null(Feedback.Validate(new FeedbackRequest { Category = "question", Message = "어떻게 쓰나요?" }));
    }

    [Fact]
    public void SharedDirFindsOneDriveApppatchOrEnv()
    {
        var profile = Path.Combine(_root, "profile");
        Directory.CreateDirectory(Path.Combine(profile, "OneDrive"));
        string? NoEnv(string _) => null;
        Assert.Null(Feedback.SharedDir(profile, NoEnv));

        var app = Path.Combine(profile, "OneDrive - Company", "apppatch", "PocketShell");
        Directory.CreateDirectory(app);
        Assert.Equal(Path.Combine(app, "feedback"), Feedback.SharedDir(profile, NoEnv));

        var release = Path.Combine(_root, "rel");
        Directory.CreateDirectory(release);
        Assert.Equal(Path.Combine(release, "feedback"), Feedback.SharedDir(profile, n => n == "PKSHELL_RELEASE_COPY_DIR" ? release : null));
        Assert.Equal(@"X:\fb", Feedback.SharedDir(profile, n => n == "PKSHELL_FEEDBACK_DIR" ? @"X:\fb" : release));
    }

    [Fact]
    public void SaveWritesReadableJson()
    {
        var shared = Path.Combine(_root, "shared");
        var req = new FeedbackRequest { Category = "bug", Message = " 정렬이 이상해요 \n", Source = "android", AppVersion = "0.2.111", Device = "Google Pixel / Android 15" };
        var (path, isShared) = Feedback.Save(Path.Combine(_root, "home"), req, "0.2.111", "dev1", "내 폰",
            new DateTimeOffset(2026, 11, 21, 9, 30, 5, TimeSpan.FromHours(9)), shared);
        Assert.True(isShared);
        Assert.StartsWith(Path.Combine(shared, "20261121-093005-bug-"), path);
        var text = File.ReadAllText(path);
        Assert.Contains("정렬이 이상해요", text); // not \uXXXX escaped
        using var doc = JsonDocument.Parse(text);
        var r = doc.RootElement;
        Assert.Equal("bug", r.GetProperty("category").GetString());
        Assert.Equal("버그 수정", r.GetProperty("categoryLabel").GetString());
        Assert.Equal("정렬이 이상해요", r.GetProperty("message").GetString());
        Assert.Equal("android", r.GetProperty("source").GetString());
        Assert.Equal("내 폰", r.GetProperty("deviceName").GetString());
        Assert.Equal("2026-11-21T09:30:05+09:00", r.GetProperty("time").GetString());
    }

    [Theory]
    [InlineData("android", "android")]
    [InlineData("pc", "pc")]
    [InlineData("web", "web")]
    [InlineData(null, "android")]
    [InlineData("other", "android")]
    public void SourceKeepsKnownClients(string? sent, string saved)
    {
        var req = new FeedbackRequest { Category = "opinion", Message = "x", Source = sent };
        var (path, _) = Feedback.Save(Path.Combine(_root, "home"), req, "0.2.145", null, null,
            new DateTimeOffset(2026, 11, 27, 9, 0, 0, TimeSpan.FromHours(9)), Path.Combine(_root, "shared-" + (sent ?? "null")));
        using var doc = JsonDocument.Parse(File.ReadAllText(path));
        Assert.Equal(saved, doc.RootElement.GetProperty("source").GetString());
    }
}
