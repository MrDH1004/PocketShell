using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using Pkshell.Core.Native;
using Pkshell.Core.Sessions;

namespace Pkshell.Cli;

/// <summary>`pkshell attach &lt;id&gt;`: raw VT console bridge. Ctrl+] then d detaches.</summary>
public static class AttachCommand
{
    private const char DetachPrefix = '\x1d'; // Ctrl+]

    public static async Task<int> RunAsync(ApiClient api, string sessionId)
    {
        IntPtr hIn = Win32.GetStdHandle(Win32.STD_INPUT_HANDLE);
        IntPtr hOut = Win32.GetStdHandle(Win32.STD_OUTPUT_HANDLE);
        if (!Win32.GetConsoleMode(hIn, out uint oldIn) || !Win32.GetConsoleMode(hOut, out uint oldOut))
            throw new CliException("attach requires an interactive console (stdin/stdout must be a console): " + Win32.LastError("GetConsoleMode").Message);
        uint oldInCp = Win32.GetConsoleCP(), oldOutCp = Win32.GetConsoleOutputCP();

        var (cols, rows) = WindowSize(hOut);
        using var cts = new CancellationTokenSource();
        using var ws = await api.ConnectWsAsync(sessionId, cols, rows, cts.Token);

        uint newIn = (oldIn & ~(Win32.ENABLE_PROCESSED_INPUT | Win32.ENABLE_LINE_INPUT | Win32.ENABLE_ECHO_INPUT | Win32.ENABLE_MOUSE_INPUT | Win32.ENABLE_QUICK_EDIT_MODE))
                     | Win32.ENABLE_VIRTUAL_TERMINAL_INPUT | Win32.ENABLE_WINDOW_INPUT | Win32.ENABLE_EXTENDED_FLAGS;
        uint newOut = oldOut | Win32.ENABLE_PROCESSED_OUTPUT | Win32.ENABLE_VIRTUAL_TERMINAL_PROCESSING | Win32.DISABLE_NEWLINE_AUTO_RETURN;
        if (!Win32.SetConsoleMode(hIn, newIn)) throw new CliException(Win32.LastError("SetConsoleMode(input)").Message);
        if (!Win32.SetConsoleMode(hOut, newOut)) throw new CliException(Win32.LastError("SetConsoleMode(output)").Message);
        if (!Win32.SetConsoleOutputCP(Win32.CP_UTF8)) Console.Error.WriteLine(Win32.LastError("SetConsoleOutputCP").Message);
        if (!Win32.SetConsoleCP(Win32.CP_UTF8)) Console.Error.WriteLine(Win32.LastError("SetConsoleCP").Message);

        var stdout = Console.OpenStandardOutput();
        string? endMessage = null;
        var sendLock = new SemaphoreSlim(1, 1);

        async Task Send(ReadOnlyMemory<byte> data, WebSocketMessageType type)
        {
            await sendLock.WaitAsync();
            try { if (ws.State == WebSocketState.Open) await ws.SendAsync(data, type, true, CancellationToken.None); }
            finally { sendLock.Release(); }
        }

        // Output pump: raw bytes to stdout, control JSON handled.
        var recv = Task.Run(async () =>
        {
            var buf = new byte[64 * 1024];
            using var msg = new MemoryStream();
            try
            {
                while (!cts.IsCancellationRequested)
                {
                    var r = await ws.ReceiveAsync(buf, cts.Token);
                    if (r.MessageType == WebSocketMessageType.Close)
                    {
                        endMessage ??= $"[connection closed by host: {(int?)ws.CloseStatus} {ws.CloseStatusDescription}]";
                        break;
                    }
                    msg.Write(buf, 0, r.Count);
                    if (!r.EndOfMessage) continue;
                    if (r.MessageType == WebSocketMessageType.Binary)
                    {
                        msg.Position = 0;
                        msg.CopyTo(stdout);
                        stdout.Flush();
                    }
                    else
                    {
                        using var doc = JsonDocument.Parse(msg.ToArray());
                        if (doc.RootElement.TryGetProperty("type", out var t) && t.GetString() == "exit")
                        {
                            int code = doc.RootElement.TryGetProperty("code", out var c) && c.TryGetInt32(out var ci) ? ci : -1;
                            endMessage = $"[session exited with code {code}]";
                            break;
                        }
                    }
                    msg.SetLength(0);
                }
            }
            catch (Exception ex) when (ex is WebSocketException or OperationCanceledException or JsonException)
            {
                endMessage ??= $"[connection lost: {ex.Message}]";
            }
            cts.Cancel();
        });

        // Resize watcher: polls window size (covers terminals that don't emit WINDOW_BUFFER_SIZE_EVENT reliably).
        var lastSize = (cols, rows);
        var resizeLock = new object();
        async Task CheckResize()
        {
            var sz = WindowSize(hOut);
            bool changed;
            lock (resizeLock) { changed = sz != lastSize; if (changed) lastSize = sz; }
            if (changed)
                await Send(Encoding.UTF8.GetBytes($"{{\"type\":\"resize\",\"cols\":{sz.Item1},\"rows\":{sz.Item2}}}"), WebSocketMessageType.Text);
        }
        var resizeTask = Task.Run(async () =>
        {
            while (!cts.IsCancellationRequested)
            {
                try { await Task.Delay(300, cts.Token); } catch (OperationCanceledException) { break; }
                await CheckResize();
            }
        });

        // Input pump: ReadConsoleInputW (blocking) on a dedicated thread; VT input arrives as characters.
        bool detached = false;
        var inputThread = new Thread(() =>
        {
            var recs = new Win32.INPUT_RECORD[512];
            var encoder = Encoding.UTF8.GetEncoder();
            var filter = new AttachInputFilter();
            var sb = new StringBuilder();
            while (!cts.IsCancellationRequested)
            {
                if (!Win32.ReadConsoleInput(hIn, recs, (uint)recs.Length, out uint n))
                {
                    endMessage ??= "[" + Win32.LastError("ReadConsoleInputW").Message + "]";
                    cts.Cancel();
                    break;
                }
                sb.Clear();
                for (int i = 0; i < n; i++)
                {
                    if (recs[i].EventType == Win32.WINDOW_BUFFER_SIZE_EVENT) { _ = CheckResize(); continue; }
                    if (recs[i].EventType != Win32.KEY_EVENT) continue;
                    var k = recs[i].KeyEvent;
                    // With ENABLE_VIRTUAL_TERMINAL_INPUT, keys arrive as VT characters (or win32-input-mode sequences).
                    if (k.bKeyDown == 0 || k.UnicodeChar == '\0') continue;
                    sb.Append(k.UnicodeChar, Math.Max((int)k.wRepeatCount, 1));
                }
                var (forward, detach) = filter.Process(sb.ToString());
                sb.Clear().Append(forward);
                if (detach) detached = true;
                if (sb.Length > 0)
                {
                    var chars = sb.ToString().ToCharArray();
                    var bytes = new byte[encoder.GetByteCount(chars, false)];
                    encoder.GetBytes(chars, 0, chars.Length, bytes, 0, false);
                    try { if (bytes.Length > 0) Send(bytes, WebSocketMessageType.Binary).GetAwaiter().GetResult(); }
                    catch (WebSocketException) { cts.Cancel(); break; }
                }
                if (detached) { cts.Cancel(); break; }
            }
        }) { IsBackground = true, Name = "attach-input" };
        inputThread.Start();

        try { await Task.Delay(Timeout.Infinite, cts.Token); } catch (OperationCanceledException) { }

        if (ws.State == WebSocketState.Open)
        {
            try
            {
                await sendLock.WaitAsync();
                using var closeCts = new CancellationTokenSource(2000);
                await ws.CloseOutputAsync(WebSocketCloseStatus.NormalClosure, detached ? "detach" : "bye", closeCts.Token);
            }
            catch (Exception ex) when (ex is WebSocketException or OperationCanceledException) { }
        }

        Win32.SetConsoleMode(hIn, oldIn);
        Win32.SetConsoleMode(hOut, oldOut);
        var tail = Encoding.UTF8.GetBytes("\x1b[0m\x1b[?25h\r\n" + (detached ? "[detached]" : endMessage ?? "[disconnected]") + "\r\n");
        stdout.Write(tail);
        stdout.Flush();
        Win32.SetConsoleCP(oldInCp);
        Win32.SetConsoleOutputCP(oldOutCp);
        return 0;
    }

    private static (int, int) WindowSize(IntPtr hOut)
    {
        if (Win32.GetConsoleScreenBufferInfoRaw(hOut, out var info))
            return (info.srWindow.Right - info.srWindow.Left + 1, info.srWindow.Bottom - info.srWindow.Top + 1);
        return (120, 30);
    }
}
