using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Pkshell.Core.Util;

namespace Pkshell.Core.Auth;

public sealed class DeviceRecord
{
    public string Id { get; set; } = "";
    public string Name { get; set; } = "";
    public string TokenSha256 { get; set; } = "";
    public string CreatedAt { get; set; } = "";
    public bool Revoked { get; set; }
    /// <summary>A guest invited to one session (null = a full device of the owner).</summary>
    public string? SessionId { get; set; }
    /// <summary>Guest only: may watch the session but not type in it.</summary>
    public bool ReadOnly { get; set; }
    /// <summary>Guest only: the access ends at this time (ISO UTC).</summary>
    public string? ExpiresAt { get; set; }
    /// <summary>Guest only: the shared session's name when it was shared (shown in lists).</summary>
    public string? SessionTitle { get; set; }

    public bool IsGuest => SessionId != null;

    /// <summary>Not revoked and, for a guest, not past its end.</summary>
    public bool IsActiveAt(DateTimeOffset now) =>
        !Revoked && (ExpiresAt == null || (DateTimeOffset.TryParse(ExpiresAt, System.Globalization.CultureInfo.InvariantCulture,
            System.Globalization.DateTimeStyles.AssumeUniversal, out var e) && now < e));
}

/// <summary>devices.json store. Token plaintext is never persisted, only its SHA-256.</summary>
public sealed class DeviceStore
{
    private readonly string? _path;
    private readonly object _gate = new();
    private readonly Func<DateTimeOffset> _clock;
    private List<DeviceRecord> _devices;

    public DeviceStore(string? path, Func<DateTimeOffset>? clock = null)
    {
        _path = path;
        _clock = clock ?? (() => DateTimeOffset.UtcNow);
        _devices = new();
        if (path != null && File.Exists(path))
        {
            try { _devices = JsonSerializer.Deserialize<List<DeviceRecord>>(File.ReadAllText(path, Encoding.UTF8), Json.Options) ?? new(); }
            catch (JsonException ex) { Log.Error($"devices.json invalid ({ex.Message}); starting with no devices"); }
        }
    }

    public IReadOnlyList<DeviceRecord> List()
    {
        lock (_gate) return _devices.Select(Copy).ToList();
    }

    public (DeviceRecord Device, string Token) Add(string name)
    {
        var token = Ids.NewToken();
        var rec = new DeviceRecord
        {
            Id = Ids.NewHexId(16),
            Name = string.IsNullOrWhiteSpace(name) ? "device" : name.Trim(),
            TokenSha256 = Ids.Sha256Hex(token),
            CreatedAt = Ids.IsoNow(),
            Revoked = false,
        };
        lock (_gate)
        {
            _devices.Add(rec);
            Save();
        }
        return (Copy(rec), token);
    }

    /// <summary>A guest token that reaches only <paramref name="sessionId"/> until <paramref name="expiresAt"/>.</summary>
    public (DeviceRecord Device, string Token) AddGuest(string name, string sessionId, bool readOnly, DateTimeOffset expiresAt, string? title)
    {
        var token = Ids.NewToken();
        var rec = new DeviceRecord
        {
            Id = Ids.NewHexId(16),
            Name = string.IsNullOrWhiteSpace(name) ? "guest" : name.Trim(),
            TokenSha256 = Ids.Sha256Hex(token),
            CreatedAt = Ids.IsoNow(),
            SessionId = sessionId,
            ReadOnly = readOnly,
            ExpiresAt = Ids.IsoUtc(expiresAt),
            SessionTitle = title,
        };
        lock (_gate)
        {
            _devices.Add(rec);
            Save();
        }
        return (Copy(rec), token);
    }

    /// <summary>Guests that can still connect, optionally only those of one session.</summary>
    public IReadOnlyList<DeviceRecord> Guests(string? sessionId = null)
    {
        var now = _clock();
        lock (_gate)
            return _devices.Where(d => d.IsGuest && d.IsActiveAt(now) && (sessionId == null || d.SessionId == sessionId)).Select(Copy).ToList();
    }

    /// <summary>
    /// Drops guests that ended (expired, revoked) and ends the ones <paramref name="sessionGone"/> says lost their
    /// session. Returns the ids of guests that were active until now (their connections must be closed).
    /// </summary>
    public IReadOnlyList<string> PruneGuests(Func<string, bool> sessionGone)
    {
        var now = _clock();
        var ended = new List<string>();
        lock (_gate)
        {
            bool changed = false;
            foreach (var d in _devices.Where(d => d.IsGuest).ToList())
            {
                bool active = d.IsActiveAt(now);
                if (active && !sessionGone(d.SessionId!)) continue;
                if (active) ended.Add(d.Id);
                _devices.Remove(d);
                changed = true;
            }
            if (changed) Save();
        }
        return ended;
    }

    public bool Revoke(string id)
    {
        lock (_gate)
        {
            var d = _devices.FirstOrDefault(x => x.Id == id);
            if (d == null) return false;
            d.Revoked = true;
            Save();
            return true;
        }
    }

    /// <summary>True when the device exists and is not revoked.</summary>
    public bool IsActive(string id)
    {
        var now = _clock();
        lock (_gate) return _devices.Any(d => d.Id == id && d.IsActiveAt(now));
    }

    /// <summary>Returns the device id for a valid (non-revoked, unexpired) token, else null.</summary>
    public string? Validate(string token) => Find(token)?.Id;

    /// <summary>The device a valid (non-revoked, unexpired) token belongs to, else null.</summary>
    public DeviceRecord? Find(string token)
    {
        var hash = Encoding.ASCII.GetBytes(Ids.Sha256Hex(token));
        var now = _clock();
        lock (_gate)
        {
            foreach (var d in _devices)
            {
                if (!d.IsActiveAt(now)) continue;
                if (CryptographicOperations.FixedTimeEquals(hash, Encoding.ASCII.GetBytes(d.TokenSha256))) return Copy(d);
            }
        }
        return null;
    }

    private void Save()
    {
        if (_path == null) return;
        AtomicFile.WriteAllText(_path, JsonSerializer.Serialize(_devices, Json.Indented));
    }

    private static DeviceRecord Copy(DeviceRecord d) => new()
    {
        Id = d.Id, Name = d.Name, TokenSha256 = d.TokenSha256, CreatedAt = d.CreatedAt, Revoked = d.Revoked,
        SessionId = d.SessionId, ReadOnly = d.ReadOnly, ExpiresAt = d.ExpiresAt, SessionTitle = d.SessionTitle,
    };
}

/// <summary>
/// Who is calling. <see cref="SharedSession"/> is set for a guest invited to one session (see <see cref="ShareAccess"/>);
/// <see cref="ReadOnly"/> guests may watch but not type.
/// </summary>
public sealed record AuthResult(bool IsLocal, string? DeviceId, string? SharedSession = null, bool ReadOnly = false, string? ExpiresAt = null, string? SessionTitle = null)
{
    public bool IsGuest => SharedSession != null;
}

/// <summary>
/// Token rules (DESIGN §2): local.token is valid only from loopback; device tokens valid while not revoked.
/// </summary>
public sealed class TokenAuthenticator
{
    private readonly byte[] _localToken;
    public DeviceStore Devices { get; }

    public TokenAuthenticator(string localToken, DeviceStore devices)
    {
        _localToken = Encoding.UTF8.GetBytes(localToken);
        Devices = devices;
    }

    public static string LoadOrCreateLocalToken(string home)
    {
        var path = PkshellPaths.LocalTokenFile(home);
        if (File.Exists(path))
        {
            var t = File.ReadAllText(path, Encoding.UTF8).Trim();
            if (t.Length > 0) return t;
        }
        var token = Ids.NewToken();
        AtomicFile.WriteAllText(path, token);
        return token;
    }

    public static bool IsLoopback(IPAddress? remote)
    {
        if (remote == null) return false;
        if (remote.IsIPv4MappedToIPv6) remote = remote.MapToIPv4();
        return IPAddress.IsLoopback(remote);
    }

    public AuthResult? Authenticate(string? token, IPAddress? remote)
    {
        if (string.IsNullOrEmpty(token)) return null;
        var bytes = Encoding.UTF8.GetBytes(token);
        if (CryptographicOperations.FixedTimeEquals(bytes, _localToken))
            return IsLoopback(remote) ? new AuthResult(true, null) : null;
        var dev = Devices.Find(token);
        if (dev == null) return null;
        return dev.IsGuest ? new AuthResult(false, dev.Id, dev.SessionId, dev.ReadOnly, dev.ExpiresAt, dev.SessionTitle) : new AuthResult(false, dev.Id);
    }

    /// <summary>Extracts the bearer token from an Authorization header value.</summary>
    public static string? ParseBearer(string? header)
    {
        if (string.IsNullOrEmpty(header)) return null;
        const string prefix = "Bearer ";
        return header.StartsWith(prefix, StringComparison.OrdinalIgnoreCase) ? header[prefix.Length..].Trim() : null;
    }
}

/// <summary>One-time pairing codes, 5 minute expiry.</summary>
public sealed class PairingManager
{
    public static readonly TimeSpan Lifetime = TimeSpan.FromMinutes(5);
    private readonly Dictionary<string, DateTimeOffset> _codes = new(StringComparer.Ordinal);
    private readonly Func<DateTimeOffset> _clock;
    private readonly object _gate = new();

    public PairingManager(Func<DateTimeOffset>? clock = null) { _clock = clock ?? (() => DateTimeOffset.UtcNow); }

    public (string Code, DateTimeOffset ExpiresAt) Start()
    {
        var code = Ids.NewToken();
        var exp = _clock() + Lifetime;
        lock (_gate)
        {
            var now = _clock();
            foreach (var k in _codes.Where(kv => kv.Value <= now).Select(kv => kv.Key).ToList()) _codes.Remove(k);
            _codes[code] = exp;
        }
        return (code, exp);
    }

    /// <summary>Consumes the code. Returns false when unknown, already used, or expired.</summary>
    public bool TryConsume(string? code)
    {
        if (string.IsNullOrEmpty(code)) return false;
        lock (_gate)
        {
            if (!_codes.Remove(code, out var exp)) return false;
            return _clock() < exp;
        }
    }
}
