namespace Whereami;

/// Stand-in for an OS whose backend isn't written yet.
public sealed class UnsupportedPlatform : IPlatform
{
    public Task<Location> GetLocationAsync(CancellationToken ct) =>
        throw new LocationException(LocationError.PlatformUnsupported);

    public Task<Address?> ReverseGeocodeAsync(double lat, double lon, CancellationToken ct) =>
        Task.FromResult<Address?>(null);

    public Task<GeocodeResult?> GeocodeAsync(string query, CancellationToken ct) =>
        throw new LocationException(LocationError.PlatformUnsupported);
}
