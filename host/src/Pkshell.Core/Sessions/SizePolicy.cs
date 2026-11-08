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
}
