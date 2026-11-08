using System.Diagnostics;
using Pkshell.Core.Projects;
using Pkshell.Core.Util;

namespace PocketShell.Tray;

/// <summary>
/// "프로젝트 폴더": folders under which the phone can create a new project (folder + session running Claude/Codex).
/// Saved to projects.json at once; the server reads it on every request.
/// </summary>
internal sealed class ProjectsForm : Form
{
    private readonly string _home;
    private readonly ListBox _list;
    private readonly Button _remove;
    private readonly Button _open;

    public ProjectsForm(string home)
    {
        _home = home;
        Text = $"{Brand.Name} — 프로젝트 폴더";
        StartPosition = FormStartPosition.CenterScreen;
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;
        AutoScaleMode = AutoScaleMode.Dpi;
        ClientSize = new Size(560, 330);
        Font = SystemFonts.MessageBoxFont ?? Control.DefaultFont;

        var agents = ProjectStore.DetectAgents();
        var help = new Label
        {
            AutoSize = false,
            Location = new Point(12, 10),
            Size = new Size(536, 58),
            Text = "휴대폰에서 프로젝트 이름과 AI를 고르면 아래 폴더 안에 새 폴더를 만들고 그 폴더에서 세션을 시작합니다.\n" +
                   "이 PC에서 찾은 AI CLI: " + (agents.Count == 0 ? "없음 (claude 또는 codex를 설치하세요)" : string.Join(", ", agents.Select(a => a.Name))),
        };
        _list = new ListBox { Location = new Point(12, 74), Size = new Size(430, 200), IntegralHeight = false, HorizontalScrollbar = true };
        _list.SelectedIndexChanged += (_, _) => UpdateButtons();
        _list.DoubleClick += (_, _) => OpenSelected();

        var add = new Button { Text = "추가…", Location = new Point(452, 74), Size = new Size(96, 30) };
        add.Click += (_, _) => AddFolder();
        _remove = new Button { Text = "삭제", Location = new Point(452, 110), Size = new Size(96, 30) };
        _remove.Click += (_, _) => RemoveSelected();
        _open = new Button { Text = "폴더 열기", Location = new Point(452, 146), Size = new Size(96, 30) };
        _open.Click += (_, _) => OpenSelected();
        var close = new Button { Text = "닫기", Location = new Point(452, 286), Size = new Size(96, 30), DialogResult = DialogResult.OK };
        var note = new Label
        {
            AutoSize = false,
            Location = new Point(12, 282),
            Size = new Size(430, 40),
            Text = "목록에서 지운 폴더의 파일은 삭제되지 않습니다.",
            ForeColor = SystemColors.GrayText,
        };

        Controls.AddRange(new Control[] { help, _list, add, _remove, _open, note, close });
        AcceptButton = close;
        CancelButton = close;
        Reload();
    }

    private void Reload()
    {
        _list.Items.Clear();
        foreach (var r in ProjectStore.Load(_home).Roots) _list.Items.Add(r);
        if (_list.Items.Count > 0) _list.SelectedIndex = 0;
        UpdateButtons();
    }

    private void UpdateButtons()
    {
        _remove.Enabled = _list.SelectedItem != null;
        _open.Enabled = _list.SelectedItem != null;
    }

    private void AddFolder()
    {
        using var dlg = new FolderBrowserDialog { Description = "프로젝트를 만들 폴더", UseDescriptionForTitle = true, ShowNewFolderButton = true };
        if (dlg.ShowDialog(this) != DialogResult.OK || string.IsNullOrWhiteSpace(dlg.SelectedPath)) return;
        var s = ProjectStore.Load(_home);
        if (ProjectStore.FindRoot(s, dlg.SelectedPath) == null) s.Roots.Add(dlg.SelectedPath);
        if (!Save(s)) return;
        Reload();
        _list.SelectedItem = ProjectStore.FindRoot(s, dlg.SelectedPath);
    }

    private void RemoveSelected()
    {
        if (_list.SelectedItem is not string root) return;
        var s = ProjectStore.Load(_home);
        s.Roots.RemoveAll(r => string.Equals(r, root, StringComparison.OrdinalIgnoreCase));
        if (Save(s)) Reload();
    }

    private void OpenSelected()
    {
        if (_list.SelectedItem is not string root || !Directory.Exists(root)) return;
        Process.Start(new ProcessStartInfo("explorer.exe", $"\"{root}\"") { UseShellExecute = true })?.Dispose();
    }

    private bool Save(ProjectSettings s)
    {
        try
        {
            ProjectStore.Save(_home, s);
            TrayLog.Write(_home, "project folders: " + string.Join("; ", s.Roots));
            return true;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            MessageBox.Show(this, "저장하지 못했습니다: " + ex.Message, Brand.Name, MessageBoxButtons.OK, MessageBoxIcon.Error);
            return false;
        }
    }
}
