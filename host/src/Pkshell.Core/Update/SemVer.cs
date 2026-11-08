using System.Text.RegularExpressions;

namespace Pkshell.Core.Update;

/// <summary>SemVer 2.0 version with prerelease precedence (DESIGN §11.1). Build metadata is parsed and ignored for ordering.</summary>
public sealed class SemVer : IComparable<SemVer>, IEquatable<SemVer>
{
    // semver.org reference grammar (numeric identifiers without leading zeros).
    private static readonly Regex Grammar = new(
        @"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)" +
        @"(?:-((?:0|[1-9]\d*|\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\.(?:0|[1-9]\d*|\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?" +
        @"(?:\+([0-9a-zA-Z-]+(?:\.[0-9a-zA-Z-]+)*))?$",
        RegexOptions.CultureInvariant);

    public long Major { get; }
    public long Minor { get; }
    public long Patch { get; }
    public IReadOnlyList<string> Prerelease { get; }
    public string Build { get; }
    public bool IsPrerelease => Prerelease.Count > 0;

    private SemVer(long major, long minor, long patch, string[] pre, string build)
    {
        Major = major;
        Minor = minor;
        Patch = patch;
        Prerelease = pre;
        Build = build;
    }

    /// <summary>Parses "1.2.3", "1.2.3-alpha.1", "1.2.3+build"; a leading 'v'/'V' (git tag) is accepted.</summary>
    public static bool TryParse(string? s, out SemVer version)
    {
        version = null!;
        if (string.IsNullOrWhiteSpace(s)) return false;
        s = s.Trim();
        if (s[0] is 'v' or 'V') s = s[1..];
        var m = Grammar.Match(s);
        if (!m.Success) return false;
        if (!long.TryParse(m.Groups[1].Value, out var major) || !long.TryParse(m.Groups[2].Value, out var minor)
            || !long.TryParse(m.Groups[3].Value, out var patch)) return false;
        var pre = m.Groups[4].Success ? m.Groups[4].Value.Split('.') : Array.Empty<string>();
        version = new SemVer(major, minor, patch, pre, m.Groups[5].Success ? m.Groups[5].Value : "");
        return true;
    }

    public static SemVer Parse(string s) => TryParse(s, out var v) ? v : throw new FormatException($"not a SemVer 2.0 version: '{s}'");

    public int CompareTo(SemVer? other)
    {
        if (other is null) return 1;
        int c = Major.CompareTo(other.Major);
        if (c != 0) return c;
        c = Minor.CompareTo(other.Minor);
        if (c != 0) return c;
        c = Patch.CompareTo(other.Patch);
        if (c != 0) return c;
        // A version without prerelease has higher precedence than one with.
        if (!IsPrerelease) return other.IsPrerelease ? 1 : 0;
        if (!other.IsPrerelease) return -1;
        int n = Math.Min(Prerelease.Count, other.Prerelease.Count);
        for (int i = 0; i < n; i++)
        {
            c = CompareIdentifier(Prerelease[i], other.Prerelease[i]);
            if (c != 0) return c;
        }
        return Prerelease.Count.CompareTo(other.Prerelease.Count);
    }

    private static int CompareIdentifier(string a, string b)
    {
        bool an = IsNumeric(a), bn = IsNumeric(b);
        if (an && bn)
        {
            // No leading zeros (grammar), so longer = larger; equal length compares lexically. Avoids overflow.
            int c = a.Length.CompareTo(b.Length);
            return c != 0 ? c : string.CompareOrdinal(a, b);
        }
        if (an) return -1; // numeric identifiers have lower precedence than alphanumeric
        if (bn) return 1;
        return Math.Sign(string.CompareOrdinal(a, b));
    }

    private static bool IsNumeric(string s) => s.Length > 0 && s.All(char.IsAsciiDigit);

    public bool Equals(SemVer? other) => other is not null && CompareTo(other) == 0;
    public override bool Equals(object? obj) => obj is SemVer v && Equals(v);
    public override int GetHashCode() => HashCode.Combine(Major, Minor, Patch, string.Join('.', Prerelease));

    /// <summary>Normalized "M.m.p[-pre]" (build metadata omitted) — the form used in tags and asset names.</summary>
    public override string ToString() => IsPrerelease ? $"{Major}.{Minor}.{Patch}-{string.Join('.', Prerelease)}" : $"{Major}.{Minor}.{Patch}";

    // null sorts below any version (consistent with CompareTo(null) == 1).
    private static int Compare(SemVer? a, SemVer? b) => a is null ? (b is null ? 0 : -1) : a.CompareTo(b);

    public static bool operator ==(SemVer? a, SemVer? b) => Compare(a, b) == 0;
    public static bool operator !=(SemVer? a, SemVer? b) => Compare(a, b) != 0;
    public static bool operator <(SemVer? a, SemVer? b) => Compare(a, b) < 0;
    public static bool operator >(SemVer? a, SemVer? b) => Compare(a, b) > 0;
    public static bool operator <=(SemVer? a, SemVer? b) => Compare(a, b) <= 0;
    public static bool operator >=(SemVer? a, SemVer? b) => Compare(a, b) >= 0;
}
