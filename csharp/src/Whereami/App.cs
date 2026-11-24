namespace Whereami;

/// What whereami prints and which exit code it returns.
public sealed record Outcome(string Stdout, string Stderr, int ExitCode);

/// What whereami does, given a parsed request and an OS backend.
/// Returns text instead of printing, so it's testable with a fake platform.
public static class App
{
    public static readonly TimeSpan Timeout = TimeSpan.FromSeconds(10);

    public static async Task<Outcome> RunAsync(Request req, IPlatform platform, TimeSpan timeout)
    {
        // The token asks the platform to stop; WaitAsync stops *us* waiting
        // even if the platform ignores the token.
        using var cts = new CancellationTokenSource(timeout);
        var ct = cts.Token;

        Location loc;
        switch (req.Mode)
        {
            case Mode.Lookup(var query):
                return await LookupAsync(req, platform, query, timeout, ct);
            case Mode.Mock(var mock):
                return Success(req, mock, null, showAccuracy: true);
            case Mode.At(var at):
                loc = at;
                break;
            default:
                try
                {
                    loc = await platform.GetLocationAsync(ct).WaitAsync(timeout);
                }
                catch (Exception e) when (ErrorOf(e) is { } error)
                {
                    return Failure(req, error.JsonKey(), LocationMessage(error));
                }
                break;
        }

        var addr = await platform.ReverseGeocodeAsync(loc.Latitude, loc.Longitude, ct).WaitAsync(timeout);
        var isAt = req.Mode is Mode.At;
        var outcome = Success(req, loc, addr, showAccuracy: !isAt);
        // With --at the address is the whole point, so say so when there isn't one.
        return isAt && addr is null
            ? outcome with { Stderr = "Warning: no address found for these coordinates.\n" }
            : outcome;
    }

    private static async Task<Outcome> LookupAsync(
        Request req, IPlatform platform, string query, TimeSpan timeout, CancellationToken ct)
    {
        GeocodeResult? found;
        try
        {
            found = await platform.GeocodeAsync(query, ct).WaitAsync(timeout);
        }
        catch (LocationException e) when (e.Error == LocationError.PlatformUnsupported)
        {
            return Failure(req, "platform_unsupported", "Error: address lookup is not supported on this platform.\n");
        }
        catch (Exception e) when (ErrorOf(e) is { } error)
        {
            return Failure(req, error.JsonKey(), LocationMessage(error));
        }

        return found is null
            ? Failure(req, "address_not_found",
                $"Error: couldn't find a location for \"{query}\".\nCheck the address and your network connection.\n")
            : Success(req, found.Location, found.Address, showAccuracy: false);
    }

    /// Which whereami error an exception means, or null to let it propagate.
    private static LocationError? ErrorOf(Exception e) => e switch
    {
        LocationException le => le.Error,
        TimeoutException or OperationCanceledException => LocationError.Timeout,
        _ => null,
    };

    private static string LocationMessage(LocationError e) => e switch
    {
        LocationError.PermissionDenied =>
            "Error: location permission denied.\nGrant location access in your system's privacy/location settings.\n",
        LocationError.LocationUnavailable => "Error: location unavailable.\nMake sure location services are enabled.\n",
        LocationError.Timeout => "Error: location request timed out.\n",
        LocationError.PlatformUnsupported => "Error: location is not supported on this platform yet.\n",
        _ => $"Error: unexpected error ({e})\n",
    };

    private static Outcome Success(Request req, Location loc, Address? addr, bool showAccuracy) =>
        new(req.Json ? Output.Json(loc, addr) : Output.Human(loc, addr, showAccuracy), "", 0);

    private static Outcome Failure(Request req, string key, string message) =>
        new(req.Json ? Output.JsonError(key) : "", message, 1);
}
