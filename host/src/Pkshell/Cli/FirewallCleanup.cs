using System.ComponentModel;
using System.Diagnostics;

namespace Pkshell.Cli;

/// <summary>
/// `psh firewall-cleanup`: removes Windows Firewall rules left by earlier copies of this program. Windows adds an
/// "allow" rule per program path the first time a program listens, and never removes it: rules for the old "mpta"
/// name, for moved or deleted installs and for test builds pile up. A rule is stale when its program is this app's
/// (mpta.exe / psh.exe / PocketShell.exe) and the file is gone, or it is the old mpta.exe. The rule of the running
/// install is never touched (its file exists). Removing rules needs administrator rights: Windows asks (UAC).
/// </summary>
public static class FirewallCleanup
{
    /// <summary>PowerShell pipeline that yields the stale rules' application filters.</summary>
    private const string StaleFilters =
        @"Get-NetFirewallApplicationFilter | Where-Object { $_.Program -match '\\(mpta|psh|PocketShell)\.exe$' -and " +
        @"($_.Program -match '\\mpta\.exe$' -or -not (Test-Path -LiteralPath $_.Program)) }";

    public static int Run(bool listOnly)
    {
        var stale = List();
        if (stale == null) return 1;
        if (stale.Count == 0)
        {
            Console.WriteLine("no stale firewall rules");
            return 0;
        }
        Console.WriteLine($"stale firewall rules ({stale.Count}):");
        foreach (var program in stale.Distinct(StringComparer.OrdinalIgnoreCase)) Console.WriteLine("  " + program);
        if (listOnly) return 0;

        Console.WriteLine("removing them (Windows asks for administrator rights)...");
        try
        {
            var psi = new ProcessStartInfo("powershell.exe", $"-NoProfile -WindowStyle Hidden -Command \"{StaleFilters} | Get-NetFirewallRule | Remove-NetFirewallRule\"")
            {
                UseShellExecute = true, // required for the "runas" verb
                Verb = "runas",
                WindowStyle = ProcessWindowStyle.Hidden,
            };
            using var p = Process.Start(psi);
            if (p == null) { Console.Error.WriteLine("psh: could not start PowerShell"); return 1; }
            p.WaitForExit();
        }
        catch (Win32Exception ex) when (ex.NativeErrorCode == 1223) // ERROR_CANCELLED: the UAC prompt was declined
        {
            Console.Error.WriteLine("psh: cancelled (administrator rights were not given); nothing removed");
            return 1;
        }
        catch (Win32Exception ex)
        {
            Console.Error.WriteLine($"psh: could not start PowerShell as administrator: {ex.Message}");
            return 1;
        }
        var left = List();
        if (left == null) return 1;
        Console.WriteLine(left.Count == 0 ? $"removed {stale.Count} rule(s)" : $"removed {stale.Count - left.Count} rule(s); {left.Count} left");
        return left.Count == 0 ? 0 : 1;
    }

    /// <summary>Program paths of the stale rules (one per rule), or null when PowerShell could not be run.</summary>
    private static List<string>? List()
    {
        try
        {
            var psi = new ProcessStartInfo("powershell.exe", $"-NoProfile -Command \"{StaleFilters} | ForEach-Object {{ $_.Program }}\"")
            {
                UseShellExecute = false,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                CreateNoWindow = true,
            };
            using var p = Process.Start(psi);
            if (p == null) { Console.Error.WriteLine("psh: could not start PowerShell"); return null; }
            var errTask = p.StandardError.ReadToEndAsync();
            var text = p.StandardOutput.ReadToEnd();
            p.WaitForExit();
            var err = errTask.GetAwaiter().GetResult();
            if (p.ExitCode != 0 && err.Trim().Length > 0)
            {
                Console.Error.WriteLine("psh: reading firewall rules failed: " + err.Trim());
                return null;
            }
            return text.Split('\n').Select(l => l.Trim()).Where(l => l.Length > 0).ToList();
        }
        catch (Win32Exception ex)
        {
            Console.Error.WriteLine($"psh: could not start PowerShell: {ex.Message}");
            return null;
        }
    }
}
