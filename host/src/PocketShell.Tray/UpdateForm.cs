using Pkshell.Core.Update;
using Pkshell.Core.Util;

namespace PocketShell.Tray;

/// <summary>
/// The update window: asks once whether to install a new version, then shows the install as it goes (download with
/// a progress bar, 설치 준비, 교체, 다시 시작). Closing it never stops the update.
/// </summary>
internal sealed class UpdateForm : Form
{
    private static readonly Color Bg = Color.FromArgb(14, 20, 38);
    private static readonly Color Muted = Color.FromArgb(150, 160, 185);
    private static readonly Color Danger = Color.FromArgb(255, 150, 140);
    private static readonly TimeSpan GiveUpAfter = TimeSpan.FromSeconds(120);

    private readonly string _target;
    /// <summary>Starts the install (asks about running sessions itself); false when it was not started.</summary>
    private readonly Func<Task<bool>> _install;
    /// <summary>Latest GET /api/update, or null while the host does not answer.</summary>
    private readonly Func<Task<UpdateStatus?>> _poll;
    private readonly Func<bool> _installedIsNew;
    private readonly Label _title;
    private readonly Label _body;
    private readonly Label _stage;
    private readonly ProgressBar _bar;
    private readonly Button _primary;
    private readonly Button _secondary;
    private readonly System.Windows.Forms.Timer _timer;
    private bool _polling;
    private bool _finished;
    private DateTime _lastAnswer;

    /// <summary>True from "지금 설치" until the install has finished or failed.</summary>
    public bool InProgress { get; private set; }

    public UpdateForm(string target, string current, string mode, Func<Task<bool>> install, Func<Task<UpdateStatus?>> poll, Func<bool> installedIsNew)
    {
        _target = target;
        _install = install;
        _poll = poll;
        _installedIsNew = installedIsNew;
        Text = $"{Brand.Name} 업데이트";
        using (var s = typeof(UpdateForm).Assembly.GetManifestResourceStream("app.ico"))
            if (s != null) Icon = new Icon(s);
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        AutoScaleMode = AutoScaleMode.Dpi;
        BackColor = Bg;
        ForeColor = Color.White;
        Font = new Font("Segoe UI", 10f);
        ClientSize = new Size(460, 262);

        _title = new Label { Text = $"새 버전 v{target}", Font = new Font("Segoe UI Semibold", 14f), AutoSize = true, Location = new Point(24, 20) };
        var later = mode == UpdateModes.Auto
            ? "'나중에'를 누르면 세션이 모두 끝났을 때 자동으로 설치됩니다."
            : "나중에 하려면 트레이 메뉴의 '업데이트 설치'를 누르세요.";
        _body = new Label
        {
            Text = $"지금 설치할까요? (현재 v{current})\n\n설치하는 동안 서버가 몇 초 멈추고, 휴대폰은 자동으로 다시 연결됩니다.\n{later}",
            ForeColor = Muted, Location = new Point(24, 58), Size = new Size(412, 124),
        };
        _stage = new Label { ForeColor = Muted, Location = new Point(24, 160), Size = new Size(412, 20), Visible = false };
        _bar = new ProgressBar { Location = new Point(24, 136), Size = new Size(412, 14), Visible = false, Style = ProgressBarStyle.Continuous };
        _primary = MakeButton("지금 설치", new Point(236, 208), primary: true);
        _primary.Click += async (_, _) => await InstallAsync();
        _secondary = MakeButton("나중에", new Point(340, 208), primary: false);
        _secondary.Click += (_, _) => Close();
        Controls.AddRange(new Control[] { _title, _body, _bar, _stage, _primary, _secondary });
        AcceptButton = _primary;
        CancelButton = _secondary;

        // While asking: only watches for the host starting the install by itself (auto mode, last session ended).
        _timer = new System.Windows.Forms.Timer { Interval = 2000 };
        _timer.Tick += async (_, _) => await PollAsync();
        _timer.Start();
        FormClosed += (_, _) => _timer.Dispose();
    }

    private static Button MakeButton(string text, Point at, bool primary) => new()
    {
        Text = text, Location = at, Size = new Size(96, 34), FlatStyle = FlatStyle.Flat,
        BackColor = primary ? Color.FromArgb(232, 196, 120) : Color.FromArgb(36, 48, 84),
        ForeColor = primary ? Color.FromArgb(20, 22, 30) : Color.White,
        FlatAppearance = { BorderColor = primary ? Color.FromArgb(232, 196, 120) : Color.FromArgb(70, 84, 124) },
    };

    private async Task InstallAsync()
    {
        if (InProgress || _finished) return;
        _primary.Enabled = false;
        _secondary.Enabled = false;
        bool started;
        try { started = await _install(); }
        catch (Exception ex) when (ex is HttpRequestException or IOException or TaskCanceledException or UnauthorizedAccessException)
        {
            if (IsDisposed) return;
            Fail($"서버에 연결하지 못했습니다: {ex.Message}\n서버가 실행 중인지 확인하고 다시 시도하세요.");
            return;
        }
        if (IsDisposed) return;
        if (!started)
        {
            _primary.Enabled = true;
            _secondary.Enabled = true;
            return;
        }
        BeginProgress();
    }

    /// <summary>Switches to the progress view (also used when the host started installing by itself).</summary>
    public void BeginProgress()
    {
        if (InProgress || _finished) return;
        InProgress = true;
        _lastAnswer = DateTime.UtcNow;
        _title.Text = $"v{_target} 설치 중";
        _body.Text = "이 창을 닫아도 설치는 계속됩니다.";
        _body.Size = new Size(412, 64);
        _bar.Visible = true;
        _stage.Visible = true;
        _primary.Visible = false;
        _secondary.Text = "숨기기";
        _secondary.Enabled = true;
        AcceptButton = _secondary;
        Render(new UpdateProgressView(1, "업데이트를 시작하는 중…", null));
        _timer.Interval = 400;
    }

    private async Task PollAsync()
    {
        if (_polling || _finished) return;
        _polling = true;
        try
        {
            var st = await _poll();
            if (IsDisposed || _finished) return;
            if (!InProgress)
            {
                if (st?.State == UpdateStates.Applying) BeginProgress();
                else if (st != null && string.Equals(st.Current, _target, StringComparison.Ordinal)) ShowDone();
                return;
            }
            var now = DateTime.UtcNow;
            if (st != null) _lastAnswer = now;
            else if (now - _lastAnswer > GiveUpAfter)
            {
                Fail("서버가 2분 넘게 응답하지 않습니다.\n트레이 메뉴의 '로그 폴더 열기'에서 host.log를 확인하세요.");
                return;
            }
            var view = UpdateProgress.Describe(st, _target, _installedIsNew());
            if (view.Failed) Fail(view.Text + "\n이전 버전으로 계속 실행됩니다. 잠시 후 다시 시도하세요.");
            else if (view.Done) ShowDone();
            else Render(view);
        }
        finally { _polling = false; }
    }

    private void Render(UpdateProgressView v)
    {
        _stage.Text = $"{v.Stage} / {UpdateProgress.Stages} 단계";
        _body.Text = v.Text + "\n이 창을 닫아도 설치는 계속됩니다.";
        if (v.Percent is int pct)
        {
            _bar.Style = ProgressBarStyle.Continuous;
            _bar.Value = Math.Clamp(pct, 0, 100);
        }
        else if (_bar.Style != ProgressBarStyle.Marquee)
        {
            _bar.Style = ProgressBarStyle.Marquee;
            _bar.MarqueeAnimationSpeed = 30;
        }
    }

    /// <summary>The new version is running (the tray restarts itself on the new files right after).</summary>
    public void ShowDone()
    {
        if (_finished) return;
        Finish();
        _title.Text = "설치 완료";
        _body.Text = $"v{_target}(으)로 업데이트했습니다.";
        _body.Size = new Size(412, 64);
        _stage.Text = $"{UpdateProgress.Stages} / {UpdateProgress.Stages} 단계";
        _stage.Visible = true;
        _bar.Visible = true;
        _bar.Style = ProgressBarStyle.Continuous;
        _bar.Value = 100;
    }

    private void Fail(string text)
    {
        Finish();
        _title.Text = "업데이트하지 못했습니다";
        _body.ForeColor = Danger;
        _body.Size = new Size(412, 124);
        _body.Text = text;
        _bar.Visible = false;
        _stage.Visible = false;
    }

    private void Finish()
    {
        _finished = true;
        InProgress = false;
        _timer.Stop();
        _primary.Visible = false;
        _secondary.Text = "닫기";
        _secondary.Enabled = true;
    }
}
