using System.Net.Http.Headers;
using System.Net.Security;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using Mpta.Core.Net;
using Mpta.Core.Util;

namespace PocketShell.Tray;

/// <summary>
/// "휴대폰 연결": issues a one-time pairing PIN (POST /api/pair/start {mode:"pin"}, DESIGN §12.1) with the local token over
/// a connection pinned to cert.sha256, and shows PIN + certificate fingerprint + addresses with a countdown.
/// </summary>
internal sealed class PinForm : Form
{
    private static readonly Color Bg = Color.FromArgb(14, 20, 38);
    private static readonly Color Panel = Color.FromArgb(24, 33, 60);
    private static readonly Color Gold = Color.FromArgb(232, 196, 120);
    private static readonly Color Muted = Color.FromArgb(150, 160, 185);

    private readonly string _home;
    private readonly int _port;
    private readonly Label _pin;
    private readonly Label _fp;
    private readonly Label _hosts;
    private readonly Label _expiry;
    private readonly Button _renew;
    private readonly System.Windows.Forms.Timer _countdown;
    private DateTimeOffset _expiresAt;

    public PinForm(string home, int port)
    {
        _home = home;
        _port = port;
        Text = $"{Brand.Name} — 휴대폰 연결";
        using (var s = typeof(PinForm).Assembly.GetManifestResourceStream("app.ico"))
            if (s != null) Icon = new Icon(s);
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        AutoScaleMode = AutoScaleMode.Dpi;
        BackColor = Bg;
        ForeColor = Color.White;
        Font = new Font("Segoe UI", 10f);
        ClientSize = new Size(460, 400);
        Padding = new Padding(24);

        var title = new Label { Text = "휴대폰에서 이 PC 연결", Font = new Font("Segoe UI Semibold", 14f), AutoSize = true, Location = new Point(24, 20) };
        var help = new Label
        {
            Text = "폰 앱의 PIN 연결에서 이 PC를 고르고 아래 PIN을 입력하세요.\n폰에 뜨는 인증서 지문이 아래와 같은지 확인하세요.",
            ForeColor = Muted, Location = new Point(24, 56), Size = new Size(412, 48),
        };
        var card = new Panel { BackColor = Panel, Location = new Point(24, 110), Size = new Size(412, 120) };
        _pin = new Label
        {
            Text = "······", Font = new Font("Consolas", 40f, FontStyle.Bold), ForeColor = Gold,
            TextAlign = ContentAlignment.MiddleCenter, Dock = DockStyle.Fill,
        };
        card.Controls.Add(_pin);
        _expiry = new Label { ForeColor = Muted, Location = new Point(24, 238), Size = new Size(412, 22), TextAlign = ContentAlignment.MiddleCenter };
        _fp = new Label { Location = new Point(24, 270), Size = new Size(412, 22) };
        _hosts = new Label { ForeColor = Muted, Location = new Point(24, 296), Size = new Size(412, 40) };
        _renew = MakeButton("새 PIN", new Point(236, 346));
        _renew.Click += async (_, _) => await IssueAsync();
        var close = MakeButton("닫기", new Point(340, 346));
        close.Click += (_, _) => Close();
        Controls.AddRange(new Control[] { title, help, card, _expiry, _fp, _hosts, _renew, close });

        _countdown = new System.Windows.Forms.Timer { Interval = 1000 };
        _countdown.Tick += (_, _) => UpdateExpiry();
        Shown += async (_, _) => await IssueAsync();
        FormClosed += (_, _) => _countdown.Dispose();
    }

    private static Button MakeButton(string text, Point at) => new()
    {
        Text = text, Location = at, Size = new Size(96, 34), FlatStyle = FlatStyle.Flat,
        BackColor = Color.FromArgb(36, 48, 84), ForeColor = Color.White,
        FlatAppearance = { BorderColor = Color.FromArgb(70, 84, 124) },
    };

    private async Task IssueAsync()
    {
        _renew.Enabled = false;
        _pin.Text = "······";
        _expiry.Text = "PIN 발급 중…";
        try
        {
            var r = await StartPinAsync();
            var pin = r.GetProperty("pin").GetString() ?? "";
            _pin.Text = pin.Length == 6 ? pin[..3] + " " + pin[3..] : pin;
            _fp.Text = "인증서 지문   " + (r.TryGetProperty("fpShort", out var f) ? f.GetString() : "");
            var hosts = r.TryGetProperty("hosts", out var h) && h.ValueKind == JsonValueKind.Array
                ? string.Join(", ", h.EnumerateArray().Select(x => x.GetString())) : "";
            _hosts.Text = $"이 PC 주소   {hosts}  (포트 {_port})";
            _expiresAt = r.TryGetProperty("expiresAt", out var e) && DateTimeOffset.TryParse(e.GetString(), out var at)
                ? at : DateTimeOffset.Now.AddMinutes(5);
            _countdown.Start();
            UpdateExpiry();
        }
        catch (Exception ex) when (ex is HttpRequestException or IOException or JsonException or KeyNotFoundException or InvalidOperationException or TaskCanceledException)
        {
            TrayLog.Write(_home, "pin issue failed: " + ex.Message);
            _expiry.Text = "PIN 발급 실패: " + ex.Message;
        }
        finally
        {
            _renew.Enabled = true;
        }
    }

    private void UpdateExpiry()
    {
        var left = _expiresAt - DateTimeOffset.Now;
        if (left <= TimeSpan.Zero)
        {
            _countdown.Stop();
            _pin.Text = "만료됨";
            _expiry.Text = "PIN이 만료되었습니다. '새 PIN'을 누르세요.";
            return;
        }
        _expiry.Text = $"1회용 · {left.Minutes}:{left.Seconds:00} 후 만료 · 5회 틀리면 무효";
    }

    private async Task<JsonElement> StartPinAsync()
    {
        var token = File.ReadAllText(MptaPaths.LocalTokenFile(_home), Encoding.UTF8).Trim();
        var fp = File.ReadAllText(MptaPaths.CertFingerprintFile(_home), Encoding.UTF8).Trim().ToLowerInvariant();
        using var handler = new SocketsHttpHandler();
        handler.SslOptions.RemoteCertificateValidationCallback =
            (object _, X509Certificate? cert, X509Chain? _, SslPolicyErrors _) =>
                cert != null && string.Equals(CertManager.Fingerprint(cert), fp, StringComparison.Ordinal);
        using var http = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(10) };
        using var req = new HttpRequestMessage(HttpMethod.Post, $"https://127.0.0.1:{_port}/api/pair/start")
        {
            Content = new StringContent("{\"mode\":\"pin\"}", Encoding.UTF8, "application/json"),
        };
        req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
        using var resp = await http.SendAsync(req);
        var text = await resp.Content.ReadAsStringAsync();
        if (!resp.IsSuccessStatusCode) throw new InvalidOperationException($"HTTP {(int)resp.StatusCode} {text}");
        using var doc = JsonDocument.Parse(text);
        return doc.RootElement.Clone();
    }
}
