using System.Text.Json;
using Pkshell.Core.Projects;

namespace Pkshell.Tests;

public class AgentHistoryTests
{
    private static void ClaudeConvo(string claude, string dirName, params string[] cwds)
    {
        var dir = Directory.CreateDirectory(Path.Combine(claude, "projects", dirName)).FullName;
        File.WriteAllLines(Path.Combine(dir, Guid.NewGuid() + ".jsonl"),
            cwds.Select(c => JsonSerializer.Serialize(new { type = "user", cwd = c, message = "hi" })));
    }

    private static string CodexConvo(string codex, string cwd)
    {
        var dir = Directory.CreateDirectory(Path.Combine(codex, "sessions", "2026", "11", "12")).FullName;
        var f = Path.Combine(dir, $"rollout-{Guid.NewGuid()}.jsonl");
        File.WriteAllLines(f, new[]
        {
            JsonSerializer.Serialize(new { type = "session_meta", payload = new { id = "x", cwd } }),
            JsonSerializer.Serialize(new { type = "event", payload = new { text = "hello" } }),
        });
        return f;
    }

    [Fact]
    public void ClaudeNameReplacesEverythingButLettersAndDigits()
    {
        Assert.Equal("E--AIWork-RNDAIProjects-apkdns", AgentHistory.ClaudeName(@"E:\AIWork\RNDAIProjects\apkdns"));
        Assert.Equal("C--GIT-kaia-axsprint", AgentHistory.ClaudeName(@"C:\GIT\kaia_axsprint"));
    }

    [Fact]
    public void FindsOnlyThisFoldersConversations()
    {
        var tmp = Path.Combine(Path.GetTempPath(), "pkshell-hist-" + Guid.NewGuid().ToString("N"));
        var claude = Path.Combine(tmp, "claude");
        var codex = Path.Combine(tmp, "codex");
        try
        {
            const string project = @"C:\work\kaia_app";
            ClaudeConvo(claude, "C--work-kaia-app", project, project);
            ClaudeConvo(claude, "C--work-kaia-app-src", project + @"\src");     // a subfolder of the project
            ClaudeConvo(claude, "C--work-kaia-app-2", @"C:\work\kaia_app-2");   // another project, same prefix
            Directory.CreateDirectory(Path.Combine(claude, "projects", "C--work-kaia-app-old")); // no cwd: name differs
            var mineCodex = CodexConvo(codex, project + @"\sub");
            CodexConvo(codex, @"C:\work\kaia_app2");
            CodexConvo(codex, @"C:\work");

            var found = AgentHistory.Find(project + @"\", claude, codex).Select(p => Path.GetRelativePath(tmp, p)).OrderBy(p => p).ToList();

            Assert.Equal(new[]
            {
                Path.Combine("claude", "projects", "C--work-kaia-app"),
                Path.Combine("claude", "projects", "C--work-kaia-app-src"),
                Path.GetRelativePath(tmp, mineCodex),
            }.OrderBy(p => p), found);
        }
        finally { if (Directory.Exists(tmp)) Directory.Delete(tmp, true); }
    }

    [Fact]
    public void SameEncodedNameOfAnotherFolderIsNotTaken()
    {
        var tmp = Path.Combine(Path.GetTempPath(), "pkshell-hist-" + Guid.NewGuid().ToString("N"));
        try
        {
            var claude = Path.Combine(tmp, "claude");
            ClaudeConvo(claude, "C--work-kaia-app", @"C:\work\kaia-app"); // kaia-app and kaia_app share the name
            Assert.Empty(AgentHistory.Find(@"C:\work\kaia_app", claude, Path.Combine(tmp, "codex")));
        }
        finally { if (Directory.Exists(tmp)) Directory.Delete(tmp, true); }
    }

    [Fact]
    public void NothingWithoutDataFolders() =>
        Assert.Empty(AgentHistory.Find(@"C:\work\app", @"C:\nope\claude-" + Guid.NewGuid(), @"C:\nope\codex-" + Guid.NewGuid()));
}
