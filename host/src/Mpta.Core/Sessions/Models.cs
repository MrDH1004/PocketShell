namespace Mpta.Core.Sessions;

public sealed class ClientDto
{
    public string Id { get; set; } = "";
    public string Type { get; set; } = "";
    public int Cols { get; set; }
    public int Rows { get; set; }
}

/// <summary>Session JSON contract (DESIGN §3). Also the on-disk meta.json format.</summary>
public sealed class SessionDto
{
    public string Id { get; set; } = "";
    public string Kind { get; set; } = "host";
    public string Title { get; set; } = "";
    public string Shell { get; set; } = "";
    public string Cwd { get; set; } = "";
    public string State { get; set; } = "running";
    public string CreatedAt { get; set; } = "";
    public string LastActivity { get; set; } = "";
    public int Cols { get; set; }
    public int Rows { get; set; }
    public List<ClientDto> Clients { get; set; } = new();
    public int? Pid { get; set; }
    public string? ResumedFrom { get; set; }
    /// <summary>External only: AI CLI running in the console ("claude" | "gpt"), else null.</summary>
    public string? Agent { get; set; }

    public SessionDto Clone() => new()
    {
        Id = Id, Kind = Kind, Title = Title, Shell = Shell, Cwd = Cwd, State = State,
        CreatedAt = CreatedAt, LastActivity = LastActivity, Cols = Cols, Rows = Rows,
        Clients = Clients.Select(c => new ClientDto { Id = c.Id, Type = c.Type, Cols = c.Cols, Rows = c.Rows }).ToList(),
        Pid = Pid, ResumedFrom = ResumedFrom, Agent = Agent,
    };
}

public sealed class CreateSessionRequest
{
    public string? Shell { get; set; }
    public string? Cwd { get; set; }
    public string? Title { get; set; }
    public int? Cols { get; set; }
    public int? Rows { get; set; }
}

public static class ControlMessages
{
    public static string Size(int cols, int rows) => $"{{\"type\":\"size\",\"cols\":{cols},\"rows\":{rows}}}";
    public static string Exit(int code) => $"{{\"type\":\"exit\",\"code\":{code}}}";
    /// <summary>External consoles: lines that scrolled off the window before this client attached (oldest first, VtFrame row bodies).</summary>
    public static string History(IReadOnlyList<string> lines) =>
        System.Text.Json.JsonSerializer.Serialize(new { type = "history", lines });
    public const string ReplayEnd = "{\"type\":\"replay-end\"}";
    public static string Hello(SessionDto s) =>
        "{\"type\":\"hello\",\"session\":" + System.Text.Json.JsonSerializer.Serialize(s, Util.Json.Options) + "}";
}
