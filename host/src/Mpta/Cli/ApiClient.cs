using System.Net.Http.Headers;
using System.Net.Security;
using System.Net.WebSockets;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using Mpta.Core.Net;
using Mpta.Core.Util;

namespace Mpta.Cli;

/// <summary>Local CLI → host client. Uses local.token and pins the server certificate by cert.sha256.</summary>
public sealed class ApiClient : IDisposable
{
    private readonly HttpClient _http;
    public string Home { get; }
    public int Port { get; }
    public string Token { get; }
    public string Fingerprint { get; }

    public ApiClient(string home, int port)
    {
        Home = home;
        Port = port;
        var tokenPath = MptaPaths.LocalTokenFile(home);
        var fpPath = MptaPaths.CertFingerprintFile(home);
        if (!File.Exists(tokenPath) || !File.Exists(fpPath))
            throw new CliException($"host data not found in {home} (local.token / cert.sha256). Start PocketShell (tray) or run `psh serve` first.");
        Token = File.ReadAllText(tokenPath, Encoding.UTF8).Trim();
        Fingerprint = File.ReadAllText(fpPath, Encoding.UTF8).Trim().ToLowerInvariant();
        var handler = new SocketsHttpHandler();
        handler.SslOptions.RemoteCertificateValidationCallback = ValidateCert;
        _http = new HttpClient(handler) { BaseAddress = new Uri($"https://127.0.0.1:{port}/"), Timeout = TimeSpan.FromSeconds(30) };
        _http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", Token);
    }

    public bool ValidateCert(object sender, X509Certificate? cert, X509Chain? chain, SslPolicyErrors errors) =>
        cert != null && string.Equals(CertManager.Fingerprint(cert), Fingerprint, StringComparison.Ordinal);

    public async Task<JsonElement> SendAsync(HttpMethod method, string path, object? body = null)
    {
        using var req = new HttpRequestMessage(method, path);
        if (body != null) req.Content = new StringContent(JsonSerializer.Serialize(body, Json.Options), Encoding.UTF8, "application/json");
        HttpResponseMessage resp;
        try { resp = await _http.SendAsync(req); }
        catch (HttpRequestException ex) { throw new CliException($"cannot reach PocketShell host at https://127.0.0.1:{Port}: {ex.Message}"); }
        using (resp)
        {
            var text = await resp.Content.ReadAsStringAsync();
            if (!resp.IsSuccessStatusCode)
                throw new CliException($"{method} {path} failed: HTTP {(int)resp.StatusCode} {text}");
            if (string.IsNullOrWhiteSpace(text)) return default;
            using var doc = JsonDocument.Parse(text);
            return doc.RootElement.Clone();
        }
    }

    /// <summary>Like <see cref="SendAsync"/> but returns non-success responses instead of throwing.</summary>
    public async Task<(int Status, JsonElement Body, string Text)> SendRawAsync(HttpMethod method, string path, object? body = null)
    {
        using var req = new HttpRequestMessage(method, path);
        if (body != null) req.Content = new StringContent(JsonSerializer.Serialize(body, Json.Options), Encoding.UTF8, "application/json");
        HttpResponseMessage resp;
        try { resp = await _http.SendAsync(req); }
        catch (HttpRequestException ex) { throw new CliException($"cannot reach PocketShell host at https://127.0.0.1:{Port}: {ex.Message}"); }
        using (resp)
        {
            var text = await resp.Content.ReadAsStringAsync();
            JsonElement el = default;
            if (!string.IsNullOrWhiteSpace(text))
            {
                try
                {
                    using var doc = JsonDocument.Parse(text);
                    el = doc.RootElement.Clone();
                }
                catch (JsonException) { }
            }
            return ((int)resp.StatusCode, el, text);
        }
    }

    public async Task<ClientWebSocket> ConnectWsAsync(string sessionId, int cols, int rows, CancellationToken ct)
    {
        var ws = new ClientWebSocket();
        ws.Options.RemoteCertificateValidationCallback = ValidateCert;
        ws.Options.SetRequestHeader("Authorization", "Bearer " + Token);
        ws.Options.KeepAliveInterval = TimeSpan.FromSeconds(20);
        var uri = new Uri($"wss://127.0.0.1:{Port}/ws/sessions/{Uri.EscapeDataString(sessionId)}?client=cli&cols={cols}&rows={rows}");
        try { await ws.ConnectAsync(uri, ct); }
        catch (WebSocketException ex) { ws.Dispose(); throw new CliException($"websocket connect failed: {ex.Message}"); }
        return ws;
    }

    public void Dispose() => _http.Dispose();
}

public sealed class CliException : Exception
{
    public CliException(string msg) : base(msg) { }
}
