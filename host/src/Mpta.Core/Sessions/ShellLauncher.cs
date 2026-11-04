using Mpta.Core.Util;

namespace Mpta.Core.Sessions;

public sealed record ShellLaunch(string Shell, string CommandLine, string WorkingDirectory, Dictionary<string, string> Env);

/// <summary>Resolves a shell name to a command line + environment per DESIGN §3.</summary>
public static class ShellLauncher
{
    public static string UserHome => Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);

    public static string? FindOnPath(string exe)
    {
        var path = Environment.GetEnvironmentVariable("PATH") ?? "";
        foreach (var dir in path.Split(';', StringSplitOptions.RemoveEmptyEntries))
        {
            try
            {
                var full = Path.Combine(dir.Trim().Trim('"'), exe);
                if (File.Exists(full)) return full;
            }
            catch (ArgumentException) { /* malformed PATH entry */ }
        }
        return null;
    }

    public static string DefaultShell() => FindOnPath("pwsh.exe") != null ? "pwsh" : "powershell";

    private static string? FindGitBash()
    {
        foreach (var root in new[] { Environment.GetEnvironmentVariable("ProgramFiles"), Environment.GetEnvironmentVariable("ProgramW6432"), Environment.GetEnvironmentVariable("ProgramFiles(x86)") })
        {
            if (string.IsNullOrEmpty(root)) continue;
            var p = Path.Combine(root, "Git", "bin", "bash.exe");
            if (File.Exists(p)) return p;
        }
        var onPath = FindOnPath("bash.exe");
        // System32\bash.exe is the WSL launcher; prefer it only for "wsl".
        if (onPath != null && !onPath.Contains("System32", StringComparison.OrdinalIgnoreCase)) return onPath;
        return null;
    }

    // Prompt wrapper: prefixes the existing prompt with OSC 9;9;<cwd> ST. Contains no double quotes so it can be passed quoted.
    internal const string PwshPromptScript =
        "$global:__mptaOrigPrompt = $function:prompt; " +
        "function global:prompt { $e = [char]27; $o = & $global:__mptaOrigPrompt; ($e + ']9;9;' + $PWD.ProviderPath + $e + '\\') + $o }";

    internal const string BashPromptCommand = "printf '\\e]7;file://%s%s\\e\\\\' \"$HOSTNAME\" \"$PWD\"";

    public static ShellLaunch Resolve(string? shell, string? cwd)
    {
        shell = string.IsNullOrWhiteSpace(shell) ? DefaultShell() : shell.Trim();
        var env = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        string workDir = !string.IsNullOrWhiteSpace(cwd) && Directory.Exists(cwd) ? cwd! : UserHome;
        string key = shell.ToLowerInvariant();

        switch (key)
        {
            case "cmd":
                env["PROMPT"] = "$E]9;9;$P$E\\$P$G";
                return new ShellLaunch("cmd", "cmd.exe", workDir, env);

            case "pwsh":
            case "powershell":
            {
                string exe;
                string actual = key;
                if (key == "pwsh")
                {
                    exe = FindOnPath("pwsh.exe") ?? "";
                    if (exe.Length == 0)
                    {
                        Log.Warn("pwsh.exe not found on PATH; falling back to powershell.exe");
                        exe = "powershell.exe";
                        actual = "powershell";
                    }
                }
                else exe = "powershell.exe";
                var cmdLine = $"{Quote(exe)} -NoLogo -NoExit -Command \"{PwshPromptScript}\"";
                return new ShellLaunch(actual, cmdLine, workDir, env);
            }

            case "bash":
            {
                var bash = FindGitBash() ?? throw new InvalidOperationException("Git Bash (bash.exe) not found");
                env["PROMPT_COMMAND"] = BashPromptCommand;
                env["CHERE_INVOKING"] = "1"; // keep the working directory with --login
                return new ShellLaunch("bash", $"{Quote(bash)} --login -i", workDir, env);
            }

            case "wsl":
            {
                env["PROMPT_COMMAND"] = BashPromptCommand;
                var existing = Environment.GetEnvironmentVariable("WSLENV");
                env["WSLENV"] = string.IsNullOrEmpty(existing) ? "PROMPT_COMMAND/u" : existing + ":PROMPT_COMMAND/u";
                // A POSIX cwd (tracked through OSC 7) is passed to wsl via --cd; Windows cwd works as process cwd.
                if (!string.IsNullOrWhiteSpace(cwd) && cwd!.StartsWith('/'))
                    return new ShellLaunch("wsl", $"wsl.exe --cd {Quote(cwd)}", UserHome, env);
                return new ShellLaunch("wsl", "wsl.exe", workDir, env);
            }

            default:
                return new ShellLaunch(shell, shell.Contains(' ') && !shell.StartsWith('"') ? Quote(shell) : shell, workDir, env);
        }
    }

    public static string Quote(string arg)
    {
        if (arg.Length > 0 && arg.IndexOfAny(new[] { ' ', '\t', '"' }) < 0) return arg;
        var sb = new System.Text.StringBuilder("\"");
        int backslashes = 0;
        foreach (var ch in arg)
        {
            if (ch == '\\') { backslashes++; continue; }
            if (ch == '"') { sb.Append('\\', backslashes * 2 + 1).Append('"'); backslashes = 0; continue; }
            sb.Append('\\', backslashes).Append(ch);
            backslashes = 0;
        }
        sb.Append('\\', backslashes * 2).Append('"');
        return sb.ToString();
    }
}
