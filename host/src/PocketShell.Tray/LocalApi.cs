using System.Net.Http.Headers;
using System.Net.Security;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using Pkshell.Core.Net;
using Pkshell.Core.Util;

namespace PocketShell.Tray;

/// <summary>
/// Calls the local `psh serve` API with the local token over HTTPS, trusting only this host's own certificate
/// (fingerprint in cert.sha256).
/// </summary>
internal static class LocalApi
{
    public static async Task<(int Status, JsonElement Body)> SendAsync(string home, int port, HttpMethod method, string path, string? json = null, TimeSpan? timeout = null)
    {
        var token = File.ReadAllText(PkshellPaths.LocalTokenFile(home), Encoding.UTF8).Trim();
        var fp = File.ReadAllText(PkshellPaths.CertFingerprintFile(home), Encoding.UTF8).Trim().ToLowerInvariant();
        using var handler = new SocketsHttpHandler();
        handler.SslOptions.RemoteCertificateValidationCallback =
            (object _, X509Certificate? cert, X509Chain? _, SslPolicyErrors _) =>
                cert != null && string.Equals(CertManager.Fingerprint(cert), fp, StringComparison.Ordinal);
        using var http = new HttpClient(handler) { Timeout = timeout ?? TimeSpan.FromSeconds(10) };
        using var req = new HttpRequestMessage(method, $"https://127.0.0.1:{port}/{path}");
        if (json != null) req.Content = new StringContent(json, Encoding.UTF8, "application/json");
        req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
        using var resp = await http.SendAsync(req);
        var text = await resp.Content.ReadAsStringAsync();
        JsonElement body;
        try
        {
            using var doc = JsonDocument.Parse(text.Length > 0 ? text : "{}");
            body = doc.RootElement.Clone();
        }
        catch (JsonException)
        {
            using var doc = JsonDocument.Parse(JsonSerializer.Serialize(new { error = text }));
            body = doc.RootElement.Clone();
        }
        return ((int)resp.StatusCode, body);
    }

    public static string Str(JsonElement e, string name) =>
        e.ValueKind == JsonValueKind.Object && e.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() ?? "" : "";
}
