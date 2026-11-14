using Pkshell.Core.Sessions;

namespace Pkshell.Tests;

/// <summary>An AI CLI start command waits until the attached windows agree on a size (real ConPTY + cmd).</summary>
public class StartWhenSizedTests
{
    [Fact]
    public async Task CommandIsTypedAfterTheSizeSettles()
    {
        var root = Directory.CreateDirectory(Path.Combine(Path.GetTempPath(), "pkshell-start-" + Guid.NewGuid().ToString("N"))).FullName;
        var launch = ShellLauncher.Resolve("cmd", root);
        using var s = HostSession.Start(root, launch, "t", SessionManager.DefaultCols, 30, null, null);
        try
        {
            s.WriteWhenSized("echo START_MARK\r"u8.ToArray());
            await Task.Delay(300);
            var phone = s.Attach("android", 63, 48);
            await Task.Delay(400);
            var pc = s.Attach("cli", 48, 70); // PC tab attaches a bit later, narrower
            await Task.Delay(400);
            Assert.DoesNotContain("START_MARK", s.ScreenText()); // still settling
            await Task.Delay(1500);
            Assert.Contains("START_MARK", s.ScreenText());
            Assert.Equal((48, 48), s.CurrentSize);
            s.Detach(pc);
            s.Detach(phone);
        }
        finally
        {
            s.Kill();
        }
    }

    [Fact]
    public async Task WithoutAnyWindowTheDeadlineStartsIt()
    {
        var root = Directory.CreateDirectory(Path.Combine(Path.GetTempPath(), "pkshell-start-" + Guid.NewGuid().ToString("N"))).FullName;
        using var s = HostSession.Start(root, ShellLauncher.Resolve("cmd", root), "t", 80, 25, null, null);
        try
        {
            s.WriteWhenSized("echo LATE_MARK\r"u8.ToArray());
            await Task.Delay(1500);
            Assert.DoesNotContain("LATE_MARK", s.ScreenText());
            await Task.Delay(4000);
            Assert.Contains("LATE_MARK", s.ScreenText());
        }
        finally
        {
            s.Kill();
        }
    }
}
