using System.Buffers.Binary;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using Microsoft.Win32.SafeHandles;
using Mpta.Core.Native;
using Mpta.Core.Util;

namespace Mpta.Core.External;

public sealed class ConsoleInfo
{
    public int Pid { get; set; }
    public string Title { get; set; } = "";
    public int Cols { get; set; }
    public int Rows { get; set; }
}

/// <summary>
/// Code that runs inside the helper child process (`mpta console-info` / `mpta console-reader`),
/// because attaching to another console detaches the caller from its own.
/// </summary>
public static class ConsoleHelpers
{
    /// <summary>`mpta console-info pid...` → stdout JSON [{pid,title,cols,rows}] for each pid that has an attachable console.</summary>
    public static int RunConsoleInfo(IEnumerable<int> pids)
    {
        using var stdout = Console.OpenStandardOutput(); // capture the pipe before detaching from our console
        var results = new List<ConsoleInfo>();
        foreach (var pid in pids)
        {
            Win32.FreeConsole();
            if (!Win32.AttachConsole((uint)pid))
            {
                Console.Error.WriteLine(Win32.LastError($"AttachConsole({pid})").Message);
                continue;
            }
            try
            {
                using var conout = OpenConsoleHandle("CONOUT$");
                if (conout.IsInvalid) { Console.Error.WriteLine(Win32.LastError("CreateFile(CONOUT$)").Message); continue; }
                if (!Win32.GetConsoleScreenBufferInfo(conout, out var info))
                {
                    Console.Error.WriteLine(Win32.LastError("GetConsoleScreenBufferInfo").Message);
                    continue;
                }
                var sb = new StringBuilder(1024);
                uint n = Win32.GetConsoleTitle(sb, (uint)sb.Capacity);
                results.Add(new ConsoleInfo
                {
                    Pid = pid,
                    Title = n > 0 ? sb.ToString() : "",
                    Cols = info.srWindow.Right - info.srWindow.Left + 1,
                    Rows = info.srWindow.Bottom - info.srWindow.Top + 1,
                });
            }
            finally { Win32.FreeConsole(); }
        }
        var bytes = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(results, Json.Options));
        stdout.Write(bytes);
        stdout.Flush();
        return 0;
    }

    private static SafeFileHandle OpenConsoleHandle(string name) =>
        Win32.CreateFile(name, Win32.GENERIC_READ | Win32.GENERIC_WRITE, Win32.FILE_SHARE_READ | Win32.FILE_SHARE_WRITE, IntPtr.Zero, Win32.OPEN_EXISTING, 0, IntPtr.Zero);

    /// <summary>
    /// `mpta console-reader pid`: attaches to the target console, polls the visible window every ~200ms and writes
    /// length-prefixed <see cref="ReaderFrame"/> payloads (4-byte LE length + cols/rows header + VT frame) to stdout
    /// when the screen changes. Reads length-prefixed
    /// input from stdin and injects it with WriteConsoleInputW. Exits when the console goes away or stdin closes.
    /// </summary>
    public static int RunConsoleReader(int pid)
    {
        var stdout = Console.OpenStandardOutput();
        var stdin = Console.OpenStandardInput();
        Win32.FreeConsole();
        if (!Win32.AttachConsole((uint)pid))
        {
            Console.Error.WriteLine(Win32.LastError($"AttachConsole({pid})").Message);
            return 2;
        }
        using var conout = OpenConsoleHandle("CONOUT$");
        using var conin = OpenConsoleHandle("CONIN$");
        if (conout.IsInvalid || conin.IsInvalid)
        {
            Console.Error.WriteLine(Win32.LastError("CreateFile(CONOUT$/CONIN$)").Message);
            return 3;
        }

        var cts = new CancellationTokenSource();
        var writeLock = new object();
        var inputThread = new Thread(() =>
        {
            try { InputLoop(stdin, conin); }
            catch (Exception ex) { Console.Error.WriteLine($"input loop failed: {ex.Message}"); }
            cts.Cancel();
        }) { IsBackground = true, Name = "console-reader-input" };
        inputThread.Start();

        byte[]? last = null;
        int exit = 0;
        while (!cts.IsCancellationRequested)
        {
            byte[]? frame;
            try { frame = ReadFrame(conout); }
            catch (Exception ex) { Console.Error.WriteLine($"read failed: {ex.Message}"); exit = 4; break; }
            if (frame == null) { exit = 4; break; } // console gone
            if (last == null || !frame.AsSpan().SequenceEqual(last))
            {
                last = frame;
                var len = new byte[4];
                BinaryPrimitives.WriteInt32LittleEndian(len, frame.Length);
                try
                {
                    lock (writeLock)
                    {
                        stdout.Write(len);
                        stdout.Write(frame);
                        stdout.Flush();
                    }
                }
                catch (IOException) { break; } // server closed the pipe
            }
            if (cts.Token.WaitHandle.WaitOne(200)) break;
        }
        Win32.FreeConsole();
        return exit;
    }

    private static byte[]? ReadFrame(SafeFileHandle conout)
    {
        if (!Win32.GetConsoleScreenBufferInfo(conout, out var info))
        {
            Console.Error.WriteLine(Win32.LastError("GetConsoleScreenBufferInfo").Message);
            return null;
        }
        int width = info.srWindow.Right - info.srWindow.Left + 1;
        int height = info.srWindow.Bottom - info.srWindow.Top + 1;
        if (width <= 0 || height <= 0) return null;
        var chars = new char[width * height];
        var attrs = new ushort[width * height];
        var row = new Win32.CHAR_INFO[width];
        for (int y = 0; y < height; y++)
        {
            // One row per call keeps each read far below the console's internal buffer limit.
            var region = new Win32.SMALL_RECT { Left = info.srWindow.Left, Right = info.srWindow.Right, Top = (short)(info.srWindow.Top + y), Bottom = (short)(info.srWindow.Top + y) };
            if (!Win32.ReadConsoleOutput(conout, row, new Win32.COORD((short)width, 1), new Win32.COORD(0, 0), ref region))
            {
                Console.Error.WriteLine(Win32.LastError("ReadConsoleOutputW").Message);
                return null;
            }
            for (int x = 0; x < width; x++)
            {
                chars[y * width + x] = row[x].UnicodeChar;
                attrs[y * width + x] = row[x].Attributes;
            }
        }
        int cx = info.dwCursorPosition.X - info.srWindow.Left;
        int cy = info.dwCursorPosition.Y - info.srWindow.Top;
        VtFrame.GrayPromptPlaceholder(chars, attrs, width, height, cx, cy);
        return ReaderFrame.Encode(width, height, VtFrame.Build(chars, attrs, width, height, cx, cy));
    }

    private static void InputLoop(Stream stdin, SafeFileHandle conin)
    {
        var translator = new VtInputTranslator();
        var lenBuf = new byte[4];
        while (true)
        {
            if (!ReadExact(stdin, lenBuf)) return;
            int len = BinaryPrimitives.ReadInt32LittleEndian(lenBuf);
            if (len < 0 || len > 1 << 20) throw new InvalidDataException($"bad input frame length {len}");
            var data = new byte[len];
            if (!ReadExact(stdin, data)) return;
            var keys = translator.Translate(data).Select(ResolvePrintable).ToList();
            if (keys.Count == 0) continue;
            var recs = VtInputTranslator.ToInputRecords(keys, vk => (ushort)Win32.MapVirtualKey(vk, Win32.MAPVK_VK_TO_VSC));
            if (!Win32.WriteConsoleInput(conin, recs, (uint)recs.Length, out var written))
                Console.Error.WriteLine(Win32.LastError("WriteConsoleInputW").Message);
            else if (written != recs.Length)
                Console.Error.WriteLine($"WriteConsoleInputW wrote {written}/{recs.Length} records");
        }
    }

    [DllImport("user32.dll", CharSet = CharSet.Unicode, EntryPoint = "VkKeyScanW")]
    private static extern short VkKeyScan(char ch);

    /// <summary>Gives printable ASCII keys a real VK + shift state so line editors that key off VK codes behave.</summary>
    private static KeyPress ResolvePrintable(KeyPress k)
    {
        if (k.VirtualKey != 0 || k.UnicodeChar < ' ' || k.UnicodeChar > '~') return k;
        short r = VkKeyScan(k.UnicodeChar);
        if (r == -1) return k;
        byte vk = (byte)(r & 0xFF);
        byte shift = (byte)((r >> 8) & 0xFF);
        uint state = 0;
        if ((shift & 1) != 0) state |= Win32.SHIFT_PRESSED;
        if ((shift & 6) != 0) return k; // needs Ctrl/Alt (AltGr layouts): send as plain unicode
        return new KeyPress(vk, k.UnicodeChar, state);
    }

    private static bool ReadExact(Stream s, byte[] buf)
    {
        int off = 0;
        while (off < buf.Length)
        {
            int n = s.Read(buf, off, buf.Length - off);
            if (n <= 0) return false;
            off += n;
        }
        return true;
    }
}
