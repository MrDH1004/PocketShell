using System.Net;
using System.Text;
using Mpta.Core.Auth;
using Mpta.Core.External;
using Mpta.Core.Native;
using Mpta.Core.Sessions;

namespace Mpta.Tests;

public class SizePolicyTests
{
    [Fact]
    public void MinOfAllClients()
    {
        var r = SizePolicy.Compute(new[] { (120, 40), (80, 50), (100, 24) }, (10, 10));
        Assert.Equal((80, 24), r);
    }

    [Fact]
    public void NoClientsKeepsCurrent()
    {
        Assert.Equal((132, 43), SizePolicy.Compute(Array.Empty<(int, int)>(), (132, 43)));
    }

    [Fact]
    public void ClientsWithoutSizeAreIgnored()
    {
        Assert.Equal((90, 30), SizePolicy.Compute(new[] { (0, 0), (90, 30) }, (120, 30)));
        Assert.Equal((120, 30), SizePolicy.Compute(new[] { (0, 0) }, (120, 30)));
    }

    [Theory]
    [InlineData(-5, 1)]
    [InlineData(0, 1)]
    [InlineData(1, 1)]
    [InlineData(80, 80)]
    [InlineData(1000, 1000)]
    [InlineData(1001, 1000)]
    [InlineData(40000, 1000)]
    [InlineData(int.MaxValue, 1000)]
    public void ClampBoundsDimensions(int input, int expected)
    {
        Assert.Equal(expected, SizePolicy.Clamp(input));
    }
}

public class OscCwdParserTests
{
    private static byte[] B(string s) => Encoding.UTF8.GetBytes(s);

    [Fact]
    public void Osc99WithSt()
    {
        var p = new OscCwdParser();
        Assert.Equal(@"C:\Users\me", p.Feed(B("abc\x1b]9;9;C:\\Users\\me\x1b\\PS> ")));
    }

    [Fact]
    public void Osc99WithBelAndQuotes()
    {
        var p = new OscCwdParser();
        Assert.Equal(@"D:\work dir", p.Feed(B("\x1b]9;9;\"D:\\work dir\"\x07")));
    }

    [Fact]
    public void SplitAcrossChunksAtEveryPosition()
    {
        var full = B("out\r\n\x1b[32mgreen\x1b[0m\x1b]9;9;C:\\프로젝트\\a\x1b\\C:\\>");
        for (int split = 0; split <= full.Length; split++)
        {
            var p = new OscCwdParser();
            var a = p.Feed(full.AsSpan(0, split));
            var b = p.Feed(full.AsSpan(split));
            Assert.Equal(@"C:\프로젝트\a", b ?? a);
        }
    }

    [Fact]
    public void ByteByByte()
    {
        var p = new OscCwdParser();
        string? last = null;
        foreach (var x in B("\x1b]7;file://host/c/Users/me%20x\x1b\\")) last = p.Feed(new[] { x }) ?? last;
        Assert.Equal(@"C:\Users\me x", last);
    }

    [Fact]
    public void Osc7Variants()
    {
        Assert.Equal(@"C:\Users\me", OscCwdParser.ParseFileUrl("file://pc/C:/Users/me"));
        Assert.Equal(@"C:\", OscCwdParser.ParseFileUrl("file://pc/c"));
        Assert.Equal("/home/me", OscCwdParser.ParseFileUrl("file://ubuntu/home/me"));
    }

    [Fact]
    public void IgnoresOtherOscAndCsi()
    {
        var p = new OscCwdParser();
        Assert.Null(p.Feed(B("\x1b]0;window title\x07\x1b[2J\x1b]8;;http://x\x1b\\")));
    }
}

public class ScrollbackTests
{
    [Fact]
    public void TrimOffsetAtNewlineBoundary()
    {
        var data = Encoding.ASCII.GetBytes("aaaa\nbbbb\ncccc\ndddd\n"); // 20 bytes
        int off = ScrollbackFile.FindTrimOffset(data, 12);              // window starts at 8 ("bb\ncccc..")
        Assert.Equal(10, off);
        Assert.Equal("cccc\ndddd\n", Encoding.ASCII.GetString(data, off, data.Length - off));
    }

    [Fact]
    public void NoTrimWhenSmall()
    {
        Assert.Equal(0, ScrollbackFile.FindTrimOffset(new byte[10], 10));
    }

    [Fact]
    public void NoNewlineKeepsWindow()
    {
        Assert.Equal(6, ScrollbackFile.FindTrimOffset(new byte[10], 4));
    }

    [Fact]
    public void FileTrimsWhenOverMax()
    {
        var dir = Path.Combine(Path.GetTempPath(), "mpta-test-" + Guid.NewGuid().ToString("N"));
        try
        {
            using var f = new ScrollbackFile(Path.Combine(dir, "output.vt"), maxBytes: 100, keepBytes: 50);
            for (int i = 0; i < 20; i++) f.Append(Encoding.ASCII.GetBytes($"line{i:00}\n")); // 7 bytes each = 140
            var all = Encoding.ASCII.GetString(f.ReadAll());
            Assert.True(all.Length <= 100);
            Assert.StartsWith("line", all);
            Assert.EndsWith("line19\n", all);
        }
        finally { try { Directory.Delete(dir, true); } catch (IOException) { } }
    }
}

public class OutputHubTests
{
    [Fact]
    public async Task SnapshotPlusLiveHasNoGapsOrDuplicates()
    {
        var dir = Path.Combine(Path.GetTempPath(), "mpta-test-" + Guid.NewGuid().ToString("N"));
        try
        {
            using var store = new ScrollbackFile(Path.Combine(dir, "output.vt"));
            var hub = new OutputHub(store);
            const int total = 3000;
            var writer = Task.Run(() =>
            {
                for (int i = 0; i < total; i++) hub.Publish(Encoding.ASCII.GetBytes($"{i};"));
            });
            var results = new List<(byte[] snap, Subscriber sub)>();
            for (int k = 0; k < 20; k++)
            {
                results.Add(hub.Subscribe());
                await Task.Delay(1);
            }
            await writer;
            var expected = string.Concat(Enumerable.Range(0, total).Select(i => $"{i};"));
            foreach (var (snap, sub) in results)
            {
                hub.Unsubscribe(sub); // completes the channel
                var sb = new StringBuilder(Encoding.ASCII.GetString(snap));
                await foreach (var m in sub.Reader.ReadAllAsync()) sb.Append(Encoding.ASCII.GetString(m.Data));
                Assert.Equal(expected, sb.ToString());
            }
        }
        finally { try { Directory.Delete(dir, true); } catch (IOException) { } }
    }

    [Fact]
    public async Task SubscriberThatNeverReadsOverflowsWhileOthersKeepReceiving()
    {
        var hub = new OutputHub(null);
        var (_, stalled) = hub.Subscribe();
        var (_, live) = hub.Subscribe();
        int total = Subscriber.Capacity * 2;
        int received = 0;
        var reader = Task.Run(async () =>
        {
            await foreach (var _ in live.Reader.ReadAllAsync()) received++;
        });
        for (int i = 0; i < total; i++)
        {
            hub.Publish(new[] { (byte)'x' });
            // Keep the live subscriber below capacity so only the stalled one overflows.
            while (live.Reader.CanCount && live.Reader.Count > Subscriber.Capacity / 2) await Task.Yield();
        }

        Assert.True(stalled.IsOverflowed);
        Assert.True(stalled.Overflowed.IsCompletedSuccessfully);
        Assert.False(live.IsOverflowed);
        Assert.Equal(1, hub.Count); // stalled subscriber dropped from the fan-out

        // Stalled reader sees exactly the capacity worth of queued data, then completion (disconnect).
        int stalledCount = 0;
        await foreach (var _ in stalled.Reader.ReadAllAsync()) stalledCount++;
        Assert.Equal(Subscriber.Capacity, stalledCount);

        hub.Unsubscribe(live);
        await reader.WaitAsync(TimeSpan.FromSeconds(10));
        Assert.Equal(total, received);
    }
}

public class VtFrameTests
{
    [Theory]
    [InlineData((ushort)0x0007, "\x1b[0;37;40m")]                 // white on black
    [InlineData((ushort)0x000F, "\x1b[0;97;40m")]                 // bright white
    [InlineData((ushort)0x0004, "\x1b[0;31;40m")]                 // red
    [InlineData((ushort)0x0001, "\x1b[0;34;40m")]                 // blue
    [InlineData((ushort)0x0006, "\x1b[0;33;40m")]                 // red+green = yellow
    [InlineData((ushort)0x001E, "\x1b[0;93;44m")]                 // bright yellow on blue
    [InlineData((ushort)0x00C0, "\x1b[0;30;101m")]                // black on bright red
    [InlineData((ushort)(0x0007 | 0x8000), "\x1b[0;37;40;4m")]    // underscore
    [InlineData((ushort)(0x0007 | 0x4000), "\x1b[0;37;40;7m")]    // reverse
    public void AttrToSgr(ushort attr, string expected) => Assert.Equal(expected, VtFrame.AttrToSgr(attr));

    [Fact]
    public void FrameStructure()
    {
        var chars = "hi  ok  ".ToCharArray();
        var attrs = new ushort[] { 7, 7, 7, 7, 0x0A, 0x0A, 7, 7 };
        var s = Encoding.UTF8.GetString(VtFrame.Build(chars, attrs, 4, 2, 1, 1));
        Assert.StartsWith("\x1b[?25l\x1b[H", s);
        Assert.Contains("\x1b[1;1H\x1b[0;37;40mhi\x1b[0m\x1b[K", s);
        Assert.Contains("\x1b[2;1H\x1b[0;92;40mok\x1b[0m\x1b[K", s);
        Assert.EndsWith("\x1b[2;2H\x1b[?25h", s);
    }

    private static (char[] Chars, ushort[] Attrs) Grid(params string[] rows)
    {
        var chars = new char[8 * rows.Length];
        var attrs = new ushort[chars.Length];
        for (int y = 0; y < rows.Length; y++)
            for (int x = 0; x < 8; x++)
            {
                chars[y * 8 + x] = x < rows[y].Length ? rows[y][x] : ' ';
                attrs[y * 8 + x] = 0x07;
            }
        return (chars, attrs);
    }

    [Fact]
    public void PromptPlaceholderTurnsGrayWhenCursorRightAfterPrompt()
    {
        var (chars, attrs) = Grid("───", "❯ hint1", "hint2", "───", "status");
        attrs[8 + 4] = 0x0A;      // a colored cell keeps its color
        attrs[8 + 5] |= 0x0100;   // leading-byte flag is preserved
        VtFrame.GrayPromptPlaceholder(chars, attrs, 8, 5, 2, 1);
        Assert.Equal(0x07, attrs[8 + 0]);        // prompt itself untouched
        Assert.Equal(0x08, attrs[8 + 2]);
        Assert.Equal(0x0A, attrs[8 + 4]);
        Assert.Equal(0x0108, attrs[8 + 5]);
        Assert.Equal(0x08, attrs[16]);           // wrapped row inside the box
        Assert.Equal(0x07, attrs[24]);           // bottom border
        Assert.Equal(0x07, attrs[32]);           // below the box
    }

    [Fact]
    public void TypedPromptTextKeepsItsColor()
    {
        var (chars, attrs) = Grid("❯ typed", "x");
        VtFrame.GrayPromptPlaceholder(chars, attrs, 8, 2, 7, 0); // cursor at the end of typed text
        Assert.All(attrs, a => Assert.Equal(0x07, a));
        VtFrame.GrayPromptPlaceholder(chars, attrs, 8, 2, 2, 1); // cursor not after the prompt
        Assert.All(attrs, a => Assert.Equal(0x07, a));
    }

    private static readonly int[] ExpectedCandidatePids = { 300, 800, 9804, 35456 };

    [Fact]
    public void ExternalFilterKeepsPersonOpenedAndAgentShells()
    {
        // Mirrors a real snapshot: WT tab running claude, Explorer-launched cmd, plain WT tab, and background shells.
        var procs = new List<ProcEntry>
        {
            new(100, 4, "explorer.exe"),
            new(200, 100, "WindowsTerminal.exe"),
            new(9804, 200, "cmd.exe"), new(9805, 9804, "claude.exe"),
            new(35456, 100, "cmd.exe"),
            new(300, 200, "pwsh.exe"),
            new(59356, 9805, "powershell.exe"),            // claude's tool shell
            new(500, 1, "node.exe"), new(59776, 500, "cmd.exe"), // MCP server
            new(46560, 600, "cmd.exe"),                     // parent gone (native host)
            new(700, 1, "Mpta.QA.exe"), new(800, 700, "cmd.exe"), new(801, 800, "node.exe"), new(802, 801, "codex.exe"),
        };
        var got = ExternalConsoles.FindCandidates(procs, selfPid: 99999).ToDictionary(c => c.Proc.Pid, c => c.Agent);
        Assert.Equal(ExpectedCandidatePids, got.Keys.Order().ToArray());
        Assert.Equal("claude", got[9804]);
        Assert.Equal("gpt", got[800]);
        Assert.Null(got[35456]);
        Assert.Equal(7, ExternalConsoles.FindCandidates(procs, selfPid: 99999, includeAll: true).Count);
    }

    [Theory]
    [InlineData("CLAUDECODE", true)]
    [InlineData("CLAUDE_CODE_CHILD_SESSION", true)]
    [InlineData("claude_code_session_id", true)]
    [InlineData("CLAUDE_PID", true)]
    [InlineData("AI_AGENT", true)]
    [InlineData("CLAUDE_CONFIG_DIR", false)]
    [InlineData("PATH", false)]
    public void SessionShellsDropInheritedAgentVariables(string name, bool dropped) =>
        Assert.Equal(dropped, Mpta.Core.Pty.PseudoConsoleProcess.IsInheritedAgentVariable(name));

    [Fact]
    public void AgentUnderFindsAiCliBelowAHostShell()
    {
        var procs = new List<ProcEntry>
        {
            new(100, 1, "psh.exe"), new(200, 100, "powershell.exe"), new(300, 200, "node.exe"), new(400, 300, "claude.exe"),
            new(500, 100, "cmd.exe"), new(600, 500, "codex.exe"), new(700, 100, "cmd.exe"),
        };
        Assert.Equal("claude", ExternalConsoles.AgentUnder(200, procs));
        Assert.Equal("gpt", ExternalConsoles.AgentUnder(500, procs));
        Assert.Null(ExternalConsoles.AgentUnder(700, procs));
    }

    private static byte[] Frame(params string[] rows)
    {
        var chars = new char[10 * rows.Length];
        var attrs = new ushort[chars.Length];
        for (int y = 0; y < rows.Length; y++)
            for (int x = 0; x < 10; x++)
            {
                chars[y * 10 + x] = x < rows[y].Length ? rows[y][x] : ' ';
                attrs[y * 10 + x] = 0x07;
            }
        return VtFrame.Build(chars, attrs, 10, rows.Length, 0, rows.Length - 2);
    }

    [Fact]
    public void ScrollbackKeepsLinesThatLeftTheWindow()
    {
        var sb = new ConsoleScrollback(maxLines: 3);
        Assert.Equal(0, sb.Accept(Frame("l1", "l2", "l3", "l4", "l5", "l6", "l7", "l8", "> in", "status")));
        Assert.Equal(2, sb.Accept(Frame("l3", "l4", "l5", "l6", "l7", "l8", "l9", "l10", "> in", "status")));
        Assert.Equal(0, sb.Accept(Frame("l3", "l4", "l5", "l6", "l7", "l8", "l9", "l10", "> inx", "status"))); // bottom repaint
        Assert.Equal(2, sb.Accept(Frame("l5", "l6", "l7", "l8", "l9", "l10", "l11", "l12", "> in", "status")));
        var lines = sb.Snapshot();
        Assert.Equal(3, lines.Count); // capped, oldest dropped
        Assert.EndsWith("l4", lines[2]);
        Assert.EndsWith("l2", lines[0]);
        Assert.Equal(-1, sb.Accept(System.Text.Encoding.UTF8.GetBytes("plain")));
        Assert.Contains("\"type\":\"history\"", ControlMessages.History(lines));
    }

    [Fact]
    public void ScrollbackDropsLinesThatCameBackOnScreen()
    {
        var sb = new ConsoleScrollback();
        sb.Accept(Frame("l1", "l2", "l3", "l4", "l5", "l6", "l7", "l8", "> in", "status"));
        Assert.Equal(3, sb.Accept(Frame("l4", "l5", "l6", "l7", "l8", "l9", "l10", "l11", "> in", "status")));
        // Redraw puts l2, l3 back at the top: they are on screen again, not history.
        sb.Accept(Frame("l2", "l3", "x4", "x5", "x6", "x7", "x8", "x9", "> in", "status"));
        Assert.Single(sb.Snapshot());
        Assert.Equal(0, ConsoleScrollback.OverlapWithTop(["a", ""], ["a", "", "z"])); // needs 2 non-blank
    }

    [Fact]
    public void ReaderFrameCarriesConsoleSize()
    {
        var vt = Encoding.UTF8.GetBytes("\x1b[Hok");
        Assert.True(ReaderFrame.TryDecode(ReaderFrame.Encode(120, 30, vt), out int c, out int r, out var body));
        Assert.Equal((120, 30), (c, r));
        Assert.Equal(vt, body);
        Assert.False(ReaderFrame.TryDecode(new byte[3], out _, out _, out _));
        Assert.False(ReaderFrame.TryDecode(ReaderFrame.Encode(0, 30, vt), out _, out _, out _));
    }
}

public class VtInputTranslatorTests
{
    private static List<KeyPress> T(string s) => new VtInputTranslator().Translate(Encoding.UTF8.GetBytes(s));

    [Fact]
    public void PrintableAndHangul()
    {
        var k = T("a한");
        Assert.Equal(2, k.Count);
        Assert.Equal('a', k[0].UnicodeChar);
        Assert.Equal('한', k[1].UnicodeChar);
    }

    [Theory]
    [InlineData("\r", VtInputTranslator.VK_RETURN)]
    [InlineData("\x7f", VtInputTranslator.VK_BACK)]
    [InlineData("\b", VtInputTranslator.VK_BACK)]
    [InlineData("\t", VtInputTranslator.VK_TAB)]
    [InlineData("\x1b", VtInputTranslator.VK_ESCAPE)]
    [InlineData("\x1b[A", VtInputTranslator.VK_UP)]
    [InlineData("\x1b[B", VtInputTranslator.VK_DOWN)]
    [InlineData("\x1b[C", VtInputTranslator.VK_RIGHT)]
    [InlineData("\x1b[D", VtInputTranslator.VK_LEFT)]
    [InlineData("\x1bOA", VtInputTranslator.VK_UP)]
    [InlineData("\x1b[H", VtInputTranslator.VK_HOME)]
    [InlineData("\x1b[F", VtInputTranslator.VK_END)]
    [InlineData("\x1b[5~", VtInputTranslator.VK_PRIOR)]
    [InlineData("\x1b[6~", VtInputTranslator.VK_NEXT)]
    [InlineData("\x1b[3~", VtInputTranslator.VK_DELETE)]
    public void SpecialKeys(string input, ushort vk)
    {
        var k = T(input);
        Assert.Single(k);
        Assert.Equal(vk, k[0].VirtualKey);
    }

    [Fact]
    public void EscPrintableIsAltKey()
    {
        var k = T("\x1bv");
        var kp = Assert.Single(k);
        Assert.Equal('v', kp.UnicodeChar);
        Assert.Equal(Win32.LEFT_ALT_PRESSED, kp.ControlKeyState);
        Assert.Equal(2, T("\x1b\r").Count); // ESC then Enter stays two keys
    }

    [Fact]
    public void CtrlLetters()
    {
        var k = T("\x03\x1a");
        Assert.Equal(2, k.Count);
        Assert.Equal((ushort)'C', k[0].VirtualKey);
        Assert.Equal('\x03', k[0].UnicodeChar);
        Assert.Equal(Win32.LEFT_CTRL_PRESSED, k[0].ControlKeyState);
        Assert.Equal((ushort)'Z', k[1].VirtualKey);
    }

    [Fact]
    public void SplitUtf8AcrossCalls()
    {
        var t = new VtInputTranslator();
        var bytes = Encoding.UTF8.GetBytes("한");
        Assert.Empty(t.Translate(bytes.AsSpan(0, 1)));
        var k = t.Translate(bytes.AsSpan(1));
        Assert.Single(k);
        Assert.Equal('한', k[0].UnicodeChar);
    }

    [Fact]
    public void InputRecordsAreDownUpPairs()
    {
        var recs = VtInputTranslator.ToInputRecords(T("x\r"), vk => 0x1C);
        Assert.Equal(4, recs.Length);
        Assert.All(recs, r => Assert.Equal(Win32.KEY_EVENT, r.EventType));
        Assert.Equal(1, recs[0].KeyEvent.bKeyDown);
        Assert.Equal(0, recs[1].KeyEvent.bKeyDown);
        Assert.Equal('x', recs[0].KeyEvent.UnicodeChar);
        Assert.Equal(VtInputTranslator.VK_RETURN, recs[2].KeyEvent.wVirtualKeyCode);
        Assert.Equal((ushort)0x1C, recs[2].KeyEvent.wVirtualScanCode);
    }
}

public class TokenAuthTests
{
    [Fact]
    public void LocalTokenOnlyFromLoopback()
    {
        var auth = new TokenAuthenticator("LOCAL", new DeviceStore(null));
        Assert.NotNull(auth.Authenticate("LOCAL", IPAddress.Loopback));
        Assert.True(auth.Authenticate("LOCAL", IPAddress.Loopback)!.IsLocal);
        Assert.NotNull(auth.Authenticate("LOCAL", IPAddress.Parse("::ffff:127.0.0.1")));
        Assert.Null(auth.Authenticate("LOCAL", IPAddress.Parse("192.168.0.10")));
        Assert.Null(auth.Authenticate("LOCAL", IPAddress.Parse("100.100.1.2")));
    }

    [Fact]
    public void DeviceTokenValidUntilRevoked()
    {
        var store = new DeviceStore(null);
        var auth = new TokenAuthenticator("LOCAL", store);
        var (dev, token) = store.Add("phone");
        var r = auth.Authenticate(token, IPAddress.Parse("192.168.0.10"));
        Assert.NotNull(r);
        Assert.False(r!.IsLocal);
        Assert.Equal(dev.Id, r.DeviceId);
        Assert.True(store.Revoke(dev.Id));
        Assert.Null(auth.Authenticate(token, IPAddress.Parse("192.168.0.10")));
    }

    [Fact]
    public void UnknownOrMissingTokenRejected()
    {
        var auth = new TokenAuthenticator("LOCAL", new DeviceStore(null));
        Assert.Null(auth.Authenticate("nope", IPAddress.Loopback));
        Assert.Null(auth.Authenticate(null, IPAddress.Loopback));
        Assert.Null(auth.Authenticate("", IPAddress.Loopback));
    }

    [Fact]
    public void DevicesPersistWithoutPlaintextToken()
    {
        var path = Path.Combine(Path.GetTempPath(), "mpta-test-" + Guid.NewGuid().ToString("N"), "devices.json");
        try
        {
            var (_, token) = new DeviceStore(path).Add("phone");
            var text = File.ReadAllText(path);
            Assert.DoesNotContain(token, text);
            Assert.NotNull(new DeviceStore(path).Validate(token));
        }
        finally { try { Directory.Delete(Path.GetDirectoryName(path)!, true); } catch (IOException) { } }
    }

    [Fact]
    public void ParseBearer()
    {
        Assert.Equal("abc", TokenAuthenticator.ParseBearer("Bearer abc"));
        Assert.Null(TokenAuthenticator.ParseBearer("Basic abc"));
        Assert.Null(TokenAuthenticator.ParseBearer(null));
    }
}

public class PairingTests
{
    [Fact]
    public void CodeIsOneTime()
    {
        var p = new PairingManager();
        var (code, _) = p.Start();
        Assert.True(p.TryConsume(code));
        Assert.False(p.TryConsume(code));
    }

    [Fact]
    public void CodeExpiresAfterFiveMinutes()
    {
        var now = DateTimeOffset.Parse("2026-01-01T00:00:00Z", System.Globalization.CultureInfo.InvariantCulture);
        var p = new PairingManager(() => now);
        var (code, exp) = p.Start();
        Assert.Equal(now.AddMinutes(5), exp);
        now = now.AddMinutes(5).AddSeconds(1);
        Assert.False(p.TryConsume(code));
    }

    [Fact]
    public void CodeValidBeforeExpiry()
    {
        var now = DateTimeOffset.Parse("2026-01-01T00:00:00Z", System.Globalization.CultureInfo.InvariantCulture);
        var p = new PairingManager(() => now);
        var (code, _) = p.Start();
        now = now.AddMinutes(4).AddSeconds(59);
        Assert.True(p.TryConsume(code));
    }

    [Fact]
    public void UnknownCodeRejectedAndCodeFormat()
    {
        var p = new PairingManager();
        Assert.False(p.TryConsume("bogus"));
        Assert.False(p.TryConsume(null));
        var (code, _) = p.Start();
        Assert.Equal(43, code.Length); // 32 bytes base64url without padding
        Assert.DoesNotContain('+', code);
        Assert.DoesNotContain('/', code);
    }
}

public class WtTabLauncherTests
{
    private static readonly string[] ExpectedFirstLaunch = { "-w", "0", "nt", "--title", "t\\;x", @"C:\x\mpta.exe", "attach", "s1", "--port", "7380" };

    [Fact]
    public void NoDuplicateWhilePendingAndNotWhenCliPresent()
    {
        var now = DateTime.UtcNow;
        var launched = new List<IReadOnlyList<string>>();
        var l = new WtTabLauncher(@"C:\x\mpta.exe", ["--port", "7380"],() => now, (_, a) => launched.Add(a));
        Assert.False(l.MaybeOpen("s1", "t", hasCliClient: true));
        Assert.True(l.MaybeOpen("s1", "t;x", hasCliClient: false));
        Assert.False(l.MaybeOpen("s1", "t", hasCliClient: false));
        now = now.AddSeconds(16);
        Assert.True(l.MaybeOpen("s1", "t", hasCliClient: false));
        Assert.Equal(2, launched.Count);
        Assert.Equal(ExpectedFirstLaunch, launched[0]);
    }
}

public class AttachInputFilterTests
{
    [Fact]
    public void RawCtrlBracketThenD()
    {
        var f = new AttachInputFilter();
        Assert.Equal(("ab", false), f.Process("ab\x1d"));
        Assert.Equal(("", true), f.Process("d"));
    }

    [Fact]
    public void RawCtrlBracketThenOtherKeyForwardsBoth()
    {
        var f = new AttachInputFilter();
        Assert.Equal(("\x1dx", false), f.Process("\x1dx"));
        Assert.Equal(("\x1d", false), new AttachInputFilter().Process("\x1d\x1d"));
    }

    [Fact]
    public void Win32InputModeSequences()
    {
        var f = new AttachInputFilter();
        // Ctrl down, ']' down with Uc=29, ']' up, Ctrl up, then 'd' down (Uc=100) split across calls.
        var (fwd1, d1) = f.Process("\x1b[17;29;0;1;8;1_\x1b[221;27;29;1;8;1_\x1b[221;27;29;0;8;1_\x1b[17;29;0;0;0;1_\x1b[68;32;1");
        Assert.False(d1);
        Assert.Equal("\x1b[17;29;0;1;8;1_", fwd1); // ctrl-down before the chord passes through
        var (_, d2) = f.Process("00;1;0;1_");
        Assert.True(d2);
    }

    [Fact]
    public void Win32SequencesPassThroughUnchanged()
    {
        var f = new AttachInputFilter();
        var seq = "\x1b[65;30;97;1;0;1_\x1b[65;30;97;0;0;1_\x1b[A";
        Assert.Equal((seq, false), f.Process(seq));
    }
}
