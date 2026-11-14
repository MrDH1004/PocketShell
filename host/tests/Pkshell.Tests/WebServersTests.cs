using System.Net;
using System.Net.Sockets;
using Pkshell.Core.Projects;

namespace Pkshell.Tests;

public class WebServersTests
{
    [Fact]
    public void PortsOfProcessesWorkingInTheFolderOrBelow()
    {
        var listeners = new List<(int, string)>
        {
            (5175, @"E:\Work\site"), (5175, @"E:\Work\site"), (3000, @"E:\Work\site\web"),
            (8080, @"E:\Work\other"), (9000, @"E:\Work\site-old"),
        };
        Assert.Equal([3000, 5175], WebServers.For(@"E:\Work\site\", listeners));
        Assert.Empty(WebServers.For(@"E:\Work\none", listeners));
        Assert.Empty(WebServers.For(@"E:\", listeners)); // a drive root is not a project
        Assert.Empty(WebServers.For("", listeners));
    }

    [Fact]
    public async Task OnlyPortsThatAnswerHttp()
    {
        var web = new TcpListener(IPAddress.Loopback, 0);
        var other = new TcpListener(IPAddress.Loopback, 0);
        web.Start();
        other.Start();
        var webPort = ((IPEndPoint)web.LocalEndpoint).Port;
        var otherPort = ((IPEndPoint)other.LocalEndpoint).Port;
        var serve = Task.Run(async () =>
        {
            using var c = await web.AcceptTcpClientAsync();
            var s = c.GetStream();
            await s.ReadAsync(new byte[256]);
            await s.WriteAsync("HTTP/1.0 200 OK\r\n\r\n"u8.ToArray());
        });
        var silent = Task.Run(async () => { using var c = await other.AcceptTcpClientAsync(); await Task.Delay(1000); });
        try
        {
            Assert.Equal([webPort], WebServers.HttpOnly([webPort, otherPort]));
        }
        finally
        {
            web.Stop();
            other.Stop();
            await Task.WhenAny(serve, Task.Delay(2000));
            await Task.WhenAny(silent, Task.Delay(2000));
        }
    }

    [Fact]
    public void ListeningPortsIncludeOurOwnListener()
    {
        var l = new TcpListener(IPAddress.Loopback, 0);
        l.Start();
        try
        {
            var port = ((IPEndPoint)l.LocalEndpoint).Port;
            Assert.Contains((port, Environment.ProcessId), WebServers.ListeningPorts());
        }
        finally { l.Stop(); }
    }
}
