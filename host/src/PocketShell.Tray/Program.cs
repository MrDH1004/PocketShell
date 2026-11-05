using Mpta.Core.Util;

namespace PocketShell.Tray;

internal static class Program
{
    /// <summary>`PocketShell.exe --wait-pid N`: started by a tray restarting after an update; take over once N has exited.</summary>
    public const string WaitPidArg = "--wait-pid";

    [STAThread]
    private static int Main(string[] args)
    {
        int pid = 0;
        bool restarting = args.Length == 2 && string.Equals(args[0], WaitPidArg, StringComparison.Ordinal) && int.TryParse(args[1], out pid);
        if (restarting) WaitForExit(pid);
        using var single = new Mutex(true, @"Local\PocketShell.Tray", out var first);
        // The previous tray has already hidden its icon: keep waiting for it rather than leaving no tray at all.
        if (!first && !(restarting && AcquireAfterPrevious(single))) return 0; // already in the tray

        ApplicationConfiguration.Initialize();
        var home = MptaPaths.Home;
        Directory.CreateDirectory(home);
        try
        {
            Application.Run(new TrayApp(home));
            return 0;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or InvalidOperationException)
        {
            TrayLog.Write(home, "fatal: " + ex);
            MessageBox.Show(ex.Message, Brand.Name, MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 1;
        }
    }

    private static bool AcquireAfterPrevious(Mutex single)
    {
        try
        {
            if (single.WaitOne(TimeSpan.FromSeconds(60))) return true;
            TrayLog.Write(MptaPaths.Home, "previous tray still holds the single-instance mutex after 60s; exiting");
            return false;
        }
        catch (AbandonedMutexException) { return true; } // previous tray exited without releasing it: now ours
    }

    private static void WaitForExit(int pid)
    {
        try
        {
            using var p = System.Diagnostics.Process.GetProcessById(pid);
            if (!p.WaitForExit(30000)) TrayLog.Write(MptaPaths.Home, $"previous tray pid {pid} still running after 30s");
        }
        catch (ArgumentException) { } // already gone
        catch (InvalidOperationException) { }
    }
}

internal static class TrayLog
{
    public static void Write(string home, string line)
    {
        try
        {
            var dir = MptaPaths.LogsDir(home);
            Directory.CreateDirectory(dir);
            File.AppendAllText(Path.Combine(dir, "tray.log"), $"{DateTime.Now:yyyy-MM-dd HH:mm:ss.fff} {line}{Environment.NewLine}");
        }
        catch (IOException) { } // logging must never take the tray down
        catch (UnauthorizedAccessException) { }
    }
}
