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
            Assert.Equal([webPort], WebServers.PagesOnly([webPort, otherPort]));
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
    public void OnlyResponsesThatAreWebPages()
    {
        Assert.True(WebServers.IsPageResponse("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n\r\n"));
        Assert.True(WebServers.IsPageResponse("HTTP/1.1 302 Found\r\nLocation: /login\r\n\r\n"));   // no type
        Assert.True(WebServers.IsPageResponse("HTTP/1.0 200 OK\r\n\r\n"));
        Assert.False(WebServers.IsPageResponse("HTTP/1.1 404 Not Found\r\n\r\n"));                // node --inspect (9229)
        Assert.False(WebServers.IsPageResponse("HTTP/1.1 403 Forbidden\r\nContent-Type: text/plain;charset=UTF-8\r\n\r\n")); // wrangler internals
        Assert.False(WebServers.IsPageResponse("HTTP/1.1 500 Internal Server Error\r\n\r\n"));
        Assert.False(WebServers.IsPageResponse("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n"));
        Assert.False(WebServers.IsPageResponse("SSH-2.0-OpenSSH"));
    }

    [Fact]
    public void PortsNamedOnTheCommandLineWin()
    {
        // wrangler pages dev --port 8790: workerd's own entry socket (random port, also HTML) is left out.
        string[] lines = ["node.exe wrangler-dist\\cli.js pages dev --port 8790 --ip 127.0.0.1", "workerd.exe serve --socket-addr=entry=127.0.0.1:0"];
        Assert.Equal([8790], WebServers.Named([8790, 11144], lines));
        Assert.Equal([5173, 5174], WebServers.Named([5173, 5174], ["node vite.js"])); // none named: all of them
        Assert.Equal([3000], WebServers.Named([3000, 13000], ["next dev -p 3000"]));  // 3000 inside 13000 is not a match
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
