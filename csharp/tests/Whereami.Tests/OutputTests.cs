using System.Text.Json;

namespace Whereami.Tests;

public class OutputTests
{
    [Fact]
    public void HumanWithAccuracy() =>
        Assert.Equal("Location: 40.7128, -74.0060\nAccuracy: 0m\n", Output.Human(new(40.7128, -74.0060, 0), null, true));

    [Fact]
    public void HumanWithoutAccuracySkipsEmptyFields() =>
        Assert.Equal(
            "Location: 38.8977, -77.0366\nAddress: 1600 Pennsylvania Ave NW, Washington, 20500, US\n",
            Output.Human(new(38.8977, -77.0366, 5), new("1600 Pennsylvania Ave NW", "Washington", "", "20500", "US"), false));

    [Fact]
    public void JsonNullAddress()
    {
        using var d = JsonDocument.Parse(Output.Json(new(40.7128, -74.006, 0), null));
        Assert.Equal(40.7128, d.RootElement.GetProperty("latitude").GetDouble());
        Assert.Equal(JsonValueKind.Null, d.RootElement.GetProperty("address").ValueKind);
        Assert.Equal(new[] { "latitude", "longitude", "accuracy", "address" }, d.RootElement.EnumerateObject().Select(p => p.Name));
    }

    [Fact]
    public void JsonEscapesAddress()
    {
        var json = Output.Json(new(1, 2, 3), new("5 \"Q\" St\\", "Montréal", "", "H2X", "CA"));
        using var d = JsonDocument.Parse(json);
        var a = d.RootElement.GetProperty("address");
        Assert.Equal("5 \"Q\" St\\", a.GetProperty("street").GetString());
        Assert.Equal("H2X", a.GetProperty("postal_code").GetString());
        Assert.Contains("Montréal", json);
    }

    [Fact]
    public void JsonErrorShape() => Assert.Equal("{\"error\":\"timeout\"}\n", Output.JsonError("timeout"));
}
