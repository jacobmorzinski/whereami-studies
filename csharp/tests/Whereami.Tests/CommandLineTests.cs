namespace Whereami.Tests;

public class CommandLineTests
{
    private static Request Ok(params string[] a) => Assert.IsType<Parsed.Ok>(CommandLine.Parse(a)).Request;

    private static void Usage(params string[] a) => Assert.IsType<Parsed.UsageError>(CommandLine.Parse(a));

    [Fact]
    public void CoordTrims()
    {
        Assert.True(CommandLine.TryParseCoord(" 51.5 , -0.1276", out var lat, out var lon));
        Assert.Equal((51.5, -0.1276), (lat, lon));
    }

    [Theory]
    [InlineData("40.7")]
    [InlineData("abc,def")]
    [InlineData("1,2,3")]
    [InlineData("NaN,0")]
    [InlineData("0,Infinity")]
    [InlineData("0,∞")]
    [InlineData("0x10,0")]
    public void CoordRejects(string s) => Assert.False(CommandLine.TryParseCoord(s, out _, out _));

    [Fact]
    public void MockNegative() => Assert.Equal(new Mode.Mock(new(-33.8, 151.2, 0)), Ok("--mock=-33.8,151.2").Mode);

    [Fact]
    public void AtBoundaryOk() => Assert.IsType<Mode.At>(Ok("--at=90,-180").Mode);

    [Fact]
    public void AddressJoined() => Assert.Equal(new Mode.Lookup("350 5th Ave"), Ok("350", "5th", "Ave").Mode);

    [Fact]
    public void AtSignIsAnAddress() => Assert.Equal(new Mode.Lookup("@home"), Ok("@home").Mode);

    [Fact]
    public void JsonAnyPosition() => Assert.True(Ok("--json", "--mock=1,2").Json);

    [Fact]
    public void NothingMeansCurrent() => Assert.IsType<Mode.Current>(Ok().Mode);

    [Fact]
    public void UnknownFlagIsUsageError() => Usage("--bogus");

    [Theory]
    [InlineData("--at=91,0")]
    [InlineData("--at=0,-180.5")]
    [InlineData("   ")]
    [InlineData("--mock=x")]
    public void UsageErrors(string a) => Usage(a);

    [Fact]
    public void Conflicts()
    {
        Usage("--at=1,2", "--mock=1,2");
        Usage("--mock=1,2", "Paris");
        Usage("completions", "bash");
    }

    [Fact]
    public void VersionLine() =>
        Assert.Equal($"whereami 0.4.0 ({Platforms.OsName})\n", Assert.IsType<Parsed.Info>(CommandLine.Parse(["-v"])).Text);

    [Fact]
    public void HelpIsInfo() => Assert.Contains("--mock", Assert.IsType<Parsed.Info>(CommandLine.Parse(["--help"])).Text);
}
