namespace Whereami;

/// The OS boundary. Failures throw <see cref="LocationException"/>.
public interface IPlatform
{
    /// Ask the OS for the current position. Should stop when <paramref name="ct"/> is cancelled.
    Task<Location> GetLocationAsync(CancellationToken ct);

    /// Coordinates → address, or null if there's no geocoder or no match.
    Task<Address?> ReverseGeocodeAsync(double lat, double lon, CancellationToken ct);

    /// Address → coordinates; null when nothing matches.
    /// Throws PlatformUnsupported where the OS has no geocoder.
    Task<GeocodeResult?> GeocodeAsync(string query, CancellationToken ct);
}

public static class Platforms
{
    /// "windows", "macos" or "linux" — decided at run time.
    public static string OsName =>
        OperatingSystem.IsWindows() ? "windows" : OperatingSystem.IsMacOS() ? "macos" : "linux";

    /// The implementation for the OS we're running on. Two layers:
    /// #if WINDOWS / #if !WINDOWS — is that OS's code compiled into this build at all?
    /// (Linux's D-Bus code is only in the portable build.)
    /// OperatingSystem.IsWindows() / IsLinux() — are we actually running there?
    public static IPlatform Native()
    {
#if WINDOWS
        if (OperatingSystem.IsWindows())
        {
            return new WindowsPlatform();
        }
#else
        if (OperatingSystem.IsLinux())
        {
            return new LinuxPlatform(App.Timeout);
        }
#endif
        return new UnsupportedPlatform();
    }
}
