/// Windows backend: the WinRT Geolocator class, through the same projection
/// the C# port uses. Compare with original/whereami/src/platform/windows.zig,
/// which declares the same COM vtables, GUIDs and async polling by hand.
module Whereami.Windows

// Only compiled for the net10.0-windows10.0.19041.0 target, which brings in
// the WinRT projection: Windows.* namespaces look like ordinary .NET classes.
#if WINDOWS
open System
open System.Threading.Tasks
open Windows.Devices.Geolocation

[<Literal>]
let private E_ACCESSDENIED = 0x80070005

/// HRESULT_FROM_WIN32(ERROR_TIMEOUT): what the timeout overload fails with.
[<Literal>]
let private HRESULT_TIMEOUT = 0x800705B4

/// Translate whatever a WinRT call threw into whereami's error kinds.
let internal mapException (e: exn) : LocationError =
    match e with
    | :? UnauthorizedAccessException -> PermissionDenied
    | :? OperationCanceledException
    | :? TimeoutException -> LocationError.Timeout
    | _ when e.HResult = E_ACCESSDENIED -> PermissionDenied
    | _ when e.HResult = HRESULT_TIMEOUT -> LocationError.Timeout
    | _ -> LocationUnavailable

let private getLocation ct =
    task {
        // The projection throws on failure; this is where exceptions become Result values.
        try
            // IAsyncOperation<T> → Task<T> with AsTask; cancelling the token cancels the WinRT operation.
            let! access = Geolocator.RequestAccessAsync().AsTask(ct)
            if access <> GeolocationAccessStatus.Allowed then
                return Error PermissionDenied
            else
                let geolocator = Geolocator(DesiredAccuracy = PositionAccuracy.High)
                let! position =
                    geolocator.GetGeopositionAsync(maximumAge = TimeSpan.Zero, timeout = Platform.requestTimeout).AsTask(ct)
                let c = position.Coordinate
                return Ok { Latitude = c.Latitude; Longitude = c.Longitude; Accuracy = c.Accuracy }
        with e ->
            return Error(mapException e)
    }

let platform: Platform =
    { GetLocation = getLocation
      /// Windows has no usable built-in geocoder: MapLocationFinder needs a
      /// Bing Maps key and returns InvalidCredentials without one.
      ReverseGeocode = fun _ _ _ -> Task.FromResult None
      Geocode = fun _ _ -> Task.FromResult(Error PlatformUnsupported) }
#endif
