namespace Pkshell.Core.Sessions;

public static class SizePolicy
{
    public const int MinDim = 1;
    public const int MaxDim = 1000;

    /// <summary>Clamps a reported terminal dimension (cols or rows) to [<see cref="MinDim"/>, <see cref="MaxDim"/>].</summary>
    public static int Clamp(int v) => Math.Clamp(v, MinDim, MaxDim);

    /// <summary>
    /// PTY size = min(cols), min(rows) over clients that reported a size (cols>0 &amp;&amp; rows>0).
    /// With no sizing clients the current size is kept.
    /// </summary>
    public static (int Cols, int Rows) Compute(IEnumerable<(int Cols, int Rows)> clients, (int Cols, int Rows) current)
    {
        int minCols = int.MaxValue, minRows = int.MaxValue;
        bool any = false;
        foreach (var (c, r) in clients)
        {
            if (c <= 0 || r <= 0) continue;
            any = true;
            if (c < minCols) minCols = c;
            if (r < minRows) minRows = r;
        }
        return any ? (minCols, minRows) : current;
    }

    /// <summary>Client type of the phone app (`?client=android`).</summary>
    public const string PhoneClient = "android";

    /// <summary>
    /// Which window the PTY follows when several are attached (phone + the PC's Windows Terminal tab): the one that
    /// typed last (<paramref name="active"/>); before anyone typed, the phones (min over them); with no phone, min
    /// over all. Taking the min of a 63-column phone and a 48-column PC tab left a quarter of the phone empty; now
    /// the window in use gets its full width and the other one shows the same screen until it is typed in.
    /// </summary>
    public static (int Cols, int Rows) ComputePreferred((int Cols, int Rows)? active, IReadOnlyList<(string Type, int Cols, int Rows)> clients, (int Cols, int Rows) current)
    {
        if (active is { Cols: > 0, Rows: > 0 } a) return a;
        var phones = clients.Where(c => c.Type == PhoneClient && c.Cols > 0 && c.Rows > 0).Select(c => (c.Cols, c.Rows)).ToList();
        return phones.Count > 0 ? Compute(phones, current) : Compute(clients.Select(c => (c.Cols, c.Rows)), current);
    }
}
