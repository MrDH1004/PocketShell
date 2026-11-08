using System.Runtime.InteropServices;
using Pkshell.Core.Util;

namespace Pkshell.Server;

/// <summary>
/// Puts an image sent by the phone on the PC clipboard so an Alt+V in Claude Code (sent next by the phone over the
/// session input) attaches it as [Image #N]. Uses WinForms' clipboard on a dedicated STA thread (OLE clipboard calls
/// require STA); the data is flushed (copy: true) so it stays on the clipboard after the bitmap is disposed.
/// </summary>
internal static class ClipboardImage
{
    public const int MaxBytes = 20 << 20;
    private static readonly TimeSpan Timeout = TimeSpan.FromSeconds(15);

    /// <summary>Returns null on success, else an error message.</summary>
    public static string? Set(byte[] image)
    {
        string? error = null;
        var thread = new Thread(() =>
        {
            try
            {
                using var ms = new MemoryStream(image, writable: false);
                using var bitmap = new System.Drawing.Bitmap(ms);
                var data = new System.Windows.Forms.DataObject();
                data.SetImage(bitmap);
                System.Windows.Forms.Clipboard.SetDataObject(data, copy: true, retryTimes: 10, retryDelay: 100);
            }
            catch (ArgumentException) { error = "not a supported image (PNG/JPEG/BMP/GIF)"; }
            catch (ExternalException ex) { error = $"clipboard is busy: {ex.Message}"; }
            catch (ThreadStateException ex) { error = $"clipboard thread: {ex.Message}"; }
        }) { IsBackground = true, Name = "clipboard-image" };
        thread.SetApartmentState(ApartmentState.STA);
        thread.Start();
        if (!thread.Join(Timeout)) return $"clipboard did not respond within {Timeout.TotalSeconds:0}s";
        if (error != null) Log.Warn("clipboard: " + error);
        return error;
    }

    /// <summary>Reads the request body up to <see cref="MaxBytes"/>; null if it is larger.</summary>
    public static async Task<byte[]?> ReadBodyAsync(Stream body, CancellationToken ct)
    {
        using var ms = new MemoryStream();
        var buf = new byte[81920];
        int n;
        while ((n = await body.ReadAsync(buf, ct)) > 0)
        {
            if (ms.Length + n > MaxBytes) return null;
            ms.Write(buf, 0, n);
        }
        return ms.ToArray();
    }
}
