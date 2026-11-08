using System.Diagnostics;
using System.Text;
using System.Text.Json.Nodes;

namespace Pkshell.QA;

/// <summary>Builds/locates pkshell.exe and runs `pkshell serve` as a child inside a kill-on-close job object.</summary>
internal sealed class HostRunner : IDisposable
{
    private readonly string _logPath;
    private readonly object _logLk = new();
    private Process? _proc;
    private Job? _job;

    public string Exe { get; }
    public string Home { get; }
    public int Port { get; }
    public IReadOnlyList<string> Binds { get; }
    public string Token { get; private set; } = "";
    public string Fp { get; private set; } = "";
    public JsonNode? Info { get; private set; }
    public int Starts { get; private set; }

    /// <summary>Extra environment for serve and CLI runs (e.g. PKSHELL_UPDATE_URL).</summary>
    public IReadOnlyDictionary<string, string> ExtraEnv { get; }
    /// <summary>Extra `serve` arguments (e.g. --discovery-port N).</summary>
    public IReadOnlyList<string> ExtraArgs { get; }
    /// <summary>The `pkshell serve` process QA started (after a self-update handoff this one has exited).</summary>
    public Process? Proc => _proc;

    public HostRunner(string exe, string home, int port, IReadOnlyList<string> binds, string logPath,
        IReadOnlyDictionary<string, string>? extraEnv = null, IReadOnlyList<string>? extraArgs = null)
    {
        Exe = exe;
        Home = home;
        Port = port;
        Binds = binds;
        _logPath = logPath;
        ExtraEnv = extraEnv ?? new Dictionary<string, string>();
        ExtraArgs = extraArgs ?? Array.Empty<string>();
    }

    public IDictionary<string, string> Env
    {
        get
        {
            // PKSHELL_EXTERNAL_ALL: QA's external test console is spawned by this driver (not a terminal app), which the
            // default "opened by a person or running an AI CLI" filter would hide.
            var env = new Dictionary<string, string>(ExtraEnv) { ["PKSHELL_HOME"] = Home, ["PKSHELL_EXTERNAL_ALL"] = "1" };
            return env;
        }
    }

    private void Log(string line)
    {
        lock (_logLk) File.AppendAllText(_logPath, line + Environment.NewLine, Encoding.UTF8);
    }

    /// <summary>Default host exe: `dotnet build host/src/Pkshell -c Release` then pick the newest bin/Release/**/pkshell.exe.</summary>
    public static async Task<(string? Exe, string Detail)> BuildDefault(string root, string outDir)
    {
        var proj = Path.Combine(root, "host", "src", "Pkshell");
        if (!Directory.Exists(proj)) return (null, $"host project not found: {proj}");
        var buildLog = Path.Combine(outDir, "host-build.log");
        var (code, output) = await Util.Run("dotnet", new[] { "build", proj, "-c", "Release", "-nologo" }, root, null, 300_000);
        File.WriteAllText(buildLog, output, Encoding.UTF8);
        var binDir = Path.Combine(proj, "bin", "Release");
        if (code != 0)
        {
            var errs = output.Split('\n').Where(l => l.Contains(": error ")).Select(l => l.Trim()).Distinct().Take(3);
            return (null, $"dotnet build exit {code} (see qa/out/host-build.log): {string.Join(" ; ", errs)}");
        }
        if (!Directory.Exists(binDir)) return (null, $"build succeeded but {binDir} missing");
        var exe = Directory.GetFiles(binDir, "psh.exe", SearchOption.AllDirectories)
            .OrderByDescending(File.GetLastWriteTimeUtc).FirstOrDefault();
        return exe == null ? (null, $"pkshell.exe not found under {binDir}") : (exe, "built OK");
    }

    public async Task<string?> Start(int timeoutMs)
    {
        Starts++;
        Log($"==== [{DateTime.Now:O}] start #{Starts}: {Exe} serve --port {Port} {string.Join(" ", Binds.Select(b => "--bind " + b))} {string.Join(" ", ExtraArgs)} (PKSHELL_HOME={Home}) ====");
        _job = new Job();
        var psi = new ProcessStartInfo(Exe)
        {
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            RedirectStandardInput = true,
            CreateNoWindow = true,
            StandardOutputEncoding = Encoding.UTF8,
            StandardErrorEncoding = Encoding.UTF8,
            WorkingDirectory = Home,
        };
        psi.ArgumentList.Add("serve");
        psi.ArgumentList.Add("--port");
        psi.ArgumentList.Add(Port.ToString());
        foreach (var b in Binds)
        {
            psi.ArgumentList.Add("--bind");
            psi.ArgumentList.Add(b);
        }
        foreach (var a in ExtraArgs) psi.ArgumentList.Add(a);
        foreach (var kv in Env) psi.Environment[kv.Key] = kv.Value;
        try
        {
            _proc = Process.Start(psi);
        }
        catch (System.ComponentModel.Win32Exception e)
        {
            return $"failed to start host: {e.Message}";
        }
        if (_proc == null) return "Process.Start returned null";
        if (!_job.Add(_proc.Handle)) Log("[qa] warning: AssignProcessToJobObject failed; will fall back to tree kill");
        _proc.OutputDataReceived += (_, e) => { if (e.Data != null) Log(e.Data); };
        _proc.ErrorDataReceived += (_, e) => { if (e.Data != null) Log("[stderr] " + e.Data); };
        _proc.BeginOutputReadLine();
        _proc.BeginErrorReadLine();

        var sw = Stopwatch.StartNew();
        string last = "token/cert files not created yet";
        var tokenPath = Path.Combine(Home, "local.token");
        var fpPath = Path.Combine(Home, "cert.sha256");
        while (sw.ElapsedMilliseconds < timeoutMs)
        {
            if (_proc.HasExited) return $"host exited early with code {_proc.ExitCode} (see qa/out/host.log)";
            if (File.Exists(tokenPath) && File.Exists(fpPath))
            {
                try
                {
                    Token = File.ReadAllText(tokenPath).Trim();
                    Fp = File.ReadAllText(fpPath).Trim().ToLowerInvariant();
                }
                catch (IOException e)
                {
                    last = "reading token/cert: " + e.Message;
                }
                if (Token.Length > 0 && Fp.Length > 0)
                {
                    using var api = new Api("127.0.0.1", Port, Fp);
                    var r = await api.Get("/api/info", Token);
                    if (r.Ok)
                    {
                        Info = r.Json;
                        Log($"[qa] ready after {sw.ElapsedMilliseconds}ms: {r.Body}");
                        return null;
                    }
                    last = "GET /api/info -> " + r;
                }
            }
            await Task.Delay(300);
        }
        return $"host not ready after {timeoutMs}ms: {last}";
    }

    public void Kill()
    {
        if (_proc == null && _job == null) return;
        Log($"==== [{DateTime.Now:O}] qa kills host (job terminate) ====");
        _job?.Dispose();
        _job = null;
        if (_proc != null)
        {
            try
            {
                if (!_proc.HasExited) _proc.Kill(entireProcessTree: true);
                _proc.WaitForExit(5000);
            }
            catch (InvalidOperationException) { }
            catch (System.ComponentModel.Win32Exception) { }
            _proc.Dispose();
            _proc = null;
        }
    }

    public void Dispose() => Kill();
}
