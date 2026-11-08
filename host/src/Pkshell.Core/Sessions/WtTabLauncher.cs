using System.Diagnostics;
using Pkshell.Core.Native;
using Pkshell.Core.Util;

namespace Pkshell.Core.Sessions;

/// <summary>
/// AC-21: opens a Windows Terminal tab running `pkshell attach &lt;id&gt;` for a host session when an android client
/// connects and no cli client is attached. At most one pending request per session (15s window).
/// Restores the previous foreground window if launching changed it.
/// </summary>
public sealed class WtTabLauncher
{
    public static readonly TimeSpan PendingWindow = TimeSpan.FromSeconds(15);

    private readonly string _pkshellExe;
    private readonly IReadOnlyList<string> _extraAttachArgs;
    private readonly Dictionary<string, DateTime> _pending = new(StringComparer.Ordinal);
    private readonly object _gate = new();
    private readonly Func<DateTime> _clock;
    private readonly Action<string, IReadOnlyList<string>> _launch;

    public WtTabLauncher(string pkshellExe, IReadOnlyList<string> extraAttachArgs, Func<DateTime>? clock = null, Action<string, IReadOnlyList<string>>? launch = null)
    {
        _pkshellExe = pkshellExe;
        _extraAttachArgs = extraAttachArgs;
        _clock = clock ?? (() => DateTime.UtcNow);
        _launch = launch ?? LaunchWt;
    }

    /// <summary>Returns true if a tab launch was started.</summary>
    public bool MaybeOpen(string sessionId, string title, bool hasCliClient)
    {
        if (hasCliClient) { ClearPending(sessionId); return false; }
        lock (_gate)
        {
            if (_pending.TryGetValue(sessionId, out var since) && _clock() - since < PendingWindow) return false;
            _pending[sessionId] = _clock();
        }
        var args = new List<string> { "-w", "0", "nt", "--title", EscapeWt(title), _pkshellExe, "attach", sessionId };
        args.AddRange(_extraAttachArgs);
        try { _launch(sessionId, args); }
        catch (Exception ex) when (ex is System.ComponentModel.Win32Exception or InvalidOperationException)
        {
            Log.Error($"wt tab launch for {sessionId} failed: {ex.Message}");
            ClearPending(sessionId);
            return false;
        }
        return true;
    }

    public void ClearPending(string sessionId)
    {
        lock (_gate) _pending.Remove(sessionId);
    }

    public bool IsPending(string sessionId)
    {
        lock (_gate) return _pending.TryGetValue(sessionId, out var since) && _clock() - since < PendingWindow;
    }

    // wt treats ';' as a subcommand separator.
    private static string EscapeWt(string s) => s.Replace(";", "\\;");

    private static void LaunchWt(string sessionId, IReadOnlyList<string> args)
    {
        var wt = ShellLauncher.FindOnPath("wt.exe") ?? throw new InvalidOperationException("wt.exe not found on PATH");
        IntPtr before = Win32.GetForegroundWindow();
        var psi = new ProcessStartInfo(wt) { UseShellExecute = false, CreateNoWindow = true };
        foreach (var a in args) psi.ArgumentList.Add(a);
        using var p = Process.Start(psi) ?? throw new InvalidOperationException("wt.exe failed to start");
        Log.Info($"wt tab requested for session {sessionId}: wt {string.Join(' ', args)}");
        // Watch for focus theft for a short while and restore the original foreground window.
        Task.Run(async () =>
        {
            for (int i = 0; i < 20; i++)
            {
                await Task.Delay(100);
                IntPtr now = Win32.GetForegroundWindow();
                if (before != IntPtr.Zero && now != before)
                {
                    if (!Win32.SetForegroundWindow(before))
                        Log.Warn($"SetForegroundWindow restore failed: {Win32.LastError("SetForegroundWindow").Message}");
                    else
                        Log.Info("restored foreground window after wt tab launch");
                    return;
                }
            }
        });
    }
}
