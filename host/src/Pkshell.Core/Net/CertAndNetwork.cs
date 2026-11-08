using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using Pkshell.Core.Util;

namespace Pkshell.Core.Net;

public static class CertManager
{
    /// <summary>Loads cert.pfx or creates a self-signed RSA-2048 CN=PocketShell certificate valid for 10 years. Writes cert.sha256.</summary>
    public static (X509Certificate2 Cert, string Fingerprint) LoadOrCreate(string home)
    {
        var pfx = PkshellPaths.CertFile(home);
        if (!File.Exists(pfx))
        {
            using var rsa = RSA.Create(2048);
            var req = new CertificateRequest("CN=PocketShell", rsa, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
            req.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, false));
            req.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature | X509KeyUsageFlags.KeyEncipherment, false));
            req.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(new OidCollection { new Oid("1.3.6.1.5.5.7.3.1") }, false));
            var san = new SubjectAlternativeNameBuilder();
            san.AddDnsName("pocketshell");
            san.AddDnsName("localhost");
            san.AddIpAddress(IPAddress.Loopback);
            req.CertificateExtensions.Add(san.Build());
            var now = DateTimeOffset.UtcNow;
            using var created = req.CreateSelfSigned(now.AddDays(-1), now.AddYears(10));
            Directory.CreateDirectory(home);
            File.WriteAllBytes(pfx, created.Export(X509ContentType.Pfx));
            Log.Info($"created self-signed certificate {pfx}");
        }
        // Loading from PFX (not ephemeral) keeps SChannel happy on Windows.
        var cert = new X509Certificate2(pfx, (string?)null, X509KeyStorageFlags.UserKeySet | X509KeyStorageFlags.Exportable);
        var fp = Fingerprint(cert);
        File.WriteAllText(PkshellPaths.CertFingerprintFile(home), fp);
        return (cert, fp);
    }

    /// <summary>SHA-256 of the DER certificate, lowercase hex, no separators.</summary>
    public static string Fingerprint(X509Certificate cert) => Ids.Sha256Hex(cert.GetRawCertData());
}

public static class HostAddresses
{
    public static bool IsPrivateOrCgnat(IPAddress ip)
    {
        if (ip.AddressFamily != AddressFamily.InterNetwork) return false;
        var b = ip.GetAddressBytes();
        if (b[0] == 10) return true;
        if (b[0] == 172 && b[1] >= 16 && b[1] <= 31) return true;
        if (b[0] == 192 && b[1] == 168) return true;
        return IsCgnat(ip);
    }

    public static bool IsCgnat(IPAddress ip)
    {
        if (ip.AddressFamily != AddressFamily.InterNetwork) return false;
        var b = ip.GetAddressBytes();
        return b[0] == 100 && b[1] >= 64 && b[1] <= 127; // 100.64.0.0/10
    }

    /// <summary>Operational interface IPv4 addresses in private ranges (LAN first, Tailscale CGNAT last).</summary>
    public static List<IPAddress> DiscoverInterfaceAddresses()
    {
        var list = new List<IPAddress>();
        foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (nic.OperationalStatus != OperationalStatus.Up) continue;
            if (nic.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
            IPInterfaceProperties props;
            try { props = nic.GetIPProperties(); }
            catch (NetworkInformationException) { continue; }
            foreach (var ua in props.UnicastAddresses)
            {
                var ip = ua.Address;
                if (IsPrivateOrCgnat(ip) && !list.Contains(ip)) list.Add(ip);
            }
        }
        return list.OrderBy(ip => IsCgnat(ip) ? 1 : 0).ToList();
    }

    /// <summary>Bind set: explicit --bind list if given, else 127.0.0.1 + private/CGNAT interface addresses. Never 0.0.0.0.</summary>
    public static List<IPAddress> ResolveBindAddresses(IReadOnlyList<string> explicitBinds)
    {
        if (explicitBinds.Count > 0)
        {
            var parsed = new List<IPAddress>();
            foreach (var s in explicitBinds)
            {
                if (!IPAddress.TryParse(s, out var ip)) throw new ArgumentException($"invalid --bind address: {s}");
                if (ip.Equals(IPAddress.Any) || ip.Equals(IPAddress.IPv6Any)) throw new ArgumentException("binding to 0.0.0.0/:: is not allowed");
                if (!parsed.Contains(ip)) parsed.Add(ip);
            }
            return parsed;
        }
        var result = new List<IPAddress> { IPAddress.Loopback };
        result.AddRange(DiscoverInterfaceAddresses());
        return result;
    }
}
