using System.Net;
using System.Net.Sockets;
using System.Text;
using Pkshell.Core.Util;

namespace Pkshell.Core.Net;

/// <summary>
/// LAN discovery responder (DESIGN §12.2): UDP datagram "PKSHELL?1" → JSON {v,name,hosts,port,fpShort}. Contains no
/// token or secret. Binds one socket per private IPv4 address (10/8, 172.16/12, 192.168/16) plus loopback for local
/// tooling; never 0.0.0.0 and never Tailscale CGNAT (no broadcast there — phones enter the IP directly).
/// </summary>
public sealed class DiscoveryResponder : IDisposable
{
    public const string Request = "PKSHELL?1";
    public const int DefaultPort = 7381;

    private readonly List<UdpClient> _sockets = new();
    private readonly CancellationTokenSource _cts = new();
    private readonly byte[] _reply;

    public IReadOnlyList<IPEndPoint> Endpoints => _sockets.Select(s => (IPEndPoint)s.Client.LocalEndPoint!).ToList();

    public DiscoveryResponder(byte[] reply) { _reply = reply; }

    public static bool IsPrivateLan(IPAddress ip)
    {
        if (ip.AddressFamily != AddressFamily.InterNetwork) return false;
        return HostAddresses.IsPrivateOrCgnat(ip) && !HostAddresses.IsCgnat(ip);
    }

    /// <summary>Addresses eligible for the discovery socket among the server's bind set.</summary>
    public static List<IPAddress> SelectAddresses(IEnumerable<IPAddress> binds) =>
        binds.Where(ip => IsPrivateLan(ip) || (ip.AddressFamily == AddressFamily.InterNetwork && IPAddress.IsLoopback(ip))).Distinct().ToList();

    public static byte[] BuildReply(string name, IEnumerable<string> hosts, int port, string fpShort) =>
        Encoding.UTF8.GetBytes(System.Text.Json.JsonSerializer.Serialize(new { v = 1, name, hosts = hosts.ToList(), port, fpShort }, Json.Options));

    public void Start(IEnumerable<IPAddress> addresses, int port)
    {
        foreach (var ip in addresses)
        {
            try
            {
                var udp = new UdpClient(AddressFamily.InterNetwork);
                udp.Client.ExclusiveAddressUse = true;
                udp.Client.Bind(new IPEndPoint(ip, port));
                _sockets.Add(udp);
                _ = Task.Run(() => Loop(udp));
            }
            catch (SocketException ex)
            {
                Log.Warn($"discovery: cannot bind udp {ip}:{port}: {ex.Message}");
            }
        }
        if (_sockets.Count > 0) Log.Info($"discovery: listening on udp {string.Join(", ", Endpoints)}");
    }

    private async Task Loop(UdpClient udp)
    {
        while (!_cts.IsCancellationRequested)
        {
            UdpReceiveResult r;
            try { r = await udp.ReceiveAsync(_cts.Token); }
            catch (OperationCanceledException) { return; }
            catch (ObjectDisposedException) { return; }
            catch (SocketException) { continue; } // e.g. ICMP port unreachable from an earlier reply (WSAECONNRESET)
            if (!IsRequest(r.Buffer)) continue;
            try { await udp.SendAsync(_reply, r.RemoteEndPoint, _cts.Token); }
            catch (Exception ex) when (ex is SocketException or OperationCanceledException or ObjectDisposedException) { }
        }
    }

    public static bool IsRequest(byte[] data) => Encoding.ASCII.GetString(data).Trim() == Request;

    public void Dispose()
    {
        _cts.Cancel();
        foreach (var s in _sockets) s.Dispose();
        _sockets.Clear();
    }
}
