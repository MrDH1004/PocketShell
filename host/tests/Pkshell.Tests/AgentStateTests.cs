using System.Text;
using Pkshell.Core.Sessions;

namespace Pkshell.Tests;

public class AgentStateTests
{
    private static readonly DateTime T0 = new(2026, 11, 12, 0, 0, 0, DateTimeKind.Utc);

    private static DateTime At(double seconds) => T0.AddSeconds(seconds);

    /// <summary>Screen changes every 0.5 s from <paramref name="from"/> to <paramref name="to"/> (a working agent).</summary>
    private static void Work(ActivityTracker t, double from, double to)
    {
        for (double s = from; s <= to; s += 0.5) t.Change(At(s));
    }

    private const string ClaudePrompt = """
        ● Bash(git push origin main)

        ╭──────────────────────────────────────────────╮
        │ Bash command                                 │
        │                                              │
        │   git push origin main                       │
        │                                              │
        │ Do you want to proceed?                      │
        │ ❯ 1. Yes                                     │
        │   2. Yes, and don't ask again for git push   │
        │   3. No, and tell Claude what to do (esc)    │
        ╰──────────────────────────────────────────────╯
        """;

    [Fact]
    public void WorkingThenQuietWithoutAQuestionIsDone()
    {
        var t = new ActivityTracker();
        Work(t, 0, 10);
        Assert.False(t.Evaluate(At(10), () => ""));
        Assert.Equal(AgentStates.Working, t.State);
        Assert.False(t.Evaluate(At(14), () => "")); // quiet for 4 s: still within the gap
        Assert.True(t.Evaluate(At(17), () => "✻ Cooked for 11s"));
        Assert.Equal(AgentStates.Done, t.State);
        Assert.Null(t.Prompt);
        Assert.False(t.Evaluate(At(18), () => "")); // reported once
    }

    [Fact]
    public void StoppingOnNumberedChoicesIsWaitingWithTheOptions()
    {
        var t = new ActivityTracker();
        Work(t, 0, 8);
        t.Evaluate(At(8), () => "");
        Assert.True(t.Evaluate(At(15), () => ClaudePrompt));
        Assert.Equal(AgentStates.Waiting, t.State);
        Assert.Equal("Do you want to proceed?", t.Prompt!.Question);
        Assert.Equal(new[] { "1", "2", "3" }, t.Prompt.Options.Select(o => o.Key));
        Assert.Equal("Yes", t.Prompt.Options[0].Label);
        Assert.All(t.Prompt.Options, o => Assert.False(o.Enter)); // a number picks at once
    }

    [Fact]
    public void TypingEchoIsNotWork()
    {
        var t = new ActivityTracker();
        // Ten seconds of typing: every screen change follows a key press.
        for (double s = 0; s <= 10; s += 0.5) { t.Input(At(s)); t.Change(At(s + 0.05)); }
        Assert.False(t.Evaluate(At(10.1), () => ""));
        Assert.Null(t.State);
        Assert.False(t.Evaluate(At(20), () => ""));
        Assert.Null(t.State);
    }

    [Fact]
    public void ASingleRedrawIsNotWork()
    {
        var t = new ActivityTracker();
        t.Change(At(0));   // e.g. the status line clock
        t.Change(At(60));
        Assert.False(t.Evaluate(At(61), () => ""));
        Assert.Null(t.State);
    }

    [Fact]
    public void AnAnswerClearsAStoppedStateAndWorkStartsAgain()
    {
        var t = new ActivityTracker();
        Work(t, 0, 8);
        t.Evaluate(At(8), () => "");
        t.Evaluate(At(15), () => ClaudePrompt);
        t.Input(At(20)); // e.g. a mouse move at the PC: nothing on screen changed
        Assert.Equal(AgentStates.Waiting, t.State);
        t.Input(At(21)); // the answer...
        t.Change(At(21.2)); // ...redraws the screen
        Assert.Null(t.State);
        Work(t, 23, 31);
        Assert.False(t.Evaluate(At(31), () => ""));
        Assert.Equal(AgentStates.Working, t.State);
    }

    [Fact]
    public void AnAnsweredPromptLeftOnScreenIsNotAskedAgain()
    {
        // A CLI that only scrolls: the answered question stays on screen above what it prints next.
        const string asked = "Do you want to proceed?\n❯ 1. Yes\n  2. No";
        var t = new ActivityTracker();
        Work(t, 0, 8);
        t.Evaluate(At(8), () => "");
        Assert.True(t.Evaluate(At(15), () => asked));
        Assert.Equal(AgentStates.Waiting, t.State);
        t.Input(At(20));
        t.Change(At(20.2));
        Work(t, 22, 30);
        t.Evaluate(At(30), () => "");
        Assert.True(t.Evaluate(At(37), () => asked + "\n> answered: 1\n* Pushing finished\nAll done."));
        Assert.Equal(AgentStates.Done, t.State);

        // Asked again at the bottom (nothing new under it): a new question.
        Work(t, 40, 48);
        t.Evaluate(At(48), () => "");
        Assert.True(t.Evaluate(At(55), () => "older output\n" + asked));
        Assert.Equal(AgentStates.Waiting, t.State);
    }

    [Fact]
    public void AQuestionWithoutAWorkingSpellIsStillNoticedOnceTheScreenSettles()
    {
        var t = new ActivityTracker();
        t.Input(At(0));          // the user sends a request
        t.Change(At(0.1));       // echo
        t.Change(At(2.0));       // the prompt is drawn in one go
        Assert.False(t.Evaluate(At(3.0), () => ClaudePrompt)); // still settling
        Assert.True(t.Evaluate(At(4.1), () => ClaudePrompt));
        Assert.Equal(AgentStates.Waiting, t.State);
        Assert.False(t.Evaluate(At(5), () => ClaudePrompt));   // reported once
        // A settled screen without a question is nothing to report (typing, an idle prompt).
        var idle = new ActivityTracker();
        idle.Change(At(0));
        Assert.False(idle.Evaluate(At(5), () => "> "));
        Assert.Null(idle.State);
    }

    [Fact]
    public void AChoiceWrappedOverTwoRowsDoesNotHideTheOnesAfterIt()
    {
        // Claude Code v2.1.322, file write approval (110 columns).
        const string screen = """
            ● Write(hello.txt)

            ──────────────────────────────────────────────────────────────────────────────
             Create file
             hello.txt
            ╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌
              1 hi
            ╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌
             Do you want to create hello.txt?
             ❯ 1. Yes
               2. Yes, and switch to accept edits (auto-approve file edits and common file commands) for this session
                  (shift+tab)
               3. No

             Esc to cancel · Tab to amend
            """;
        var p = PromptDetector.Detect(screen);
        Assert.NotNull(p);
        Assert.Equal("Do you want to create hello.txt?", p!.Question);
        Assert.Equal(new[] { "1", "2", "3" }, p.Options.Select(o => o.Key));
        Assert.Equal("No", p.Options[2].Label);
        Assert.StartsWith("Yes, and switch to accept edits", p.Options[1].Label);

        // The highlighted choice is not the first one, and the one above it is wrapped.
        const string second = """
             Pick one
               1. A long first choice that goes on
                  and on
             ❯ 2. Second
               3. Third
            """;
        var q = PromptDetector.Detect(second);
        Assert.NotNull(q);
        Assert.Equal(new[] { "A long first choice that goes on and on", "Second", "Third" }, q!.Options.Select(o => o.Label));
    }

    [Fact]
    public void CodexNumberedPromptIsReadFromItsScreen()
    {
        // Codex 0.166 writes the first choice as one long line and lets the terminal wrap it; the row is full, a
        // colour change follows, and the next choice must still start on a new row.
        var screen = new VtScreen(40, 8);
        var first = "› 1. Update now (runs `" + new string('x', 17) + "     " + new string('y', 20) + "`)" + new string(' ', 13);
        Assert.Equal(80, first.Length);
        screen.Feed(Encoding.UTF8.GetBytes("\r\n\u001b[1m" + first + "\u001b[22m\u001b[27m  2. Skip\u001b[K\r\n  3. Skip until next version\u001b[K\r\n\u001b[K\r\n  enter continue · esc skip"));
        var rows = screen.Text().Split('\n');
        Assert.Equal("  2. Skip", rows[3]);
        var p = PromptDetector.Detect(screen.Text());
        Assert.NotNull(p);
        Assert.Equal(new[] { "1", "2", "3" }, p!.Options.Select(o => o.Key));
        Assert.Equal("Skip", p.Options[1].Label);

        // Codex 0.166 file approval: the question is a few rows above the choices, a path in between.
        const string approval = """
              Would you like to make the following edits?

              Description: Apply proposed file edits
              Destination:
              C:\work\hello.txt


            › 1. Yes, proceed (y)
              2. Yes, and don't ask again for these files (a)
              3. No, and tell Codex what to do differently (esc)

              Press enter to confirm or esc to cancel
            """;
        var a = PromptDetector.Detect(approval);
        Assert.NotNull(a);
        Assert.Equal("Would you like to make the following edits?", a!.Question);
        Assert.Equal(3, a.Options.Count);
    }

    [Fact]
    public void PreviewIsTheLastMessageAboveTheInputBox()
    {
        // Claude Code v2.1.322 right after a turn.
        const string claude = """
            ❯ Create a file named hello.txt containing the word hi. Do not do anything else.

            ● Write(hello.txt)
              ⎿  Wrote 1 line to hello.txt
                  1 hi

            ● hello.txt 만들었어. 내용은 hi.
              다음 줄.

            ✻ Cooked for 5s · done 오전 4:04
                                                                              ◐ medium · /effort
            ─────────────────────────────────────────────────────────────────────────────────────❯
            ❯
            ──────────────────────────────────────────────────────────────────────────────────────
              [OMC#5.8.5L] | Model: Fable 5.2 | effort:medium
              ⏸ manual mode on · ← 2 agents
            """;
        Assert.Equal("hello.txt 만들었어. 내용은 hi.\n다음 줄.", AgentPreview.From(claude));

        // Codex 0.166: no box, the input row holds a placeholder.
        const string codex = """
            › Create a file named hello.txt containing the word hi.


            • Added hello.txt (+1 -0)
                1 +hi

              Worked for 5s • 4:05 AM


            › Ask Codex to do anything

              GPT-5.6-Sol medium · weekly 4% left
            """;
        Assert.Equal("Added hello.txt (+1 -0)\n1 +hi", AgentPreview.From(codex));
        Assert.Null(AgentPreview.From("❯ \n"));
        Assert.Null(AgentPreview.From(""));
    }

    [Fact]
    public void StoppingIsNotAnnouncedWhenTheUserAlreadyTypedTheNextThing()
    {
        var t = new ActivityTracker();
        Work(t, 0, 10);
        t.Evaluate(At(10), () => "");
        t.Input(At(13)); // the agent stopped at 10; the user is typing the next request
        Assert.False(t.Evaluate(At(17), () => "✻ Cooked for 10s"));
        Assert.Null(t.State);
        // A stop with the preview, when nobody typed.
        var quiet = new ActivityTracker();
        Work(quiet, 0, 10);
        quiet.Evaluate(At(10), () => "");
        Assert.True(quiet.Evaluate(At(17), () => "● All tests pass.\n\n❯ "));
        Assert.Equal("All tests pass.", quiet.Preview);
    }

    [Fact]
    public void OnlyTextOnScreenChangesTheFingerprint()
    {
        var s = new VtScreen(20, 4);
        s.Feed(Encoding.UTF8.GetBytes("hello"));
        int before = s.Fingerprint();
        // Codex blinks the window title while it waits; colours and a redraw of the same text change nothing either.
        s.Feed(Encoding.UTF8.GetBytes("\u001b]0;[ ! ] Action Required\a\u001b[1m\u001b[1;1Hhello\u001b[0m"));
        Assert.Equal(before, s.Fingerprint());
        s.Feed(Encoding.UTF8.GetBytes("!"));
        Assert.NotEqual(before, s.Fingerprint());
    }

    [Fact]
    public void ResetForgetsEverything()
    {
        var t = new ActivityTracker();
        Work(t, 0, 8);
        t.Evaluate(At(8), () => "");
        t.Reset(At(9));
        Assert.Null(t.State);
        Assert.False(t.Evaluate(At(20), () => ""));
    }

    [Fact]
    public void ANumberedListInAnAnswerIsNotAPrompt()
    {
        const string answer = """
            봐:
              1. 메뉴(⋮) → uBlock Origin 아이콘
              2. 아무 배너나 터치
              3. 아래쪽 창이 뜨면 슬라이더를 올리기

            ✻ Cooked for 11s
            """;
        Assert.Null(PromptDetector.Detect(answer));
        Assert.Null(PromptDetector.Detect(""));
        Assert.Null(PromptDetector.Detect("❯ 1. only one choice"));
        // The input box of an idle CLI: a marker and what the user is typing, nothing lined up under it.
        Assert.Null(PromptDetector.Detect("─────\n❯ fix the login bug\n─────\n  ? for shortcuts"));
    }

    [Fact]
    public void NumbersMustCountUpFromOne()
    {
        // Not 1..n and no key hint: not a prompt.
        Assert.Null(PromptDetector.Detect("Pick?\n❯ 2. b\n  3. c"));
        var p = PromptDetector.Detect("Allow this command?\n› 1. Yes\n  2. No");
        Assert.NotNull(p);
        Assert.Equal("Allow this command?", p!.Question);
        Assert.Equal(2, p.Options.Count);
    }

    [Fact]
    public void ArrowListWithAKeyHintGivesArrowKeysFromTheHighlightedChoice()
    {
        // Claude Code's folder trust question (no numbers; the first choice is highlighted).
        const string trust = """
             Quick safety check: Is this a project you created or one you trust?

             Security guide

             ❯ No, exit
               Yes, I trust this folder

             Enter to confirm · Esc to cancel
            """;
        var p = PromptDetector.Detect(trust);
        Assert.NotNull(p);
        Assert.Equal(new[] { "No, exit", "Yes, I trust this folder" }, p!.Options.Select(o => o.Label));
        Assert.Equal("", p.Options[0].Key);
        Assert.Equal("\u001b[B", p.Options[1].Key);
        Assert.All(p.Options, o => Assert.True(o.Enter));
        Assert.Equal("Quick safety check: Is this a project you created or one you trust?", p.Question); // the row that asks, not the link above the choices

        // Highlight on the second choice: the first is one arrow up.
        var moved = PromptDetector.Detect("Pick one\n   No, exit\n ❯ Yes, I trust this folder\n\n Enter to confirm · Esc to cancel");
        Assert.Equal("\u001b[A", moved!.Options[0].Key);
        Assert.Equal("", moved.Options[1].Key);

        // The same shape without a key hint is just text.
        Assert.Null(PromptDetector.Detect("Notes\n ❯ first point\n   second point\n"));
    }

    [Fact]
    public void AListFarAboveTheBottomIsOldOutput()
    {
        var old = "Do you want to proceed?\n❯ 1. Yes\n  2. No\n" + string.Join("\n", Enumerable.Range(1, 8).Select(i => $"later line {i}"));
        Assert.Null(PromptDetector.Detect(old));
    }

    [Fact]
    public void StripRemovesEscapeSequences()
    {
        var vt = "\u001b[2K\u001b[1;32m❯ 1. Yes\u001b[0m\r\n\u001b]0;title\u0007  2. No\u001b[?25l";
        Assert.Equal("❯ 1. Yes\n  2. No", AnsiText.Strip(vt));
    }

    private static string Screen(string vt, int cols = 60, int rows = 12)
    {
        var s = new VtScreen(cols, rows);
        s.Feed(Encoding.UTF8.GetBytes(vt));
        return s.Text();
    }

    [Fact]
    public void ScreenPlacesTextWhereTheCursorIs()
    {
        // What a pseudoconsole sends for Claude Code's trust question: rows placed with CUP, spaces as "cursor forward".
        var vt = "\u001b[2;2HSecurity guide\u001b[38;2;177;185;249m\u001b[4;2H❯\u001b[1CNo,\u001b[1Cexit\u001b[m" +
                 "\u001b[5;4HYes,\u001b[1CI\u001b[1Ctrust\u001b[1Cthis\u001b[1Cfolder\u001b[7;2HEnter\u001b[1Cto\u001b[1Cconfirm";
        var text = Screen(vt);
        Assert.Equal("\n Security guide\n\n ❯ No, exit\n   Yes, I trust this folder\n\n Enter to confirm", text);
        var p = PromptDetector.Detect(text);
        Assert.NotNull(p);
        Assert.Equal(2, p!.Options.Count);
    }

    [Fact]
    public void ScreenErasesAndScrolls()
    {
        // A TUI removing its dialog: move up, erase to the end of the screen, write the next thing.
        Assert.Equal("header\nresult", Screen("header\r\nDo you want?\r\n❯ 1. Yes\r\n  2. No\u001b[2A\r\u001b[Jresult"));
        // Erase in line, carriage return overwrite, backspace.
        Assert.Equal("abXY", Screen("abcdef\r\u001b[2C\u001b[KXY"));
        Assert.Equal("spin - done", Screen("spin |\b-\u001b[1Cdone"));
        // More lines than rows: the top scrolls away.
        Assert.Equal("2\n3\n4", Screen("1\r\n2\r\n3\r\n4", cols: 10, rows: 3));
        // Wide characters take two columns, so text placed after them lands where the console put it.
        Assert.Equal("한글 ok", Screen("한글\u001b[1;6Hok"));
        // Sequences cut across chunks, a title, colours and private modes leave no text.
        var s = new VtScreen(20, 3);
        s.Feed(Encoding.UTF8.GetBytes("a\u001b["));
        s.Feed(Encoding.UTF8.GetBytes("31mb\u001b]0;title\u0007\u001b[?25lc"));
        Assert.Equal("abc", s.Text());
    }

    [Fact]
    public async Task EventLogReturnsEventsAfterASeqAndWakesAWaiter()
    {
        var log = new AgentEventLog();
        Assert.Empty(await log.WaitAfterAsync(0, TimeSpan.Zero, CancellationToken.None));
        log.Add(new AgentEventDto { SessionId = "a", State = AgentStates.Done });
        log.Add(new AgentEventDto { SessionId = "b", State = AgentStates.Waiting });
        Assert.Equal(2, log.LastSeq);
        Assert.Equal(new[] { "b" }, log.After(1).Select(e => e.SessionId));

        var waiting = log.WaitAfterAsync(2, TimeSpan.FromSeconds(10), CancellationToken.None);
        Assert.False(waiting.IsCompleted);
        log.Add(new AgentEventDto { SessionId = "c", State = AgentStates.Done });
        var got = await waiting.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.Equal(new[] { "c" }, got.Select(e => e.SessionId));
        Assert.Equal(3, got[0].Seq);
    }
}
