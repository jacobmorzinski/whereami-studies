using System.Globalization;
using System.Text.Encodings.Web;
using System.Text.Json;

namespace Whereami;

/// Turning results into the text whereami prints.
public static class Output
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        // Latitude → "latitude", PostalCode → "postal_code".
        PropertyNamingPolicy = JsonNamingPolicy.SnakeCaseLower,
        // Keep "é" readable instead of "é"; still valid JSON.
        Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
    };

    private sealed record JsonOutput(double Latitude, double Longitude, double Accuracy, Address? Address);

    /// Human-readable output. showAccuracy is false for looked-up places,
    /// which have no position fix. Always invariant culture: "40.7128", never "40,7128".
    public static string Human(Location loc, Address? addr, bool showAccuracy)
    {
        var inv = CultureInfo.InvariantCulture;
        var s = string.Create(inv, $"Location: {loc.Latitude:F4}, {loc.Longitude:F4}\n");
        if (showAccuracy)
        {
            s += string.Create(inv, $"Accuracy: {loc.Accuracy:F0}m\n");
        }
        if (addr is not null)
        {
            string[] fields = [addr.Street, addr.City, addr.State, addr.PostalCode, addr.Country];
            s += $"Address: {string.Join(", ", fields.Where(f => f.Length > 0))}\n";
        }
        return s;
    }

    /// One line of JSON; "address" is null when there is none.
    public static string Json(Location loc, Address? addr) =>
        JsonSerializer.Serialize(new JsonOutput(loc.Latitude, loc.Longitude, loc.Accuracy, addr), JsonOptions) + "\n";

    /// {"error":"key"} for runtime errors in --json mode.
    public static string JsonError(string key) =>
        JsonSerializer.Serialize(new { error = key }, JsonOptions) + "\n";
}
