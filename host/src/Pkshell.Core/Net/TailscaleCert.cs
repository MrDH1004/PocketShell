using System.Diagnostics;
using System.Net.Security;
using System.Security.Cryptography.X509Certificates;
using System.Text.Json;
using Pkshell.Core.Util;

namespace Pkshell.Core.Net;

/// <summary>
/// Browser client install (0.2.116+): a publicly trusted certificate for this PC's Tailscale name
/// (desktop-x.tailnet.ts.net, Let's Encrypt via `tailscale cert`). Chrome installs a web app (Android "앱 설치",
/// desktop install) and iPhone home screen apps open without a warning only on a trusted certificate; the PC's own
/// self-signed one stays for the phone app, which pins it. Needs MagicDNS + HTTPS enabled for the tailnet.
/// PKSHELL_TAILSCALE_CERT=0 turns it off.
/// </summary>
public sealed class TailscaleCert
{
    /// <summary>
    /// The certificate and what TLS sends: leaf + the intermediates from the PEM. Android Chrome does not fetch a missing
    /// intermediate (desktop Chrome does), so with the leaf alone every request from the phone failed ("Failed to fetch").
    /// </summary>
    public sealed record Issued(string Domain, X509Certificate2 Cert, SslStreamCertificateContext Context);

    private readonly string _dir;
    /// <summary>Given (tests) or null: then looked up on every load, so Tailscale installed after the host started is found.</summary>
    private readonly string? _exe;

    public TailscaleCert(string home, string? exe = null)
    {
        _dir = Path.Combine(home, "tls");
        _exe = exe;
    }

    public static bool Disabled => EnvVars.Get("TAILSCALE_CERT") == "0";

    /// <summary>tailscale.exe from Program Files or PATH; null when Tailscale is not installed.</summary>
    public static string? FindExe()
    {
        foreach (var root in new[] { Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86) })
        {
            if (string.IsNullOrEmpty(root)) continue;
            var p = Path.Combine(root, "Tailscale", "tailscale.exe");
            if (File.Exists(p)) return p;
        }
        foreach (var dir in (Environment.GetEnvironmentVariable("PATH") ?? "").Split(Path.PathSeparator))
        {
            try
            {
                var p = Path.Combine(dir.Trim(), "tailscale.exe");
                if (dir.Trim().Length > 0 && File.Exists(p)) return p;
            }
            catch (ArgumentException) { /* bad PATH entry */ }
        }
        return null;
    }

    /// <summary>The first cert domain from `tailscale status --json` (CertDomains), null without HTTPS for the tailnet.</summary>
    public static string? CertDomain(string statusJson)
    {
        try
        {
            using var doc = JsonDocument.Parse(statusJson);
            if (doc.RootElement.TryGetProperty("CertDomains", out var d) && d.ValueKind == JsonValueKind.Array)
                foreach (var x in d.EnumerateArray())
                    if (x.GetString() is { Length: > 0 } s) return s.TrimEnd('.').ToLowerInvariant();
        }
        catch (JsonException) { }
        return null;
    }

    /// <summary>
    /// Issues or renews (tailscale keeps it and renews near expiry) and loads it; null when Tailscale, MagicDNS or HTTPS
    /// is not available. The reason is in <paramref name="why"/>.
    /// </summary>
    public async Task<Issued?> LoadAsync(Action<string> why, CancellationToken ct)
    {
        if (Disabled) { why("off (PKSHELL_TAILSCALE_CERT=0)"); return null; }
        var exe = _exe ?? FindExe();
        if (exe == null) { why("Tailscale not installed"); return null; }
        var (code, status, err) = await RunAsync(exe, new[] { "status", "--json" }, TimeSpan.FromSeconds(15), ct);
        if (code != 0) { why($"tailscale status failed ({code}): {err.Trim()}"); return null; }
        var domain = CertDomain(status);
        if (domain == null) { why("no cert domain (turn on MagicDNS and HTTPS in the Tailscale admin console)"); return null; }
        Directory.CreateDirectory(_dir);
        var crt = Path.Combine(_dir, "tailscale.crt");
        var key = Path.Combine(_dir, "tailscale.key");
        (code, _, err) = await RunAsync(exe, new[] { "cert", "--cert-file", crt, "--key-file", key, domain }, TimeSpan.FromSeconds(90), ct);
        if (code != 0 && !File.Exists(crt)) { why($"tailscale cert failed ({code}): {err.Trim()}"); return null; }
        try
        {
            using var pem = X509Certificate2.CreateFromPemFile(crt, key);
            // Schannel cannot use the ephemeral key of a PEM load: round-trip through PKCS#12.
            var cert = new X509Certificate2(pem.Export(X509ContentType.Pkcs12), (string?)null, X509KeyStorageFlags.Exportable);
            if (cert.NotAfter.ToUniversalTime() < DateTime.UtcNow) { why($"certificate expired {cert.NotAfter:u}"); cert.Dispose(); return null; }
            var all = new X509Certificate2Collection();
            all.ImportFromPemFile(crt);
            var chain = new X509Certificate2Collection();
            foreach (var c in all)
                if (!string.Equals(c.Thumbprint, cert.Thumbprint, StringComparison.OrdinalIgnoreCase)) chain.Add(c);
            return new Issued(domain, cert, SslStreamCertificateContext.Create(cert, chain, offline: true));
        }
        catch (Exception ex) when (ex is System.Security.Cryptography.CryptographicException or IOException)
        {
            why($"cannot load certificate: {ex.Message}");
            return null;
        }
    }

    private static async Task<(int Code, string Out, string Err)> RunAsync(string exe, string[] args, TimeSpan timeout, CancellationToken ct)
    {
        var psi = new ProcessStartInfo(exe) { RedirectStandardOutput = true, RedirectStandardError = true, UseShellExecute = false, CreateNoWindow = true };
        foreach (var a in args) psi.ArgumentList.Add(a);
        using var p = new Process { StartInfo = psi };
        try { p.Start(); }
        catch (System.ComponentModel.Win32Exception ex) { return (-1, "", ex.Message); }
        var outTask = p.StandardOutput.ReadToEndAsync(ct);
        var errTask = p.StandardError.ReadToEndAsync(ct);
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        cts.CancelAfter(timeout);
        try { await p.WaitForExitAsync(cts.Token); }
        catch (OperationCanceledException)
        {
            try { p.Kill(true); } catch (InvalidOperationException) { }
            return (-2, "", "timed out");
        }
        return (p.ExitCode, await outTask, await errTask);
    }
}
