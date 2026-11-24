using System.Diagnostics;

namespace Whereami.Tests;

public class AppTests
{
    private sealed class Fake : IPlatform
    {
        public Func<CancellationToken, Task<Location>> Loc { get; init; } =
            _ => Task.FromResult(new Location(47.6062, -122.3321, 12.4));

        public Address? Addr { get; init; }

        public Func<Task<GeocodeResult?>> Geo { get; init; } = () => Task.FromResult<GeocodeResult?>(null);

        public Task<Location> GetLocationAsync(CancellationToken ct) => Loc(ct);

        public Task<Address?> ReverseGeocodeAsync(double lat, double lon, CancellationToken ct) => Task.FromResult(Addr);

        public Task<GeocodeResult?> GeocodeAsync(string query, CancellationToken ct) => Geo();
    }

    private static readonly Address Hoyt = new("45 Hoyt St", "New York", "NY", "11201", "US");

    private static Fake Failing(LocationError e) => new() { Loc = _ => throw new LocationException(e) };

    private static Task<Outcome> Run(Mode mode, IPlatform p, bool json = false, int timeoutMs = 10_000) =>
        App.RunAsync(new Request(mode, json), p, TimeSpan.FromMilliseconds(timeoutMs));

    [Fact]
    public async Task CurrentOkHuman()
    {
        var o = await Run(new Mode.Current(), new Fake());
        Assert.Equal("Location: 47.6062, -122.3321\nAccuracy: 12m\n", o.Stdout);
        Assert.Equal(0, o.ExitCode);
    }

    [Fact]
    public async Task PermissionDeniedJson()
    {
        var o = await Run(new Mode.Current(), Failing(LocationError.PermissionDenied), json: true);
        Assert.Equal("{\"error\":\"permission_denied\"}\n", o.Stdout);
        Assert.StartsWith("Error: location permission denied.", o.Stderr);
        Assert.Equal(1, o.ExitCode);
    }

    [Fact]
    public async Task TimeoutHuman()
    {
        var o = await Run(new Mode.Current(), Failing(LocationError.Timeout));
        Assert.Equal("", o.Stdout);
        Assert.Equal("Error: location request timed out.\n", o.Stderr);
        Assert.Equal(1, o.ExitCode);
    }

    [Fact]
    public async Task TimeoutHoldsWhenPlatformIgnoresCancellation()
    {
        var never = new Fake { Loc = _ => new TaskCompletionSource<Location>().Task };
        var watch = Stopwatch.StartNew();
        var o = await Run(new Mode.Current(), never, timeoutMs: 100);
        Assert.True(watch.Elapsed < TimeSpan.FromSeconds(2), $"took {watch.Elapsed}");
        Assert.Equal("Error: location request timed out.\n", o.Stderr);
        Assert.Equal(1, o.ExitCode);
    }

    [Fact]
    public async Task CancellationIsTimeout()
    {
        var waits = new Fake
        {
            Loc = async ct =>
            {
                await Task.Delay(Timeout.Infinite, ct);
                return default;
            },
        };
        var o = await Run(new Mode.Current(), waits, timeoutMs: 100);
        Assert.Equal("Error: location request timed out.\n", o.Stderr);
        Assert.Equal(1, o.ExitCode);
    }

    [Fact]
    public async Task UnsupportedCurrent()
    {
        var o = await Run(new Mode.Current(), Failing(LocationError.PlatformUnsupported));
        Assert.Equal("Error: location is not supported on this platform yet.\n", o.Stderr);
        Assert.Equal(1, o.ExitCode);
    }

    [Fact]
    public async Task MockSkipsReverseGeocode()
    {
        var o = await Run(new Mode.Mock(new(1, 2, 0)), new Fake { Addr = Hoyt });
        Assert.DoesNotContain("Address:", o.Stdout);
        Assert.Equal(0, o.ExitCode);
    }

    [Fact]
    public async Task AtNoAddressWarns()
    {
        var o = await Run(new Mode.At(new(1, 2, 0)), new Fake());
        Assert.Equal("Location: 1.0000, 2.0000\n", o.Stdout);
        Assert.Equal("Warning: no address found for these coordinates.\n", o.Stderr);
        Assert.Equal(0, o.ExitCode);
    }

    [Fact]
    public async Task AddressNotFoundJson()
    {
        var o = await Run(new Mode.Lookup("Nowhere"), new Fake(), json: true);
        Assert.Equal("{\"error\":\"address_not_found\"}\n", o.Stdout);
        Assert.Contains("couldn't find a location for \"Nowhere\"", o.Stderr);
        Assert.Equal(1, o.ExitCode);
    }

    [Fact]
    public async Task AddressFoundNoAccuracy()
    {
        var found = new Fake { Geo = () => Task.FromResult<GeocodeResult?>(new(new(40.6892, -73.9857, 0), Hoyt)) };
        var o = await Run(new Mode.Lookup("45 Hoyt St"), found);
        Assert.Contains("Address: 45 Hoyt St, New York, NY, 11201, US\n", o.Stdout);
        Assert.DoesNotContain("Accuracy:", o.Stdout);
        Assert.Equal(0, o.ExitCode);
    }

    [Fact]
    public async Task AddressUnsupported()
    {
        var none = new Fake { Geo = () => throw new LocationException(LocationError.PlatformUnsupported) };
        var o = await Run(new Mode.Lookup("Paris"), none);
        Assert.Equal("Error: address lookup is not supported on this platform.\n", o.Stderr);
        Assert.Equal(1, o.ExitCode);
    }
}
