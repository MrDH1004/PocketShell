using System.Text;

namespace Mpta.Core.Sessions;

/// <summary>
/// Incremental scanner for OSC 9;9;&lt;path&gt; and OSC 7;file://host/&lt;path&gt; in a VT byte stream.
/// Handles sequences split across chunks. Never modifies the stream; it only observes it.
/// </summary>
public sealed class OscCwdParser
{
    private const int MaxOscLength = 8192;
    private enum State { Ground, Esc, Osc, OscEsc }

    private State _state = State.Ground;
    private readonly List<byte> _buf = new();
    private bool _overflow;

    /// <summary>Feeds bytes; returns the last cwd completed in this chunk, or null.</summary>
    public string? Feed(ReadOnlySpan<byte> data)
    {
        string? result = null;
        foreach (var b in data)
        {
            switch (_state)
            {
                case State.Ground:
                    if (b == 0x1b) _state = State.Esc;
                    break;
                case State.Esc:
                    if (b == (byte)']') { _state = State.Osc; _buf.Clear(); _overflow = false; }
                    else if (b == 0x1b) _state = State.Esc;
                    else _state = State.Ground;
                    break;
                case State.Osc:
                    if (b == 0x07) { result = Complete() ?? result; _state = State.Ground; }
                    else if (b == 0x1b) _state = State.OscEsc;
                    else if (b == 0x18 || b == 0x1a) _state = State.Ground; // CAN/SUB abort
                    else Append(b);
                    break;
                case State.OscEsc:
                    if (b == (byte)'\\') { result = Complete() ?? result; _state = State.Ground; }
                    else if (b == (byte)']') { _state = State.Osc; _buf.Clear(); _overflow = false; } // ESC ] restarts
                    else _state = State.Ground; // aborted by another escape sequence
                    break;
            }
        }
        return result;
    }

    private void Append(byte b)
    {
        if (_buf.Count >= MaxOscLength) { _overflow = true; return; }
        _buf.Add(b);
    }

    private string? Complete()
    {
        if (_overflow || _buf.Count < 2) return null;
        var text = Encoding.UTF8.GetString(_buf.ToArray());
        _buf.Clear();
        if (text.StartsWith("9;9;", StringComparison.Ordinal))
        {
            var p = text[4..].Trim();
            if (p.Length >= 2 && p[0] == '"' && p[^1] == '"') p = p[1..^1];
            return p.Length == 0 ? null : p;
        }
        if (text.StartsWith("7;", StringComparison.Ordinal))
            return ParseFileUrl(text[2..]);
        return null;
    }

    /// <summary>file://host/path → local path. MSYS style /c/x → C:\x. Percent-decoding applied.</summary>
    internal static string? ParseFileUrl(string url)
    {
        const string prefix = "file://";
        if (!url.StartsWith(prefix, StringComparison.OrdinalIgnoreCase)) return null;
        var rest = url[prefix.Length..];
        int slash = rest.IndexOf('/');
        if (slash < 0) return null;
        var path = PercentDecode(rest[slash..]);
        if (path.Length == 0) return null;
        // /C:/Users/x → C:\Users\x
        if (path.Length >= 3 && path[0] == '/' && char.IsLetter(path[1]) && path[2] == ':')
            return path[1..].Replace('/', '\\');
        // MSYS /c or /c/Users/x → C:\Users\x
        if (path.Length >= 2 && path[0] == '/' && char.IsLetter(path[1]) && (path.Length == 2 || path[2] == '/'))
            return char.ToUpperInvariant(path[1]) + ":\\" + (path.Length > 3 ? path[3..].Replace('/', '\\') : "");
        return path; // POSIX path (e.g. WSL)
    }

    private static string PercentDecode(string s) => s.Contains('%') ? Uri.UnescapeDataString(s) : s;
}
