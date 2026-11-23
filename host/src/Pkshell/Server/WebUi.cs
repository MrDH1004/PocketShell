using System.Reflection;
using System.Security.Cryptography;
using Microsoft.AspNetCore.Http;

namespace Pkshell.Server;

/// <summary>
/// Browser client (0.2.112+): the page a Mac / iPhone / any browser opens at https://&lt;PC&gt;:7380/. The files are embedded
/// in psh.exe (Web\**). They are served without a token: the page holds no data, every /api and /ws call still needs the
/// device token it gets by PIN pairing.
/// </summary>
public static class WebUi
{
    public sealed record Asset(byte[] Body, string ContentType, string ETag);

    private const string Prefix = "web/";

    private static readonly Lazy<IReadOnlyDictionary<string, Asset>> Assets = new(Load);

    private static readonly Dictionary<string, string> Types = new(StringComparer.OrdinalIgnoreCase)
    {
        [".html"] = "text/html; charset=utf-8",
        [".js"] = "text/javascript; charset=utf-8",
        [".css"] = "text/css; charset=utf-8",
        [".json"] = "application/manifest+json; charset=utf-8",
        [".ico"] = "image/x-icon",
        [".png"] = "image/png",
        [".svg"] = "image/svg+xml",
        [".txt"] = "text/plain; charset=utf-8",
        [".woff2"] = "font/woff2",
    };

    /// <summary>Served path ("/", "/app.js", "/vendor/xterm.js") → file; "/" is index.html.</summary>
    public static Asset? Find(string path)
    {
        var key = path is "" or "/" ? "index.html" : path.TrimStart('/');
        return Assets.Value.TryGetValue(key, out var a) ? a : null;
    }

    public static int Count => Assets.Value.Count;

    private static IReadOnlyDictionary<string, Asset> Load()
    {
        var asm = Assembly.GetExecutingAssembly();
        var map = new Dictionary<string, Asset>(StringComparer.Ordinal);
        foreach (var name in asm.GetManifestResourceNames())
        {
            if (!name.StartsWith(Prefix, StringComparison.Ordinal)) continue;
            var rel = name.Substring(Prefix.Length).Replace('\\', '/');
            if (!Types.TryGetValue(Path.GetExtension(rel), out var type)) continue;
            using var s = asm.GetManifestResourceStream(name);
            if (s == null) continue;
            using var ms = new MemoryStream();
            s.CopyTo(ms);
            var body = ms.ToArray();
            // The page knows which host build it came from, so an open (installed) page can reload itself after an update.
            if (rel == "app.js")
                body = System.Text.Encoding.UTF8.GetBytes(System.Text.Encoding.UTF8.GetString(body).Replace("__PKSHELL_VERSION__", HostServer.Version, StringComparison.Ordinal));
            var etag = "\"" + Convert.ToHexString(SHA256.HashData(body), 0, 8).ToLowerInvariant() + "\"";
            map[rel] = new Asset(body, type, etag);
        }
        return map;
    }

    /// <summary>GET/HEAD of a page file: true when it was served (200 or 304).</summary>
    public static async Task<bool> TryServe(HttpContext ctx)
    {
        var req = ctx.Request;
        if (!HttpMethods.IsGet(req.Method) && !HttpMethods.IsHead(req.Method)) return false;
        var a = Find(req.Path.Value ?? "/");
        if (a == null) return false;
        var h = ctx.Response.Headers;
        h["X-Content-Type-Options"] = "nosniff";
        h["Referrer-Policy"] = "no-referrer";
        h["Cache-Control"] = "no-cache"; // revalidate by ETag: a host update changes the page at once
        h.ETag = a.ETag;
        if (a.ContentType.StartsWith("text/html", StringComparison.Ordinal))
        {
            // Scripts only from this PC (no inline script); xterm.js sets inline styles. connect: this origin's wss.
            // frame: the web view's forwarded dev servers (WebForward), other ports of this same host name.
            var host = req.Host.Value ?? "";
            h["Content-Security-Policy"] = $"default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src 'self' wss://{host}; frame-src https://{req.Host.Host}:*; frame-ancestors 'none'; base-uri 'none'; form-action 'self'";
            h["X-Frame-Options"] = "DENY";
        }
        if (req.Headers.IfNoneMatch.ToString() == a.ETag)
        {
            ctx.Response.StatusCode = StatusCodes.Status304NotModified;
            return true;
        }
        ctx.Response.ContentType = a.ContentType;
        ctx.Response.ContentLength = a.Body.Length;
        if (HttpMethods.IsGet(req.Method)) await ctx.Response.Body.WriteAsync(a.Body);
        return true;
    }
}
