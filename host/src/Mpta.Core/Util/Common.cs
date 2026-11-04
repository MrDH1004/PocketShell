using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Mpta.Core.Util;

public static class MptaPaths
{
    public static string Home
    {
        get
        {
            var env = Environment.GetEnvironmentVariable("MPTA_HOME");
            if (!string.IsNullOrWhiteSpace(env)) return Path.GetFullPath(env);
            return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "mpta");
        }
    }

    public static string ConfigFile(string home) => Path.Combine(home, "config.json");
    public static string CertFile(string home) => Path.Combine(home, "cert.pfx");
    public static string CertFingerprintFile(string home) => Path.Combine(home, "cert.sha256");
    public static string LocalTokenFile(string home) => Path.Combine(home, "local.token");
    public static string DevicesFile(string home) => Path.Combine(home, "devices.json");
    public static string SessionsDir(string home) => Path.Combine(home, "sessions");
    public static string LogsDir(string home) => Path.Combine(home, "logs");
    public static string PairQrPng(string home) => Path.Combine(home, "pair-qr.png");
}

public sealed class MptaConfig
{
    public const int DefaultPort = 7380;
    public int Port { get; set; } = DefaultPort;

    public static MptaConfig LoadOrCreate(string home)
    {
        var path = MptaPaths.ConfigFile(home);
        if (File.Exists(path))
        {
            try
            {
                var cfg = JsonSerializer.Deserialize<MptaConfig>(File.ReadAllText(path, Encoding.UTF8), Json.Options);
                if (cfg != null && cfg.Port > 0 && cfg.Port < 65536) return cfg;
            }
            catch (JsonException ex)
            {
                Log.Error($"config.json invalid, using defaults: {ex.Message}");
            }
            return new MptaConfig();
        }
        var created = new MptaConfig();
        Directory.CreateDirectory(home);
        File.WriteAllText(path, JsonSerializer.Serialize(created, Json.Options), new UTF8Encoding(false));
        return created;
    }
}

public static class Json
{
    public static readonly JsonSerializerOptions Options = new(JsonSerializerDefaults.Web)
    {
        DefaultIgnoreCondition = JsonIgnoreCondition.Never,
        WriteIndented = false,
    };

    public static readonly JsonSerializerOptions Indented = new(JsonSerializerDefaults.Web)
    {
        DefaultIgnoreCondition = JsonIgnoreCondition.Never,
        WriteIndented = true,
    };
}

public static class Ids
{
    public static string NewHexId(int hexChars = 12)
    {
        var bytes = RandomNumberGenerator.GetBytes((hexChars + 1) / 2);
        return Convert.ToHexString(bytes).ToLowerInvariant()[..hexChars];
    }

    public static string NewToken() => Base64Url(RandomNumberGenerator.GetBytes(32));

    public static string Base64Url(byte[] data) =>
        Convert.ToBase64String(data).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    public static string Sha256Hex(string s) => Sha256Hex(Encoding.UTF8.GetBytes(s));

    public static string Sha256Hex(byte[] data) => Convert.ToHexString(SHA256.HashData(data)).ToLowerInvariant();

    public static string IsoNow() => IsoUtc(DateTimeOffset.UtcNow);

    public static string IsoUtc(DateTimeOffset t) => t.UtcDateTime.ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'", System.Globalization.CultureInfo.InvariantCulture);
}

/// <summary>Simple logger: stdout + logs/host.log (enabled by <see cref="InitFile"/>).</summary>
public static class Log
{
    private static readonly object Gate = new();
    private static StreamWriter? _file;
    public static bool Quiet { get; set; }

    public static void InitFile(string home)
    {
        var dir = MptaPaths.LogsDir(home);
        Directory.CreateDirectory(dir);
        lock (Gate)
        {
            _file = new StreamWriter(new FileStream(Path.Combine(dir, "host.log"), FileMode.Append, FileAccess.Write, FileShare.ReadWrite), new UTF8Encoding(false))
            {
                AutoFlush = true,
            };
        }
    }

    public static void Info(string msg) => Write("INFO", msg);
    public static void Warn(string msg) => Write("WARN", msg);
    public static void Error(string msg) => Write("ERROR", msg);

    private static void Write(string level, string msg)
    {
        var line = $"{DateTime.Now:yyyy-MM-dd HH:mm:ss.fff} [{level}] {msg}";
        lock (Gate)
        {
            if (!Quiet)
            {
                if (level == "ERROR") Console.Error.WriteLine(line); else Console.WriteLine(line);
            }
            try { _file?.WriteLine(line); } catch (IOException) { /* log file unavailable; stdout already has it */ }
        }
    }
}

public static class AtomicFile
{
    public static void WriteAllText(string path, string content)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var tmp = path + ".tmp";
        File.WriteAllText(tmp, content, new UTF8Encoding(false));
        File.Move(tmp, path, overwrite: true);
    }
}
