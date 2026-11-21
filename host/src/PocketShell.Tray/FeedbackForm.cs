using Pkshell.Core.Util;

namespace PocketShell.Tray;

/// <summary>
/// "의견 보내기": kind (버그 수정 · 개선 · 의견 · 문의) + text, saved by <see cref="Feedback.Save"/> straight from the tray,
/// so it works while the server is stopped too.
/// </summary>
internal sealed class FeedbackForm : Form
{
    private static readonly Color Bg = Color.FromArgb(14, 20, 38);
    private static readonly Color Panel = Color.FromArgb(24, 33, 60);
    private static readonly Color Muted = Color.FromArgb(150, 160, 185);

    private readonly string _home;
    private readonly string _version;
    private readonly List<(RadioButton Button, string Id)> _kinds = new();
    private readonly TextBox _text;
    private readonly Label _status;

    public FeedbackForm(string home, string version)
    {
        _home = home;
        _version = version;
        Text = $"{Brand.Name} — 의견 보내기";
        using (var s = typeof(FeedbackForm).Assembly.GetManifestResourceStream("app.ico"))
            if (s != null) Icon = new Icon(s);
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        AutoScaleMode = AutoScaleMode.Dpi;
        BackColor = Bg;
        ForeColor = Color.White;
        Font = new Font("Segoe UI", 10f);
        ClientSize = new Size(480, 420);

        var title = new Label { Text = "버그 · 개선 · 의견 · 문의", Font = new Font("Segoe UI Semibold", 14f), AutoSize = true, Location = new Point(24, 20) };
        var shared = Feedback.SharedDir();
        var help = new Label
        {
            Text = shared != null ? "보낸 내용은 공유 폴더에 저장됩니다." : "공유 폴더(OneDrive apppatch)가 없어 이 PC 데이터 폴더에 저장됩니다.",
            ForeColor = Muted, Location = new Point(24, 56), Size = new Size(432, 22),
        };
        var x = 24;
        foreach (var (id, label) in Feedback.Categories)
        {
            var rb = new RadioButton { Text = label, Location = new Point(x, 88), AutoSize = true, Checked = _kinds.Count == 0 };
            _kinds.Add((rb, id));
            Controls.Add(rb);
            x += 108;
        }
        _text = new TextBox
        {
            Multiline = true, ScrollBars = ScrollBars.Vertical, AcceptsReturn = true, MaxLength = Feedback.MaxMessageLength,
            Location = new Point(24, 124), Size = new Size(432, 220), BackColor = Panel, ForeColor = Color.White,
            BorderStyle = BorderStyle.FixedSingle, PlaceholderText = "무엇이 어떻게 됐는지, 바라는 점을 적어 주세요",
        };
        _status = new Label { ForeColor = Muted, Location = new Point(24, 360), Size = new Size(220, 40), TextAlign = ContentAlignment.MiddleLeft };
        var send = MakeButton("보내기", new Point(256, 362));
        send.Click += (_, _) => Send();
        var close = MakeButton("닫기", new Point(360, 362));
        close.Click += (_, _) => Close();
        Controls.AddRange(new Control[] { title, help, _text, _status, send, close });
        AcceptButton = null;
        CancelButton = close;
    }

    private static Button MakeButton(string text, Point at) => new()
    {
        Text = text, Location = at, Size = new Size(96, 34), FlatStyle = FlatStyle.Flat,
        BackColor = Color.FromArgb(36, 48, 84), ForeColor = Color.White,
        FlatAppearance = { BorderColor = Color.FromArgb(70, 84, 124) },
    };

    private void Send()
    {
        var req = new FeedbackRequest
        {
            Category = _kinds.FirstOrDefault(k => k.Button.Checked).Id,
            Message = _text.Text,
            Source = "pc",
            AppVersion = _version,
        };
        var invalid = Feedback.Validate(req);
        if (invalid != null)
        {
            _status.ForeColor = Color.FromArgb(240, 150, 150);
            _status.Text = invalid;
            return;
        }
        try
        {
            var (_, shared) = Feedback.Save(_home, req, _version);
            MessageBox.Show(this, shared ? "보냈습니다. 고맙습니다." : "이 PC 데이터 폴더의 feedback에 저장했습니다.", Text,
                MessageBoxButtons.OK, MessageBoxIcon.Information);
            Close();
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            _status.ForeColor = Color.FromArgb(240, 150, 150);
            _status.Text = "저장 실패: " + ex.Message;
        }
    }
}
