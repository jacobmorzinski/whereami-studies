namespace Whereami

open System
open System.Threading
open System.Threading.Tasks

/// The OS boundary, as a record of functions rather than an interface.
/// An implementation is a record value, and a test fake is a copy of one
/// with a field replaced: { Platform.unsupported with GetLocation = … }.
/// Expected failures are returned as Result values, not thrown.
type Platform =
    { /// Ask the OS for the current position. Should stop when the token is cancelled.
      GetLocation: CancellationToken -> Task<Result<Location, LocationError>>
      /// Coordinates → address; None if there's no geocoder or no match.
      ReverseGeocode: float -> float -> CancellationToken -> Task<Address option>
      /// Address → coordinates; Ok None when nothing matches.
      Geocode: string -> CancellationToken -> Task<Result<GeocodeResult option, LocationError>> }

module Platform =
    /// "windows", "macos" or "linux", decided at run time.
    let osName =
        if OperatingSystem.IsWindows() then "windows"
        elif OperatingSystem.IsMacOS() then "macos"
        else "linux"

    /// The limit on one whole request.
    let requestTimeout = TimeSpan.FromSeconds 10.

    /// Stand-in for an OS whose backend isn't written yet.
    let unsupported: Platform =
        { GetLocation = fun _ -> Task.FromResult(Error PlatformUnsupported)
          ReverseGeocode = fun _ _ _ -> Task.FromResult None
          Geocode = fun _ _ -> Task.FromResult(Error PlatformUnsupported) }
