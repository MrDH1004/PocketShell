namespace Pkshell.Core.Sessions;

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
    /// <summary>AI CLI ("claude" | "gpt"): running in an external console, or running in a host session when the host
    /// ended it (history; resume continues it). Else null.</summary>
    public string? Agent { get; set; }
    /// <summary>Live AI CLI sessions only: "working" | "waiting" | "done" (<see cref="AgentStates"/>), null when unknown.</summary>
    [System.Text.Json.Serialization.JsonIgnore(Condition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull)]
    public string? AgentState { get; set; }
    /// <summary>When <see cref="AgentState"/> began (ISO UTC).</summary>
    [System.Text.Json.Serialization.JsonIgnore(Condition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull)]
    public string? AgentStateSince { get; set; }
    /// <summary>The question shown while <see cref="AgentState"/> is "waiting".</summary>
    [System.Text.Json.Serialization.JsonIgnore(Condition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull)]
    public PromptDto? Prompt { get; set; }
    /// <summary>What the agent wrote last (for the "done" event); not part of the session JSON.</summary>
    [System.Text.Json.Serialization.JsonIgnore]
    public string? AgentPreview { get; set; }

    public SessionDto Clone() => new()
    {
        Id = Id, Kind = Kind, Title = Title, Shell = Shell, Cwd = Cwd, State = State,
        CreatedAt = CreatedAt, LastActivity = LastActivity, Cols = Cols, Rows = Rows,
        Clients = Clients.Select(c => new ClientDto { Id = c.Id, Type = c.Type, Cols = c.Cols, Rows = c.Rows }).ToList(),
        Pid = Pid, ResumedFrom = ResumedFrom, Agent = Agent,
        AgentState = AgentState, AgentStateSince = AgentStateSince, Prompt = Prompt,
    };

    /// <summary>Copies a tracker's state into this snapshot.</summary>
    public SessionDto WithAgentState(ActivityTracker t)
    {
        AgentState = t.State;
        AgentStateSince = t.State == null ? null : Util.Ids.IsoUtc(t.StateSince);
        Prompt = t.Prompt;
        AgentPreview = t.Preview;
        return this;
    }
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
