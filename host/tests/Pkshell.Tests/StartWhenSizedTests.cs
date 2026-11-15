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
            Assert.Equal((63, 48), s.CurrentSize); // the phone's width, not min(phone, PC tab)

            // The PC tab answering the program's queries (Windows Terminal does at Claude's start) is not typing.
            s.WriteInputFrom(pc.Client, "\u001b[?1;2c\u001b]11;rgb:0c0c/0c0c/0c0c\u001b\\\u001b[I"u8);
            Assert.Equal((63, 48), s.CurrentSize);
            // Typing in the PC tab makes it the window the size follows; the phone takes it back by typing.
            s.WriteInputFrom(pc.Client, " "u8);
            Assert.Equal((48, 70), s.CurrentSize);
            s.WriteInputFrom(phone.Client, " "u8);
            Assert.Equal((63, 48), s.CurrentSize);
            // The phone leaves: the PC tab's size.
            s.Detach(phone);
            Assert.Equal((48, 70), s.CurrentSize);
            s.Detach(pc);
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
