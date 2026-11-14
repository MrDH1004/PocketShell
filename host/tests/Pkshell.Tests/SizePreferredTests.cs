using Pkshell.Core.Sessions;

namespace Pkshell.Tests;

public class SizePreferredTests
{
    private static readonly List<(string, int, int)> PhoneAndTab = [("android", 63, 48), ("cli", 48, 70)];

    [Fact]
    public void BeforeAnyoneTypedThePhoneWins() =>
        Assert.Equal((63, 48), SizePolicy.ComputePreferred(null, PhoneAndTab, (120, 30)));

    [Fact]
    public void TheWindowThatTypedLastWins() =>
        Assert.Equal((48, 70), SizePolicy.ComputePreferred((48, 70), PhoneAndTab, (63, 48)));

    [Fact]
    public void TwoPhonesShareTheSmaller() =>
        Assert.Equal((50, 40), SizePolicy.ComputePreferred(null, [("android", 63, 48), ("android", 50, 40), ("cli", 30, 20)], (120, 30)));

    [Fact]
    public void WithoutAPhoneTheMinOverAll() =>
        Assert.Equal((48, 30), SizePolicy.ComputePreferred(null, [("cli", 48, 70), ("cli", 100, 30)], (120, 30)));

    [Fact]
    public void NobodySizedKeepsTheCurrentSize() =>
        Assert.Equal((120, 30), SizePolicy.ComputePreferred(null, [], (120, 30)));
}
