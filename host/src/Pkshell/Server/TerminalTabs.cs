using System.Runtime.InteropServices;
using System.Windows.Automation;
using Pkshell.Core.Util;

namespace Pkshell.Server;

/// <summary>Reads tab names of every Windows Terminal window through UI Automation (what the tab strip shows).</summary>
internal static class TerminalTabs
{
    private static readonly Condition TabItem = new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.TabItem);

    /// <returns>Top-level window handle → tab names in strip order. Empty when WT is not running or UIA fails.</returns>
    public static IReadOnlyDictionary<long, IReadOnlyList<string>> Read()
    {
        var result = new Dictionary<long, IReadOnlyList<string>>();
        var pids = System.Diagnostics.Process.GetProcessesByName("WindowsTerminal");
        try
        {
            foreach (var p in pids)
            {
                try
                {
                    var windows = AutomationElement.RootElement.FindAll(TreeScope.Children,
                        new PropertyCondition(AutomationElement.ProcessIdProperty, p.Id));
                    foreach (AutomationElement w in windows)
                    {
                        var names = new List<string>();
                        foreach (AutomationElement tab in w.FindAll(TreeScope.Descendants, TabItem)) names.Add(tab.Current.Name ?? "");
                        result[w.Current.NativeWindowHandle] = names;
                    }
                }
                catch (Exception ex) when (ex is ElementNotAvailableException or COMException or InvalidOperationException)
                {
                    Log.Warn($"terminal tabs: UI Automation failed for WindowsTerminal {p.Id}: {ex.Message}");
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
