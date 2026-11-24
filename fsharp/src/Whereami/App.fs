namespace Whereami

open System
open System.Threading
open System.Threading.Tasks

/// What to print and which exit code to return.
type Outcome = { Stdout: string; Stderr: string; ExitCode: int }

/// What whereami does, given a validated request and an OS backend. Pure:
/// it returns an Outcome instead of printing, so tests drive it with fakes.
module App =
    let private success (req: Request) loc address showAccuracy =
        { Stdout = if req.Json then Output.json loc address else Output.human loc address showAccuracy
          Stderr = ""
          ExitCode = 0 }

    let private failure (req: Request) key message =
        { Stdout = if req.Json then Output.jsonError key else ""
          Stderr = message
          ExitCode = 1 }

    let private locationFailure req error =
        let message =
            match error with
            | PermissionDenied ->
                "Error: location permission denied.\n"
                + "Grant location access in your system's privacy/location settings.\n"
            | LocationUnavailable -> "Error: location unavailable.\nMake sure location services are enabled.\n"
            | LocationError.Timeout -> "Error: location request timed out.\n"
            | PlatformUnsupported -> "Error: location is not supported on this platform yet.\n"
            | GeocodingFailed -> "Error: unexpected error (GeocodingFailed)\n"
        failure req (LocationError.jsonKey error) message

    /// All the platform calls for one request, as one task.
    let private work (req: Request) (platform: Platform) (ct: CancellationToken) : Task<Outcome> =
        task {
            match req.Mode with
            | Mock loc -> return success req loc None true
            | Current ->
                match! platform.GetLocation ct with
                | Error e -> return locationFailure req e
                | Ok loc ->
                    let! address = platform.ReverseGeocode loc.Latitude loc.Longitude ct
                    return success req loc address true
            | At loc ->
                let! address = platform.ReverseGeocode loc.Latitude loc.Longitude ct
                let out = success req loc address false
                // With --at the address is the whole point, so say so when there isn't one.
                return
                    match address with
                    | Some _ -> out
                    | None -> { out with Stderr = "Warning: no address found for these coordinates.\n" }
            | Lookup query ->
                match! platform.Geocode query ct with
                | Ok(Some found) -> return success req found.Location (Some found.Address) false
                | Ok None ->
                    return
                        failure req "address_not_found"
                            ($"Error: couldn't find a location for \"{query}\".\n"
                             + "Check the address and your network connection.\n")
                | Error PlatformUnsupported ->
                    return
                        failure req "platform_unsupported" "Error: address lookup is not supported on this platform.\n"
                | Error e -> return locationFailure req e
        }

    /// Runs the request with one time budget for all of its platform calls:
    /// the token asks platforms to stop, and WaitAsync stops us waiting even
    /// if one ignores the token. Unexpected exceptions propagate.
    let run (req: Request) (platform: Platform) (timeout: TimeSpan) : Task<Outcome> =
        task {
            use cts = new CancellationTokenSource(timeout)
            try
                return! (work req platform cts.Token).WaitAsync(timeout)
            with
            | :? TimeoutException -> return locationFailure req LocationError.Timeout
            | :? OperationCanceledException when cts.IsCancellationRequested -> return locationFailure req LocationError.Timeout
        }
