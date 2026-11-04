using System.Security.Cryptography;
using System.Text;

namespace Mpta.Core.Auth;

public enum PinClaimResult { Ok, Invalid }

/// <summary>
/// PIN pairing (DESIGN §12.1): 6-digit crypto-random PIN, 5 minute lifetime, one-time, a single active PIN
/// (issuing a new one revokes the previous), and the PIN dies after 5 wrong attempts.
/// </summary>
public sealed class PinPairing
{
    public static readonly TimeSpan Lifetime = TimeSpan.FromMinutes(5);
    public const int MaxWrongAttempts = 5;

    private readonly Func<DateTimeOffset> _clock;
    private readonly object _gate = new();
    private string? _pin;
    private DateTimeOffset _expires;
    private int _wrong;

    public PinPairing(Func<DateTimeOffset>? clock = null) { _clock = clock ?? (() => DateTimeOffset.UtcNow); }

    public static string NewPin() => RandomNumberGenerator.GetInt32(0, 1_000_000).ToString("D6", System.Globalization.CultureInfo.InvariantCulture);

    public (string Pin, DateTimeOffset ExpiresAt) Start()
    {
        lock (_gate)
        {
            _pin = NewPin();
            _expires = _clock() + Lifetime;
            _wrong = 0;
            return (_pin, _expires);
        }
    }

    /// <summary>True while a PIN is issued, unexpired, not consumed and not killed by wrong attempts.</summary>
    public bool HasActivePin
    {
        get { lock (_gate) return _pin != null && _clock() < _expires; }
    }

    public PinClaimResult TryConsume(string? pin)
    {
        lock (_gate)
        {
            if (_pin == null || _clock() >= _expires)
            {
                _pin = null;
                return PinClaimResult.Invalid;
            }
            var given = Encoding.ASCII.GetBytes(pin?.Trim() ?? "");
            if (given.Length == _pin.Length && CryptographicOperations.FixedTimeEquals(given, Encoding.ASCII.GetBytes(_pin)))
            {
                _pin = null; // one-time
                return PinClaimResult.Ok;
            }
            if (++_wrong >= MaxWrongAttempts) _pin = null; // brute force guard: PIN must be re-issued
            return PinClaimResult.Invalid;
        }
    }

    /// <summary>First 8 bytes of the SHA-256 cert fingerprint as "XXXX-XXXX-XXXX-XXXX" (uppercase hex).</summary>
    public static string FpShort(string fingerprintHex)
    {
        var hex = fingerprintHex.Replace(":", "").Replace("-", "").Trim();
        if (hex.Length < 16 || !hex[..16].All(char.IsAsciiHexDigit)) throw new FormatException("fingerprint must be hex with at least 8 bytes");
        var u = hex[..16].ToUpperInvariant();
        return $"{u[..4]}-{u[4..8]}-{u[8..12]}-{u[12..16]}";
    }
}

/// <summary>Per remote IP claim failure limiter (DESIGN §12.1): more than 10 failures in 10 minutes → HTTP 429.</summary>
public sealed class ClaimRateLimiter
{
    public const int MaxFailures = 10;
    public static readonly TimeSpan Window = TimeSpan.FromMinutes(10);

    private readonly Func<DateTimeOffset> _clock;
    private readonly Dictionary<string, Queue<DateTimeOffset>> _failures = new(StringComparer.Ordinal);
    private readonly object _gate = new();

    public ClaimRateLimiter(Func<DateTimeOffset>? clock = null) { _clock = clock ?? (() => DateTimeOffset.UtcNow); }

    public bool IsLimited(string ip)
    {
        lock (_gate) return Prune(ip) >= MaxFailures;
    }

    public void RecordFailure(string ip)
    {
        lock (_gate)
        {
            Prune(ip);
            if (!_failures.TryGetValue(ip, out var q)) _failures[ip] = q = new Queue<DateTimeOffset>();
            q.Enqueue(_clock());
        }
    }

    private int Prune(string ip)
    {
        if (!_failures.TryGetValue(ip, out var q)) return 0;
        var cutoff = _clock() - Window;
        while (q.Count > 0 && q.Peek() <= cutoff) q.Dequeue();
        if (q.Count == 0) _failures.Remove(ip);
        return q.Count;
    }
}
