using Mpta.Core.Util;

namespace PocketShell.Tray;

internal static class Program
{
    /// <summary>`PocketShell.exe --wait-pid N`: started by a tray restarting after an update; take over once N has exited.</summary>
    public const string WaitPidArg = "--wait-pid";

    [STAThread]
    private static int Main(string[] args)
    {
        if (args.Length == 2 && args[0] == WaitPidArg && int.TryParse(args[1], out var pid)) WaitForExit(pid);
        using var single = new Mutex(true, @"Local\PocketShell.Tray", out var first);
        if (!first) return 0; // already in the tray

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
