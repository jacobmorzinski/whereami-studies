#if WINDOWS
// Only compiled for the net10.0-windows10.0.19041.0 target, which brings in
// the WinRT projection: Windows.* namespaces look like ordinary .NET classes.
using System.Runtime.InteropServices;
using Windows.Devices.Geolocation;

namespace Whereami;

/// Windows backend: the WinRT Geolocator class. Compare with
/// original/whereami/src/platform/windows.zig, which declares the same COM
/// vtables, GUIDs and async polling by hand.
public sealed class WindowsPlatform : IPlatform
{
    private const int E_ACCESSDENIED = unchecked((int)0x80070005);

    /// HRESULT_FROM_WIN32(ERROR_TIMEOUT): what the timeout overload fails with.
    private const int HRESULT_TIMEOUT = unchecked((int)0x800705B4);

    public async Task<Location> GetLocationAsync(CancellationToken ct)
    {
        try
        {
            // IAsyncOperation<T> → Task<T> with AsTask; cancelling the token
            // cancels the WinRT operation.
            var access = await Geolocator.RequestAccessAsync().AsTask(ct);
            if (access != GeolocationAccessStatus.Allowed)
            {
                throw new LocationException(LocationError.PermissionDenied);
            }

            var geolocator = new Geolocator { DesiredAccuracy = PositionAccuracy.High };
            var position = await geolocator.GetGeopositionAsync(maximumAge: TimeSpan.Zero, timeout: App.Timeout).AsTask(ct);
            var c = position.Coordinate;
            return new Location(c.Latitude, c.Longitude, c.Accuracy);
        }
        catch (Exception e) when (e is not LocationException)
        {
            // Failed WinRT calls surface as .NET exceptions carrying the HRESULT.
            throw new LocationException(MapException(e));
        }
    }

    /// Windows has no usable built-in geocoder: MapLocationFinder needs a Bing
    /// Maps key and returns InvalidCredentials without one (checked 2026-11-23).
    public Task<Address?> ReverseGeocodeAsync(double lat, double lon, CancellationToken ct) =>
        Task.FromResult<Address?>(null);

    public Task<GeocodeResult?> GeocodeAsync(string query, CancellationToken ct) =>
        throw new LocationException(LocationError.PlatformUnsupported);

    /// Translate whatever a WinRT call threw into whereami's error kinds.
    internal static LocationError MapException(Exception e) => e switch
    {
        UnauthorizedAccessException => LocationError.PermissionDenied,
        OperationCanceledException or TimeoutException => LocationError.Timeout,
        { HResult: E_ACCESSDENIED } => LocationError.PermissionDenied,
        { HResult: HRESULT_TIMEOUT } => LocationError.Timeout,
        _ => LocationError.LocationUnavailable,
    };
}
#endif
