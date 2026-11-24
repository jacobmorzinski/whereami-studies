using System.Globalization;

namespace Whereami.Tests;

/// Runs with a culture whose decimal separator is a comma.
public sealed class CultureTests : IDisposable
{
    private readonly CultureInfo _saved = CultureInfo.CurrentCulture;

    public CultureTests() => CultureInfo.CurrentCulture = new CultureInfo("de-DE");

    public void Dispose() => CultureInfo.CurrentCulture = _saved;

    [Fact]
    public void HumanOutputIsInvariant() =>
        Assert.StartsWith("Location: 40.7128, -74.0060", Output.Human(new(40.7128, -74.0060, 0), null, true));

    [Fact]
    public void ParsingIsInvariant() =>
        Assert.Equal(new Mode.Mock(new(40.7128, -74.006, 0)),
            Assert.IsType<Parsed.Ok>(CommandLine.Parse(["--mock=40.7128,-74.006"])).Request.Mode);
}
