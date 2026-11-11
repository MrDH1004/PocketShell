using System.Text;
using System.Text.RegularExpressions;

namespace Pkshell.Core.Sessions;

/// <summary>What an AI CLI session is doing, as far as its screen tells (DESIGN §14).</summary>
public static class AgentStates
{
    /// <summary>The screen keeps changing on its own: the agent is working.</summary>
    public const string Working = "working";
    /// <summary>It stopped on a question with choices (approval prompt).</summary>
    public const string Waiting = "waiting";
    /// <summary>It stopped and shows no question: the turn is finished.</summary>
    public const string Done = "done";
}

public sealed class PromptOptionDto
{
    /// <summary>Keys that pick it: its number ("2"), or arrow keys from the highlighted choice (may be empty).</summary>
    public string Key { get; set; } = "";
    /// <summary>Press Enter after <see cref="Key"/> (lists picked with arrows; a number picks at once).</summary>
    public bool Enter { get; set; }
    public string Label { get; set; } = "";
}

/// <summary>A question the agent is waiting on.</summary>
public sealed class PromptDto
{
    public string Question { get; set; } = "";
    public List<PromptOptionDto> Options { get; set; } = new();
    /// <summary>Rows with text under the choices when it was read (tells a fresh prompt from one left above newer output).</summary>
    [System.Text.Json.Serialization.JsonIgnore]
    public int RowsBelow { get; set; }

    /// <summary>The same question with the same choices.</summary>
    public bool SameAs(PromptDto other) =>
        Question == other.Question && Options.Select(o => o.Label).SequenceEqual(other.Options.Select(o => o.Label));
}

/// <summary>One state change worth telling the phone about (GET /api/events).</summary>
public sealed class AgentEventDto
{
    public long Seq { get; set; }
    public string SessionId { get; set; } = "";
    public string Title { get; set; } = "";
    public string? Agent { get; set; }
    public string State { get; set; } = "";
    public PromptDto? Prompt { get; set; }
    public string Time { get; set; } = "";
}

/// <summary>Plain text of terminal output: escape sequences and control characters removed.</summary>
public static partial class AnsiText
{
    [GeneratedRegex(@"\x1b\[[0-9;?<>=]*[ -/]*[@-~]|\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)|\x1b[()][0-9A-Za-z]|\x1b[=>78DEHM]")]
    private static partial Regex Escape();

    public static string Strip(string vt)
    {
        var s = Escape().Replace(vt, "");
        var sb = new StringBuilder(s.Length);
        foreach (var c in s)
        {
            if (c == '\r') continue;
            if (c == '\n' || c == '\t' || c >= ' ') sb.Append(c);
        }
        return sb.ToString();
    }
}

/// <summary>
/// Finds the choice list an AI CLI stopped on, at the bottom of a screen (rows as text, indentation kept). The CLI marks
/// the highlighted choice ("❯ 1. Yes"); the other choices line up under its text. Two kinds exist:
/// numbered ("1. Yes / 2. No": typing the number picks it) and plain ("❯ No, exit / Yes, I trust this folder" with
/// an "Enter to confirm" hint: arrows move, Enter picks). A numbered list in an answer has no marker and is not one.
/// </summary>
public static partial class PromptDetector
{
    private const int LookBackRows = 40;
    /// <summary>Rows with text allowed under the choices (key hints, a status line); more means the list is old output.</summary>
    private const int MaxRowsBelow = 6;
    private const int MaxLabel = 60;
    private const string ArrowDown = "\u001b[B";
    private const string ArrowUp = "\u001b[A";

    [GeneratedRegex(@"^(?<indent>\s*)[❯›▶▸]\s+(?<text>\S.*)$")]
    private static partial Regex MarkedRow();

    [GeneratedRegex(@"^(?<n>[1-9])[.)]\s+(?<label>\S.*)$")]
    private static partial Regex Numbered();

    [GeneratedRegex(@"enter to |esc to |↑|↓|arrow keys", RegexOptions.IgnoreCase)]
    private static partial Regex KeyHint();

    public static PromptDto? Detect(string screenText)
    {
        var rows = screenText.Split('\n').Select(Unbox).ToList();
        int first = Math.Max(0, rows.Count - LookBackRows);
        for (int m = rows.Count - 1; m >= first; m--)
        {
            var marked = MarkedRow().Match(rows[m]);
            if (!marked.Success) continue;
            int textCol = marked.Groups["text"].Index;
            // Choices: rows right above and below whose text starts in the same column as the highlighted one.
            int top = m, bottom = m;
            while (top - 1 >= first && StartsAt(rows[top - 1], textCol)) top--;
            while (bottom + 1 < rows.Count && StartsAt(rows[bottom + 1], textCol)) bottom++;
            var texts = new List<string>();
            for (int r = top; r <= bottom; r++) texts.Add(r == m ? marked.Groups["text"].Value.Trim() : rows[r].Trim());
            if (texts.Count < 2) return null;
            var below = rows.Skip(bottom + 1).Where(HasText).ToList();
            if (below.Count > MaxRowsBelow) return null;

            var options = new List<PromptOptionDto>();
            bool numbered = true;
            for (int i = 0; i < texts.Count && numbered; i++)
            {
                var n = Numbered().Match(texts[i]);
                numbered = n.Success && n.Groups["n"].Value == (i + 1).ToString(System.Globalization.CultureInfo.InvariantCulture);
                if (numbered) options.Add(new PromptOptionDto { Key = n.Groups["n"].Value, Label = Short(n.Groups["label"].Value) });
            }
            if (!numbered)
            {
                // Picked with arrows: only a list that says so (the key hint under it) is taken for a prompt.
                if (!below.Take(3).Any(b => KeyHint().IsMatch(b))) return null;
                options.Clear();
                int at = m - top;
                for (int i = 0; i < texts.Count; i++)
                {
                    var keys = i >= at ? string.Concat(Enumerable.Repeat(ArrowDown, i - at)) : string.Concat(Enumerable.Repeat(ArrowUp, at - i));
                    options.Add(new PromptOptionDto { Key = keys, Enter = true, Label = Short(texts[i]) });
                }
            }
            string question = "";
            for (int q = top - 1; q >= Math.Max(first, top - 8); q--)
            {
                if (!HasText(rows[q])) continue;
                question = Short(rows[q].Trim(), 160);
                break;
            }
            return new PromptDto { Question = question, Options = options, RowsBelow = below.Count };
        }
        return null;
    }

    private static string Short(string s, int max = MaxLabel) => s.Length > max ? s[..max].TrimEnd() + "…" : s.Trim();

    private static bool StartsAt(string row, int col) => row.Length > col && row[col] != ' ' && row[..col].All(c => c == ' ');

    /// <summary>A row with words on it (not blank, not just the lines of a box).</summary>
    private static bool HasText(string row) => row.Any(c => char.IsLetterOrDigit(c));

    /// <summary>Removes the vertical box border CLIs draw around dialogs, keeping the indentation inside it.</summary>
    private static string Unbox(string row)
    {
        var s = row.TrimEnd();
        if (s.Length > 0 && s[^1] is '│' or '┃') s = s[..^1].TrimEnd();
        int i = 0;
        while (i < s.Length && s[i] == ' ') i++;
        if (i < s.Length && s[i] is '│' or '┃') s = s[..i] + " " + s[(i + 1)..];
        return s;
    }
}

/// <summary>
/// Decides working / stopped from when the screen changes. An AI CLI redraws its status line while it works and is
/// still when it waits, whatever words it shows (those change between versions). Changes right after input are the echo
/// of typing, not work. Not thread-safe: callers lock.
/// </summary>
public sealed class ActivityTracker
{
    /// <summary>A change this soon after input is the echo of that input.</summary>
    public static readonly TimeSpan EchoWindow = TimeSpan.FromSeconds(1.5);
    /// <summary>No change for this long = stopped.</summary>
    public static readonly TimeSpan QuietGap = TimeSpan.FromSeconds(6);
    /// <summary>Changes must keep coming for this long (and <see cref="MinChanges"/> times) to count as working.</summary>
    public static readonly TimeSpan MinWork = TimeSpan.FromSeconds(3);
    public const int MinChanges = 3;
    /// <summary>The screen must be still this long before it is read for a question that came without a working spell.</summary>
    public static readonly TimeSpan SettleTime = TimeSpan.FromSeconds(2);

    private DateTime _lastChange = DateTime.MinValue;
    private DateTime _lastRead = DateTime.MinValue;
    private DateTime _lastInput = DateTime.MinValue;
    private DateTime _workStart = DateTime.MinValue;
    private DateTime _lastWork = DateTime.MinValue;
    private int _changes;

    public string? State { get; private set; }
    public DateTime StateSince { get; private set; }
    public PromptDto? Prompt { get; private set; }
    /// <summary>The prompt that was answered last. A CLI that only scrolls leaves it on screen above what follows.</summary>
    private PromptDto? _answered;

    /// <summary>Input was written to the session (phone, PC tab, or the PC keyboard for a console we only watch).</summary>
    public void Input(DateTime now) => _lastInput = now;

    /// <summary>The screen changed.</summary>
    public void Change(DateTime now)
    {
        _lastChange = now;
        if (now - _lastInput < EchoWindow)
        {
            // Someone answered the prompt or typed after a finished turn: the stopped state no longer describes the
            // screen. (Input alone, e.g. a mouse move at the PC, changes nothing and keeps the state.)
            if (State is AgentStates.Waiting or AgentStates.Done)
            {
                if (Prompt != null) _answered = Prompt;
                Set(null, now, null);
            }
            return;
        }
        if (now - _lastWork > QuietGap) { _workStart = now; _changes = 0; }
        _changes++;
        _lastWork = now;
    }

    /// <summary>
    /// Re-evaluates the state; returns true when the session just stopped (working → waiting / done), which is the
    /// moment to notify. <paramref name="screenText"/> is only read then.
    /// </summary>
    public bool Evaluate(DateTime now, Func<string> screenText)
    {
        bool working = now - _lastWork <= QuietGap && _changes >= MinChanges && _lastWork - _workStart >= MinWork;
        if (working)
        {
            if (State != AgentStates.Working) Set(AgentStates.Working, now, null);
            return false;
        }
        if (State == AgentStates.Working)
        {
            _lastRead = _lastChange;
            var prompt = ReadPrompt(screenText());
            Set(prompt != null ? AgentStates.Waiting : AgentStates.Done, now, prompt);
            return true;
        }
        // A question can also come at once, with no working spell before it (a quick approval prompt, the folder
        // trust question at start): once the screen has settled after a change, read it.
        if (State == AgentStates.Waiting || _lastChange <= _lastRead || now - _lastChange < SettleTime) return false;
        _lastRead = _lastChange;
        var asked = ReadPrompt(screenText());
        if (asked == null) return false;
        Set(AgentStates.Waiting, now, asked);
        return true;
    }

    private PromptDto? ReadPrompt(string screen)
    {
        var prompt = PromptDetector.Detect(screen);
        // The answered prompt, now with more output under it than when it was asked: old text, not a new question.
        if (prompt != null && _answered != null && prompt.SameAs(_answered) && prompt.RowsBelow > _answered.RowsBelow) return null;
        if (prompt != null) _answered = null;
        return prompt;
    }

    /// <summary>The agent is no longer running in this session.</summary>
    public void Reset(DateTime now)
    {
        if (State != null) Set(null, now, null);
        _changes = 0;
        _lastWork = DateTime.MinValue;
    }

    private void Set(string? state, DateTime now, PromptDto? prompt)
    {
        State = state;
        StateSince = now;
        Prompt = prompt;
    }
}

/// <summary>Recent state changes for long-polling phones. <see cref="Epoch"/> changes when the host restarts.</summary>
public sealed class AgentEventLog
{
    public const int Capacity = 100;
    private readonly object _lock = new();
    private readonly LinkedList<AgentEventDto> _events = new();
    private long _seq;
    private TaskCompletionSource _signal = new(TaskCreationOptions.RunContinuationsAsynchronously);

    public string Epoch { get; } = Util.Ids.NewHexId(8);

    public long LastSeq { get { lock (_lock) return _seq; } }

    public void Add(AgentEventDto e)
    {
        TaskCompletionSource fired;
        lock (_lock)
        {
            e.Seq = ++_seq;
            _events.AddLast(e);
            while (_events.Count > Capacity) _events.RemoveFirst();
            fired = _signal;
            _signal = new(TaskCreationOptions.RunContinuationsAsynchronously);
        }
        fired.TrySetResult();
    }

    public List<AgentEventDto> After(long seq)
    {
        lock (_lock) return _events.Where(e => e.Seq > seq).ToList();
    }

    /// <summary>Events after <paramref name="seq"/>, waiting up to <paramref name="wait"/> for the first one.</summary>
    public async Task<List<AgentEventDto>> WaitAfterAsync(long seq, TimeSpan wait, CancellationToken ct)
    {
        Task signal;
        lock (_lock)
        {
            var now = _events.Where(e => e.Seq > seq).ToList();
            if (now.Count > 0 || wait <= TimeSpan.Zero) return now;
            signal = _signal.Task;
        }
        try { await signal.WaitAsync(wait, ct); }
        catch (TimeoutException) { }
        return After(seq);
    }
}
