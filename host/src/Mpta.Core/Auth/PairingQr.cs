using System.Text.Json;
using System.Text.Json.Serialization;
using QRCoder;

namespace Mpta.Core.Auth;

public sealed class QrPayload
{
    [JsonPropertyName("v")] public int V { get; set; } = 1;
    [JsonPropertyName("name")] public string Name { get; set; } = "";
    [JsonPropertyName("hosts")] public List<string> Hosts { get; set; } = new();
    [JsonPropertyName("port")] public int Port { get; set; }
    [JsonPropertyName("fp")] public string Fp { get; set; } = "";
    [JsonPropertyName("code")] public string Code { get; set; } = "";

    public string ToJson() => JsonSerializer.Serialize(this);
}

public static class PairingQr
{
    public static (string Ascii, byte[] Png) Render(string payload)
    {
        using var gen = new QRCodeGenerator();
        using var data = gen.CreateQrCode(payload, QRCodeGenerator.ECCLevel.M);
        using var ascii = new AsciiQRCode(data);
        // Two characters per module keeps the aspect ratio roughly square in a console.
        var text = ascii.GetGraphic(1, "██", "  ", true, "\n");
        using var png = new PngByteQRCode(data);
        var bytes = png.GetGraphic(8);
        return (text, bytes);
    }
}
