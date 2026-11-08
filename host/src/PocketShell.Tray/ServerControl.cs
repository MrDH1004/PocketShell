using System.Diagnostics;
using Microsoft.Win32;
using Pkshell.Core.Util;

namespace PocketShell.Tray;

/// <summary>
/// Starts/stops `psh serve` without a console window. "Running" = the serve mutex exists (Brand.ServeMutexName), so a
/// server started elsewhere (CLI, update handoff) is seen too; stop = the serve stop event (graceful shutdown).
/// </summary>
internal sealed class ServerControl
{
    private readonly string _home;
    public string CliPath { get; } = Path.Combine(AppContext.BaseDirectory, Brand.CliExe);
    public int Port { get; private set; }

    public ServerControl(string home)
    {
        _home = home;
        Port = PkshellConfig.LoadOrCreate(home).Port;
    }

    public bool IsRunning
    {
        get
        {
            if (!Mutex.TryOpenExisting(Brand.ServeMutexName(Port), out var m)) return false;
            m.Dispose();
            return true;
        }
    }

    /// <summary>Returns null on success, else an error message.</summary>
    public string? Start()
    {
        Port = PkshellConfig.LoadOrCreate(_home).Port; // config may have changed since the tray started
        if (IsRunning) return null;
        if (!File.Exists(CliPath)) return $"{Brand.CliExe} not found next to {Brand.TrayExe}: {CliPath}";
        var psi = new ProcessStartInfo(CliPath, "serve")
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            WorkingDirectory = AppContext.BaseDirectory,
        };
        try
        {
            using var p = Process.Start(psi);
            if (p == null) return "Process.Start returned null";
            TrayLog.Write(_home, $"started {CliPath} serve pid={p.Id}");
            return null;
        }
        catch (System.ComponentModel.Win32Exception ex)
        {
            return $"cannot start {Brand.CliExe}: {ex.Message}";
        }
    }

    /// <summary>Signals graceful shutdown and waits until the serve mutex is gone. Returns false on timeout.</summary>
    public async Task<bool> StopAsync(TimeSpan timeout)
    {
        if (!IsRunning) return true;
        if (EventWaitHandle.TryOpenExisting(Brand.StopEventName(Port), out var ev))
        {
            using (ev) ev.Set();
            TrayLog.Write(_home, "stop event set");
        }
        else
        {
            TrayLog.Write(_home, "stop event not found (server older than the tray?)");
            return false;
        }
        var sw = Stopwatch.StartNew();
        while (sw.Elapsed < timeout)
        {
            if (!IsRunning) return true;
            await Task.Delay(200);
        }
        return !IsRunning;
    }
}

/// <summary>HKCU Run entry so the tray (and with it the server) starts at Windows sign-in.</summary>
internal static class AutoStart
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private static string Command => $"\"{Path.Combine(AppContext.BaseDirectory, Brand.TrayExe)}\"";

    public static bool IsEnabled
    {
        get
        {
            using var k = Registry.CurrentUser.OpenSubKey(RunKey);
            return k?.GetValue(Brand.Name) is string v && string.Equals(v, Command, StringComparison.OrdinalIgnoreCase);
        }
    }

    public static void Set(bool enabled)
    {
        using var k = Registry.CurrentUser.CreateSubKey(RunKey);
        if (enabled) k.SetValue(Brand.Name, Command);
        else k.DeleteValue(Brand.Name, throwOnMissingValue: false);
    }
}
