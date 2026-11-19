using Pkshell.Core.Native;

namespace Pkshell.Tests;

public class SystemStatsTests
{
    [Fact]
    public void GpuIsTheBusiestEngineSummedOverProcesses()
    {
        var items = new[]
        {
            ("pid_10_luid_0x00000000_0x0000D1E3_phys_0_eng_0_engtype_3D", 20.0),
            ("pid_20_luid_0x00000000_0x0000D1E3_phys_0_eng_0_engtype_3D", 15.0),
            ("pid_10_luid_0x00000000_0x0000D1E3_phys_0_eng_3_engtype_VideoDecode", 30.0),
        };
        Assert.Equal(35.0, SystemStats.Busiest(items), 3);
    }

    [Fact]
    public void GpuIsCappedAndZeroWithoutEngines()
    {
        Assert.Equal(0, SystemStats.Busiest(Array.Empty<(string, double)>()));
        Assert.Equal(100, SystemStats.Busiest(new[] { ("pid_1_luid_0x0_0x1_phys_0_eng_0_engtype_3D", 80.0), ("pid_2_luid_0x0_0x1_phys_0_eng_0_engtype_3D", 70.0) }));
    }

    [Fact]
    public async Task ReadsThisPc()
    {
        using var stats = new SystemStats();
        var s = await stats.ReadAsync();
        Assert.True(s.MemTotal > 0 && s.MemUsed <= s.MemTotal);
        Assert.NotNull(s.Cpu);
        Assert.InRange(s.Cpu!.Value, 0, 100);
    }
}
