namespace Pkshell.Core.Update;

/// <summary>One line of the update window: which of the four stages, its text, and how far (null = no percentage).</summary>
public sealed record UpdateProgressView(int Stage, string Text, int? Percent, bool Done = false, bool Failed = false);

/// <summary>
/// What the tray's update window (and `psh update`) shows for a host <see cref="UpdateStatus"/> while an update to
/// <c>target</c> is being installed: 1 다운로드 → 2 설치 준비 → 3 교체 → 4 다시 시작.
/// </summary>
public static class UpdateProgress
{
    public const int Stages = 4;

    /// <param name="status">Latest GET /api/update, or null while the host does not answer (it stops listening to swap files).</param>
    /// <param name="installedIsNew">The install folder already holds the new build (files swapped, new host starting).</param>
    public static UpdateProgressView Describe(UpdateStatus? status, string target, bool installedIsNew)
    {
        if (status == null)
        {
            return installedIsNew
                ? new UpdateProgressView(4, "다시 시작 중: 새 버전 서버를 켜고 있습니다…", null)
                : new UpdateProgressView(3, "교체 중: 서버를 잠시 멈추고 파일을 바꾸고 있습니다…", null);
        }
        if (target.Length > 0 && string.Equals(status.Current, target, StringComparison.Ordinal))
            return new UpdateProgressView(4, $"설치 완료: v{target}", 100, Done: true);
        switch (status.State)
        {
            case UpdateStates.Error:
                return new UpdateProgressView(1, "업데이트 실패: " + (status.Error ?? "알 수 없는 오류"), null, Failed: true);
            case UpdateStates.Downloading when status.Step == UpdateSteps.Verify:
                return new UpdateProgressView(2, "설치 준비 중: 받은 파일이 맞는지 확인하고 있습니다…", null);
            case UpdateStates.Downloading when status.Step == UpdateSteps.Extract:
                return new UpdateProgressView(2, "설치 준비 중: 압축을 풀고 있습니다…", null);
            case UpdateStates.Downloading:
                long got = Math.Max(0, status.Downloaded ?? 0);
                if (status.Total is > 0)
                {
                    long total = status.Total.Value;
                    int pct = (int)Math.Clamp(got * 100 / total, 0, 100);
                    return new UpdateProgressView(1, $"다운로드 중: {Mb(got)} / {Mb(total)} MB ({pct}%)", pct);
                }
                return new UpdateProgressView(1, got > 0 ? $"다운로드 중: {Mb(got)} MB" : "다운로드를 시작하는 중…", null);
            case UpdateStates.Ready:
                return new UpdateProgressView(2, "설치 준비 완료: 곧 교체를 시작합니다…", null);
            case UpdateStates.Applying:
                return new UpdateProgressView(3, "교체 중: 서버를 잠시 멈추고 파일을 바꾸고 있습니다…", null);
            default:
                return new UpdateProgressView(1, "업데이트를 시작하는 중…", null);
        }
    }

    private static string Mb(long bytes) => (bytes / 1048576.0).ToString("0.0", System.Globalization.CultureInfo.InvariantCulture);
}
