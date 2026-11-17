using System.Security.Cryptography;
using System.Text;

namespace Pkshell.Core.Auth;

/// <summary>A pending invitation to one session: the guest claims it with the PIN like a pairing PIN.</summary>
public sealed record ShareInvite(string Pin, string SessionId, bool ReadOnly, TimeSpan Duration, string? Title, DateTimeOffset ExpiresAt);

/// <summary>
/// Session share PINs: 6 digits, 10 minutes, one-time. Several may be open at once (one per invited person), never two
/// with the same digits. Like the pairing PIN, all of them die after 5 wrong claims in a row (brute force guard); the
/// per-IP <see cref="ClaimRateLimiter"/> applies on top.
/// </summary>
public sealed class ShareInvites
{
    public static readonly TimeSpan Lifetime = TimeSpan.FromMinutes(10);
    public const int MaxWrongAttempts = 5;
    public static readonly TimeSpan MaxDuration = TimeSpan.FromDays(7);

    private readonly Func<DateTimeOffset> _clock;
    private readonly object _gate = new();
    private readonly List<ShareInvite> _open = new();
    private int _wrong;

    public ShareInvites(Func<DateTimeOffset>? clock = null) { _clock = clock ?? (() => DateTimeOffset.UtcNow); }

    public ShareInvite Start(string sessionId, bool readOnly, TimeSpan duration, string? title)
    {
        if (duration <= TimeSpan.Zero || duration > MaxDuration) throw new ArgumentOutOfRangeException(nameof(duration));
        lock (_gate)
        {
            PruneLocked();
            string pin;
            do pin = PinPairing.NewPin(); while (_open.Any(i => i.Pin == pin));
            var invite = new ShareInvite(pin, sessionId, readOnly, duration, title, _clock() + Lifetime);
            _open.Add(invite);
            _wrong = 0;
            return invite;
        }
    }

    /// <summary>Open invitations, optionally only those of one session.</summary>
    public IReadOnlyList<ShareInvite> Open(string? sessionId = null)
    {
        lock (_gate)
        {
            PruneLocked();
            return _open.Where(i => sessionId == null || i.SessionId == sessionId).ToList();
        }
    }

    /// <summary>Takes back the open invitations of a session (sharing stopped).</summary>
    public int Cancel(string sessionId)
    {
        lock (_gate) return _open.RemoveAll(i => i.SessionId == sessionId);
    }

    /// <summary>The invitation with this PIN (consumed), or null. A miss counts as a wrong attempt.</summary>
    public ShareInvite? TryConsume(string? pin)
    {
        var given = Encoding.ASCII.GetBytes(pin?.Trim() ?? "");
        lock (_gate)
        {
            PruneLocked();
            ShareInvite? hit = null;
            foreach (var i in _open)
            {
                var want = Encoding.ASCII.GetBytes(i.Pin);
                if (given.Length == want.Length && CryptographicOperations.FixedTimeEquals(given, want)) hit = i;
            }
            if (hit != null)
            {
                _open.Remove(hit);
                return hit;
            }
            if (_open.Count > 0 && ++_wrong >= MaxWrongAttempts) _open.Clear();
            return null;
        }
    }

    private void PruneLocked()
    {
        var now = _clock();
        _open.RemoveAll(i => i.ExpiresAt <= now);
    }
}

/// <summary>What a guest (a token for one shared session) may call. Everything else answers 403.</summary>
public static class ShareAccess
{
    /// <param name="path">The decoded request path ("/api/sessions/ext-42/input").</param>
    public static bool Allows(string method, string path, string sessionId, bool readOnly)
    {
        bool get = string.Equals(method, "GET", StringComparison.OrdinalIgnoreCase);
        bool post = string.Equals(method, "POST", StringComparison.OrdinalIgnoreCase);
        var own = "/api/sessions/" + sessionId;
        if (get && path is "/api/info" or "/api/events" or "/api/sessions" or "/api/app/android" or "/api/app/android/apk") return true;
        if (get && (Same(path, own) || Same(path, own + "/screen"))) return true;
        if (post && !readOnly && (Same(path, own + "/input") || path == "/api/clipboard/image")) return true;
        // WebSocket upgrade (GET): the terminal of this session only.
        if (get && Same(path, "/ws/sessions/" + sessionId)) return true;
        return false;
    }

    private static bool Same(string path, string want) => string.Equals(path.TrimEnd('/'), want, StringComparison.Ordinal);
}
