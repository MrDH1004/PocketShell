using Mpta.Core.Util;

namespace PocketShell.Tray;

internal static class Program
{
    [STAThread]
    private static int Main()
    {
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
