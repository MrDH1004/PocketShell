using System.Runtime.InteropServices;
using System.Windows.Automation;
using Pkshell.Core.Native;
using Pkshell.Core.Util;

namespace Pkshell.Server;

/// <summary>Reads tab names of every Windows Terminal window through UI Automation (what the tab strip shows).</summary>
internal static class TerminalTabs
{
    private static readonly Condition TabItem = new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.TabItem);

    /// <summary>Window class of a Windows Terminal top-level window.</summary>
    private const string WtWindowClass = "CASCADIA_HOSTING_WINDOW_CLASS";

    /// <returns>Top-level window handle → tab names in strip order. Empty when WT is not running or UIA fails.</returns>
    public static IReadOnlyDictionary<long, IReadOnlyList<string>> Read()
    {
        var result = new Dictionary<long, IReadOnlyList<string>>();
        var pids = System.Diagnostics.Process.GetProcessesByName("WindowsTerminal");
        try
        {
            foreach (var p in pids)
            {
                // WT windows are found with EnumWindows, not by searching the UI Automation root's children: that
                // search asks every top-level window, and one hung app (seen: Tailscale's tray window "응답 없음")
                // stalls it ~15 s per call — longer than the phone's read timeout for GET /api/sessions.
                foreach (var hwnd in Win32.TopLevelWindowsOf(p.Id, WtWindowClass))
                {
                    try
                    {
                        var w = AutomationElement.FromHandle(hwnd);
                        var names = new List<string>();
                        foreach (AutomationElement tab in w.FindAll(TreeScope.Descendants, TabItem)) names.Add(tab.Current.Name ?? "");
                        result[hwnd.ToInt64()] = names;
                    }
                    catch (Exception ex) when (ex is ElementNotAvailableException or COMException or InvalidOperationException or ArgumentException)
                    {
                        Log.Warn($"terminal tabs: UI Automation failed for WindowsTerminal {p.Id} window {hwnd}: {ex.Message}");
                    }
                }
            }
        }
        finally
        {
            foreach (var p in pids) p.Dispose();
        }
        return result;
    }
}
