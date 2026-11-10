using System.IO.Compression;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Serialization;
using Pkshell.Core.Util;

namespace Pkshell.Core.Update;

/// <summary>JSON of <c>GET /api/update</c> and <c>/api/info.update</c> (DESIGN §11.3).</summary>
public sealed class UpdateStatus
{
    public string Current { get; set; } = "";
    public string? Latest { get; set; }
    public string State { get; set; } = UpdateStates.None;
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Error { get; set; }
}

public static class UpdateStates
{
    public const string None = "none", Available = "available", Downloading = "downloading", Ready = "ready", Applying = "applying", Error = "error";
}

public static class UpdateModes
{
    public const string Auto = "auto", Notify = "notify", Off = "off";

    public static string Normalize(string? mode) => mode?.Trim().ToLowerInvariant() switch
    {
        Notify => Notify,
        Off => Off,
        _ => Auto,
    };
}

/// <summary>
/// Release check / download / SHA-256 verification / staging (DESIGN §11.2–11.3). Applying (process handoff) is done
/// by the host server, which this service asks via <see cref="AutoApply"/> in auto mode once there are no sessions.
/// Staging layout: <c>&lt;home&gt;/updates/&lt;ver&gt;/app/</c> (extracted zip) + the zip itself + <c>.ready</c> (verified sha256).
/// </summary>
public sealed class UpdateService : IDisposable
{
    private readonly string _updatesDir;
    private readonly HttpClient _http;
    private readonly SemaphoreSlim _op = new(1, 1);
    private readonly object _gate = new();
    private readonly CancellationTokenSource _stop = new();
    private readonly Func<int> _runningSessions;
    private UpdateStatus _status;
    private ReleaseInfo? _release;
    private string? _stagingDir;
    private SemVer? _failedVersion;

    public SemVer Current { get; }
    public string FeedUrl { get; }
    public string Mode { get; }
    public TimeSpan Interval { get; }

    /// <summary>Called (auto mode) when a verified update is staged and no host session is running.</summary>
    public Func<Task>? AutoApply { get; set; }

    public UpdateService(string home, SemVer current, string feedUrl, string mode, TimeSpan interval, Func<int> runningSessions)
    {
        _updatesDir = Path.Combine(home, "updates");
        Current = current;
        FeedUrl = feedUrl;
        Mode = UpdateModes.Normalize(mode);
        Interval = interval < TimeSpan.FromSeconds(1) ? TimeSpan.FromSeconds(1) : interval;
        _runningSessions = runningSessions;
        _status = new UpdateStatus { Current = current.ToString() };
        _http = new HttpClient { Timeout = TimeSpan.FromMinutes(10) };
        _http.DefaultRequestHeaders.UserAgent.ParseAdd(ReleaseFeed.UserAgent);
    }

    public UpdateStatus Status
    {
        get { lock (_gate) return new UpdateStatus { Current = _status.Current, Latest = _status.Latest, State = _status.State, Error = _status.Error }; }
    }

    /// <summary>Staged app directory (contains psh.exe) when State is ready/applying.</summary>
    public string? StagingDir { get { lock (_gate) return _stagingDir; } }

    /// <summary>True when the last check found a release newer than the running version.</summary>
    public bool HasNewer { get { lock (_gate) return _release != null; } }

    private void Set(string state, string? error = null, string? latest = null)
    {
        lock (_gate)
        {
            _status.State = state;
            _status.Error = error;
            if (latest != null) _status.Latest = latest;
        }
    }

    public void MarkApplying() => Set(UpdateStates.Applying);

    public void MarkError(string error)
    {
        Log.Error("update: " + error);
        Set(UpdateStates.Error, error);
    }

    /// <summary>
    /// The new process of this version failed to start and was rolled back: periodic checks keep state=error for it
    /// instead of re-staging and auto-applying it again (an explicit apply can still retry it).
    /// </summary>
    public void MarkApplyFailed(string version)
    {
        if (SemVer.TryParse(version, out var v)) lock (_gate) _failedVersion = v;
    }

    /// <summary>Apply was skipped (e.g. a session started meanwhile): staged files are still valid.</summary>
    public void MarkReady() => Set(UpdateStates.Ready);

    /// <summary>Starts the periodic check (immediately, then every <see cref="Interval"/>) unless mode is off.</summary>
    public void Start()
    {
        CleanupStaleStaging();
        if (Mode == UpdateModes.Off)
        {
            Log.Info("update: autoUpdate=off (no background checks)");
            return;
        }
        Log.Info($"update: mode={Mode}, feed={FeedUrl}, interval={Interval.TotalSeconds:0}s, current={Current}");
        _ = Task.Run(CheckLoop);
        if (Mode == UpdateModes.Auto) _ = Task.Run(AutoApplyLoop);
    }

    private async Task CheckLoop()
    {
        while (!_stop.IsCancellationRequested)
        {
            await CheckAsync();
            try { await Task.Delay(Interval, _stop.Token); }
            catch (OperationCanceledException) { return; }
        }
    }

    private async Task AutoApplyLoop()
    {
        while (!_stop.IsCancellationRequested)
        {
            try { await Task.Delay(TimeSpan.FromSeconds(2), _stop.Token); }
            catch (OperationCanceledException) { return; }
            if (Status.State != UpdateStates.Ready || _runningSessions() > 0 || AutoApply == null) continue;
            Log.Info("update: staged update ready and no running sessions -> auto apply");
            try { await AutoApply(); }
            catch (Exception ex) { MarkError($"auto apply failed: {ex.Message}"); }
        }
    }

    /// <summary>Fetches the feed; in auto mode also downloads + verifies a newer release. Never throws.</summary>
    public async Task<UpdateStatus> CheckAsync()
    {
        await _op.WaitAsync();
        try
        {
            var state = Status.State;
            if (state is UpdateStates.Applying) return Status;
            List<ReleaseInfo> releases;
            try
            {
                if (ReleaseFeed.TryLocalDir(FeedUrl, out var localDir)) releases = ReleaseFeed.ScanDir(localDir);
                else releases = await FetchFeedAsync();
            }
            catch (Exception ex) when (ex is HttpRequestException or OperationCanceledException or JsonException or IOException or UnauthorizedAccessException)
            {
                MarkError($"update check failed: {ex.Message}");
                return Status;
            }
            var latest = ReleaseFeed.PickLatest(releases, ReleaseFeed.HostAssetName);
            if (latest == null || latest.Version <= Current)
            {
                lock (_gate) { _release = null; _stagingDir = null; }
                Set(UpdateStates.None, latest: latest?.Version.ToString() ?? Current.ToString());
                return Status;
            }
            bool sameStaged;
            lock (_gate)
            {
                if (_failedVersion != null && _failedVersion.Equals(latest.Version))
                {
                    _release = latest; // explicit apply may retry; no automatic re-apply
                    _status.Latest = latest.Version.ToString();
                    return Status;
                }
                sameStaged = _release != null && _release.Version.Equals(latest.Version) && _status.State == UpdateStates.Ready;
                _release = latest;
            }
            if (sameStaged) return Status;
            Log.Info($"update: newer release {latest.Tag} found (current {Current})");
            Set(UpdateStates.Available, latest: latest.Version.ToString());
            if (Mode == UpdateModes.Auto) await DownloadCoreAsync(latest);
            return Status;
        }
        finally { _op.Release(); }
    }

    private async Task<List<ReleaseInfo>> FetchFeedAsync()
    {
        using var req = new HttpRequestMessage(HttpMethod.Get, FeedUrl);
        req.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/vnd.github+json"));
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(30));
        using var resp = await _http.SendAsync(req, cts.Token);
        var body = await resp.Content.ReadAsStringAsync(cts.Token);
        if (!resp.IsSuccessStatusCode) throw new HttpRequestException($"HTTP {(int)resp.StatusCode} from {FeedUrl}");
        return ReleaseFeed.Parse(body);
    }

    /// <summary>Makes sure the newest known release is downloaded, verified and staged. Returns the staging dir or null (State=error/none).</summary>
    public async Task<string?> EnsureStagedAsync()
    {
        await _op.WaitAsync();
        try
        {
            ReleaseInfo? rel;
            lock (_gate)
            {
                rel = _release;
                if (rel != null && _status.State == UpdateStates.Ready && _stagingDir != null) return _stagingDir;
            }
            if (rel == null) return null;
            return await DownloadCoreAsync(rel);
        }
        finally { _op.Release(); }
    }

    private async Task<string?> DownloadCoreAsync(ReleaseInfo rel)
    {
        var assetName = ReleaseFeed.HostAssetName(rel.Version);
        var asset = rel.Asset(assetName)!;
        Set(UpdateStates.Downloading);
        try
        {
            string? expected = ReleaseFeed.ParseDigest(asset.Digest);
            string source = "digest";
            if (expected == null)
            {
                var sums = rel.Asset(ReleaseFeed.SumsAssetName);
                if (sums != null)
                {
                    var text = await _http.GetStringAsync(sums.Url);
                    expected = ReleaseFeed.FindInSums(text, assetName);
                    source = ReleaseFeed.SumsAssetName;
                }
            }
            if (expected == null)
            {
                MarkError($"refusing update {rel.Tag}: asset {assetName} has no sha256 digest and no {ReleaseFeed.SumsAssetName} entry");
                return null;
            }

            var dir = Path.Combine(_updatesDir, rel.Version.ToString());
            var appDir = Path.Combine(dir, "app");
            var readyFile = Path.Combine(dir, ".ready");
            var zip = Path.Combine(dir, assetName);
            // Reuse a verified staging only after re-hashing the kept zip; re-extract from it so app/ matches the zip.
            if (File.Exists(readyFile) && File.ReadAllText(readyFile).Trim() == expected && File.Exists(zip) && await HashFileAsync(zip) == expected)
            {
                TryDeleteDir(appDir);
                ZipFile.ExtractToDirectory(zip, appDir);
                if (FindExeDir(appDir) is { } cachedExeDir) return Staged(cachedExeDir, rel, "already staged (cached zip re-verified)");
            }

            TryDeleteDir(dir);
            Directory.CreateDirectory(dir);
            string actual;
            if (ReleaseFeed.TryLocalDir(FeedUrl, out _))
                File.Copy(asset.Url, zip, overwrite: true); // local folder feed: the asset URL is a file path
            else
            using (var resp = await _http.GetAsync(asset.Url, HttpCompletionOption.ResponseHeadersRead))
            {
                if (!resp.IsSuccessStatusCode) throw new HttpRequestException($"download {asset.Url}: HTTP {(int)resp.StatusCode}");
                await using var input = await resp.Content.ReadAsStreamAsync();
                await using (var fs = new FileStream(zip, FileMode.Create, FileAccess.Write, FileShare.None))
                    await input.CopyToAsync(fs);
            }
            actual = await HashFileAsync(zip);
            if (!string.Equals(actual, expected, StringComparison.Ordinal))
            {
                TryDeleteDir(dir);
                MarkError($"refusing update {rel.Tag}: sha256 mismatch for {assetName} (expected {expected} from {source}, got {actual})");
                return null;
            }
            ZipFile.ExtractToDirectory(zip, appDir); // .NET rejects entries that escape the target directory; zip kept for re-verification
            var exeDir = FindExeDir(appDir);
            if (exeDir == null)
            {
                TryDeleteDir(dir);
                MarkError($"refusing update {rel.Tag}: {assetName} does not contain {Brand.CliExe}");
                return null;
            }
            File.WriteAllText(readyFile, expected);
            return Staged(exeDir, rel, $"downloaded and verified ({source})");
        }
        catch (Exception ex) when (ex is HttpRequestException or IOException or UnauthorizedAccessException or InvalidDataException or OperationCanceledException)
        {
            MarkError($"download of {rel.Tag} failed: {ex.Message}");
            return null;
        }
    }

    private string Staged(string exeDir, ReleaseInfo rel, string how)
    {
        lock (_gate) _stagingDir = exeDir;
        Set(UpdateStates.Ready);
        Log.Info($"update: {rel.Tag} {how}; staged at {exeDir}");
        return exeDir;
    }

    private static async Task<string> HashFileAsync(string path)
    {
        await using var fs = File.OpenRead(path);
        return Convert.ToHexString(await SHA256.HashDataAsync(fs)).ToLowerInvariant();
    }

    private static string? FindExeDir(string appDir)
    {
        if (File.Exists(Path.Combine(appDir, Brand.CliExe))) return appDir;
        var subs = Directory.GetDirectories(appDir);
        return subs.Length == 1 && File.Exists(Path.Combine(subs[0], Brand.CliExe)) ? subs[0] : null;
    }

    /// <summary>Removes staging folders for versions that are not newer than the running one (applied or obsolete).</summary>
    private void CleanupStaleStaging()
    {
        if (!Directory.Exists(_updatesDir)) return;
        foreach (var d in Directory.GetDirectories(_updatesDir))
        {
            if (SemVer.TryParse(Path.GetFileName(d), out var v) && v > Current) continue;
            TryDeleteDir(d);
        }
    }

    private static void TryDeleteDir(string dir)
    {
        try { if (Directory.Exists(dir)) Directory.Delete(dir, true); }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { Log.Warn($"update: cannot delete {dir}: {ex.Message}"); }
    }

    public void Dispose()
    {
        _stop.Cancel();
        _http.Dispose();
    }
}
