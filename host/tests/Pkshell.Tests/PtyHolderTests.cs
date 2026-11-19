using System.Collections.Concurrent;
using System.Text;
using Pkshell.Core.Pty;
using Pkshell.Core.Sessions;

namespace Pkshell.Tests;

/// <summary>The shell held outside the host: input, output, a host going away and the next one reattaching.</summary>
public sealed class PtyHolderTests
{
    private sealed class FakeShell : IPty
    {
        public readonly ConcurrentQueue<string> Input = new();
        public Action<byte[]> Out = _ => { };
        public Action<int> Exit = _ => { };
        public (int, int) Size;
        public int Pid => 4242;
        public bool SurvivesHost => false;
        public void Write(ReadOnlySpan<byte> data) => Input.Enqueue(Encoding.UTF8.GetString(data));
        public void Resize(int cols, int rows) => Size = (cols, rows);
        public void Kill() => Exit(137);
        public void Dispose() { }
        public void Say(string s) => Out(Encoding.UTF8.GetBytes(s));
    }

    private sealed class Sink
    {
        private readonly StringBuilder _text = new();
        public readonly TaskCompletionSource<int> Exited = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public void Output(byte[] b) { lock (_text) _text.Append(Encoding.UTF8.GetString(b)); }
        public void Exit(int code) => Exited.TrySetResult(code);
        public string Text { get { lock (_text) return _text.ToString(); } }
    }

    private static (FakeShell Shell, Task<int> Holder, string Pipe) StartHolder()
    {
        var shell = new FakeShell();
        var spec = new PtySpec { Pipe = "pocketshell-pty-test-" + Guid.NewGuid().ToString("N"), CommandLine = "fake", Cols = 80, Rows = 24 };
        var holder = Task.Run(() => new PtyHolder(spec).Run((_, o, x) => { shell.Out = o; shell.Exit = x; return shell; }));
        return (shell, holder, spec.Pipe);
    }

    private static void Until(Func<bool> cond, string what)
    {
        var end = DateTime.UtcNow.AddSeconds(5);
        while (!cond())
        {
            if (DateTime.UtcNow > end) throw new TimeoutException(what);
            Thread.Sleep(20);
        }
    }

    [Fact]
    public async Task ForwardsBothWaysAndKeepsOutputWhileNoHostIsConnected()
    {
        var (shell, holder, pipe) = StartHolder();
        var a = new Sink();
        var first = RemotePty.Connect(pipe, TimeSpan.FromSeconds(5), a.Output, a.Exit);
        Assert.NotNull(first);
        Assert.Equal(4242, first!.Pid);
        Assert.True(first.SurvivesHost);

        first.Write(Encoding.UTF8.GetBytes("dir\r"));
        first.Resize(100, 40);
        shell.Say("hello ");
        Until(() => shell.Input.Contains("dir\r") && shell.Size == (100, 40) && a.Text == "hello ", "first host round trip");

        // The host hands over (update): the shell keeps running and its output waits for the next host.
        first.Detach();
        Thread.Sleep(100);
        shell.Say("while ");
        shell.Say("away");
        Assert.False(a.Exited.Task.IsCompleted);

        var b = new Sink();
        var second = RemotePty.Connect(pipe, TimeSpan.FromSeconds(5), b.Output, b.Exit);
        Assert.NotNull(second);
        Until(() => b.Text == "while away", "buffered output after reattach");
        second!.Kill();
        Assert.Equal(137, await b.Exited.Task.WaitAsync(TimeSpan.FromSeconds(5)));
        await holder.WaitAsync(TimeSpan.FromSeconds(10)); // holder ends after the host learned the exit
        Assert.Equal("hello ", a.Text); // the first host saw nothing after it let go
    }

    [Fact]
    public async Task ShellEndingWhileNoHostIsConnectedIsReportedToTheNextOne()
    {
        var (shell, holder, pipe) = StartHolder();
        var a = new Sink();
        var first = RemotePty.Connect(pipe, TimeSpan.FromSeconds(5), a.Output, a.Exit)!;
        first.Detach();
        Thread.Sleep(100);
        shell.Say("bye");
        shell.Exit(3);

        var b = new Sink();
        var second = RemotePty.Connect(pipe, TimeSpan.FromSeconds(5), b.Output, b.Exit);
        Assert.NotNull(second);
        Assert.Equal(3, await b.Exited.Task.WaitAsync(TimeSpan.FromSeconds(5)));
        Assert.Equal("bye", b.Text);
        await holder.WaitAsync(TimeSpan.FromSeconds(10));
    }

    [Fact]
    public async Task ResizeUnderTheSessionLockDoesNotWaitForTheHolder()
    {
        // The session lock is held around Resize and output delivery takes it too. With output queued in the holder
        // (made while no host was connected) the holder is busy sending it and not reading; Resize must not wait.
        var (shell, holder, pipe) = StartHolder();
        Thread.Sleep(200);
        for (int i = 0; i < 64; i++) shell.Say(new string((char)('a' + i % 26), 4096));
        var sessionLock = new object();
        var got = new StringBuilder();
        var exited = new TaskCompletionSource<int>(TaskCreationOptions.RunContinuationsAsynchronously);
        RemotePty? pty;
        var resized = Task.Run(() =>
        {
            lock (sessionLock)
            {
                pty = RemotePty.Connect(pipe, TimeSpan.FromSeconds(5), b => { lock (sessionLock) got.Append(Encoding.UTF8.GetString(b)); }, c => exited.TrySetResult(c));
                pty!.Resize(90, 33);
                return pty;
            }
        });
        var p = await resized.WaitAsync(TimeSpan.FromSeconds(5));
        Until(() => shell.Size == (90, 33), "resize reached the shell");
        Until(() => { lock (sessionLock) return got.Length == 64 * 4096; }, "queued output delivered");
        p.Kill();
        Assert.Equal(137, await exited.Task.WaitAsync(TimeSpan.FromSeconds(5)));
        await holder.WaitAsync(TimeSpan.FromSeconds(10));
    }

    [Fact]
    public void NothingListeningIsNotAHolder()
    {
        var sink = new Sink();
        Assert.Null(RemotePty.Connect("pocketshell-pty-none-" + Guid.NewGuid().ToString("N"), TimeSpan.FromMilliseconds(300), sink.Output, sink.Exit));
    }

    [Fact]
    public async Task ShellThatCannotStartIsAnError()
    {
        var spec = new PtySpec { Pipe = "pocketshell-pty-test-" + Guid.NewGuid().ToString("N"), CommandLine = "x", Cols = 80, Rows = 24 };
        var holder = Task.Run(() => new PtyHolder(spec).Run((_, _, _) => throw new InvalidOperationException("no such shell")));
        var sink = new Sink();
        var ex = Assert.Throws<InvalidOperationException>(() => RemotePty.Connect(spec.Pipe, TimeSpan.FromSeconds(5), sink.Output, sink.Exit));
        Assert.Equal("no such shell", ex.Message);
        Assert.Equal(1, await holder.WaitAsync(TimeSpan.FromSeconds(5)));
    }

    /// <summary>A launcher whose holders run on threads of the test process (no psh.exe to start).</summary>
    private sealed class ThreadHolderLauncher : IPtyLauncher
    {
        public readonly List<Task<int>> Holders = new();

        public IPty Start(string sessionDir, string commandLine, string? cwd, Dictionary<string, string>? env, int cols, int rows, Action<byte[]> onOutput, Action<int> onExit)
        {
            var spec = new PtySpec { Pipe = "pocketshell-pty-test-" + Guid.NewGuid().ToString("N"), CommandLine = commandLine, Cwd = cwd, Env = env, Cols = cols, Rows = rows };
            File.WriteAllText(PtySpec.PathIn(sessionDir), System.Text.Json.JsonSerializer.Serialize(spec, Pkshell.Core.Util.Json.Options));
            Holders.Add(Task.Run(() => new PtyHolder(spec).Run()));
            return RemotePty.Connect(spec.Pipe, TimeSpan.FromSeconds(10), onOutput, onExit) ?? throw new InvalidOperationException("holder did not answer");
        }

        public IPty? Reattach(string sessionDir, Action<byte[]> onOutput, Action<int> onExit)
        {
            var spec = PtySpec.Read(PtySpec.PathIn(sessionDir));
            return spec == null ? null : RemotePty.Connect(spec.Pipe, TimeSpan.FromSeconds(2), onOutput, onExit);
        }
    }

    [Fact]
    public async Task RealShellSurvivesTheSessionManagerHandingOver()
    {
        var home = Path.Combine(Path.GetTempPath(), "psh-holder-" + Guid.NewGuid().ToString("N"));
        var launcher = new ThreadHolderLauncher();
        try
        {
            var first = new SessionManager(home, launcher);
            var s = first.Create(new CreateSessionRequest { Shell = "cmd", Cwd = Path.GetTempPath(), Cols = 80, Rows = 24 });
            var id = s.Id;
            int pid = s.ShellPid!.Value;
            Assert.True(s.SurvivesHost);
            Assert.Equal(0, first.BlockingUpdate);
            s.WriteInput(Encoding.UTF8.GetBytes("echo before-handoff\r"));
            Until(() => Encoding.UTF8.GetString(s.ReadScrollback()).Contains("before-handoff\r\n"), "echo before the handoff");

            first.Detach(); // update: the old host lets go

            var second = new SessionManager(home, launcher);
            var again = second.Get(id);
            Assert.NotNull(again);
            Assert.True(again!.IsRunning);
            Assert.Equal(pid, again.ShellPid);
            Assert.Equal("host", again.Snapshot().Kind);
            again.WriteInput(Encoding.UTF8.GetBytes("echo after-handoff\r"));
            Until(() => Encoding.UTF8.GetString(again.ReadScrollback()).Contains("after-handoff\r\n"), "echo after the handoff");
            Assert.Contains("before-handoff", Encoding.UTF8.GetString(again.ReadScrollback()));

            second.Dispose(); // an explicit stop still ends the shell
            Assert.False(again.IsRunning);
            await Task.WhenAll(launcher.Holders).WaitAsync(TimeSpan.FromSeconds(10)); // holders end with their shell
            Assert.False(File.Exists(PtySpec.PathIn(again.Dir)));
        }
        finally
        {
            try { Directory.Delete(home, true); } catch (IOException) { }
        }
    }
}
