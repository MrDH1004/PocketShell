namespace Pkshell.Core.External;

/// <summary>
/// Last activity of external consoles (they have no input/output stream of ours unless bridged). A console is active
/// when its title or folder changes (AI CLIs animate the title while working) or, for watched consoles, when the
/// screen changes (<see cref="ConsoleScrollback.LastChange"/>). First sight = process start time.
/// All times are ISO UTC strings from Ids, so they compare ordinally.
/// </summary>
public sealed class ConsoleActivity
{
    private readonly Dictionary<int, (string Sig, string Time)> _seen = new();
    private readonly object _lock = new();

    /// <param name="pid">console process</param>
    /// <param name="signature">title + folder: a change counts as activity</param>
    /// <param name="startTime">process start (first sight), may be empty</param>
    /// <param name="screenChange">last screen change if the console is watched</param>
    /// <param name="now">current time</param>
    public string Observe(int pid, string signature, string startTime, string? screenChange, string now)
    {
        string time;
        lock (_lock)
        {
            if (!_seen.TryGetValue(pid, out var e)) time = string.IsNullOrEmpty(startTime) ? now : startTime;
            else time = e.Sig == signature ? e.Time : now;
            _seen[pid] = (signature, time);
        }
        return screenChange != null && string.CompareOrdinal(screenChange, time) > 0 ? screenChange : time;
    }

    /// <summary>Forgets consoles that are gone.</summary>
    public void Prune(IReadOnlyCollection<int> alive)
    {
        lock (_lock)
        {
            foreach (var pid in _seen.Keys.Where(p => !alive.Contains(p)).ToList()) _seen.Remove(pid);
        }
    }
}
