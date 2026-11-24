module Whereami.Tests.AppTests

open System
open System.Diagnostics
open System.Threading.Tasks
open Xunit
open Whereami

let private loc = { Latitude = 1.; Longitude = 2.; Accuracy = 3. }

let private whiteHouse =
    { Street = "1600 Pennsylvania Ave NW"; City = "Washington"; State = "DC"; PostalCode = "20500"; Country = "US" }

/// A platform that fails the test if anything is called.
let private untouchable: Platform =
    { GetLocation = fun _ -> failwith "GetLocation should not be called"
      ReverseGeocode = fun _ _ _ -> failwith "ReverseGeocode should not be called"
      Geocode = fun _ _ -> failwith "Geocode should not be called" }

/// Fakes are copies of the stub with one or two functions replaced.
let private locatedAt l =
    { Platform.unsupported with GetLocation = fun _ -> Task.FromResult(Ok l) }

let private failing error =
    { Platform.unsupported with GetLocation = fun _ -> Task.FromResult(Error error) }

let private never<'T> () = TaskCompletionSource<'T>().Task // completes never, ignores cancellation

let private run mode json platform =
    (App.run { Mode = mode; Json = json } platform (TimeSpan.FromSeconds 5.)).GetAwaiter().GetResult()

[<Fact>]
let mockSkipsPlatform () =
    let out = run (Mock { Latitude = 40.7128; Longitude = -74.006; Accuracy = 0. }) false untouchable
    Assert.Equal({ Stdout = "Location: 40.7128, -74.0060\nAccuracy: 0m\n"; Stderr = ""; ExitCode = 0 }, out)

[<Fact>]
let currentWithAddress () =
    let platform = { locatedAt loc with ReverseGeocode = fun _ _ _ -> Task.FromResult(Some whiteHouse) }
    Assert.Equal(
        { Stdout =
            "Location: 1.0000, 2.0000\nAccuracy: 3m\n"
            + "Address: 1600 Pennsylvania Ave NW, Washington, DC, 20500, US\n"
          Stderr = ""
          ExitCode = 0 },
        run Current false platform)

[<Fact>]
let atWithoutAddressWarns () =
    Assert.Equal(
        { Stdout = "Location: 90.0000, -180.0000\n"
          Stderr = "Warning: no address found for these coordinates.\n"
          ExitCode = 0 },
        run (At { Latitude = 90.; Longitude = -180.; Accuracy = 0. }) false Platform.unsupported)

[<Fact>]
let permissionDeniedJson () =
    let out = run Current true (failing PermissionDenied)
    Assert.Equal("{\"error\":\"permission_denied\"}\n", out.Stdout)
    Assert.StartsWith("Error: location permission denied.", out.Stderr)
    Assert.Equal(1, out.ExitCode)

[<Fact>]
let unsupportedLocation () =
    Assert.Equal(
        { Stdout = ""; Stderr = "Error: location is not supported on this platform yet.\n"; ExitCode = 1 },
        run Current false Platform.unsupported)

[<Fact>]
let lookupUnsupported () =
    Assert.Equal(
        { Stdout = "{\"error\":\"platform_unsupported\"}\n"
          Stderr = "Error: address lookup is not supported on this platform.\n"
          ExitCode = 1 },
        run (Lookup "Paris") true Platform.unsupported)

[<Fact>]
let lookupNotFound () =
    let platform = { Platform.unsupported with Geocode = fun _ _ -> Task.FromResult(Ok None) }
    let out = run (Lookup "Paris") true platform
    Assert.Equal("{\"error\":\"address_not_found\"}\n", out.Stdout)
    Assert.Contains("couldn't find a location for \"Paris\"", out.Stderr)
    Assert.Equal(1, out.ExitCode)

[<Fact>]
let lookupFoundOmitsAccuracy () =
    let found = { Location = { Latitude = 38.8977; Longitude = -77.0366; Accuracy = 0. }; Address = whiteHouse }
    let platform = { Platform.unsupported with Geocode = fun _ _ -> Task.FromResult(Ok(Some found)) }
    let out = run (Lookup "1600 Pennsylvania Ave") false platform
    Assert.Equal(0, out.ExitCode)
    Assert.DoesNotContain("Accuracy:", out.Stdout)
    Assert.Contains("Address: 1600 Pennsylvania Ave NW", out.Stdout)

[<Fact>]
let timeoutHoldsWhenPlatformIgnoresCancellation () =
    let platform = { Platform.unsupported with GetLocation = fun _ -> never () }
    let clock = Stopwatch.StartNew()
    let out =
        (App.run { Mode = Current; Json = true } platform (TimeSpan.FromMilliseconds 200.)).GetAwaiter().GetResult()
    Assert.Equal(
        { Stdout = "{\"error\":\"timeout\"}\n"; Stderr = "Error: location request timed out.\n"; ExitCode = 1 },
        out)
    Assert.True(clock.Elapsed < TimeSpan.FromSeconds 2., $"took {clock.Elapsed}")

[<Fact>]
let timeoutCoversWholeRequest () =
    // Location takes 400 ms, then reverse geocoding hangs. One 500 ms budget ends at ~500 ms;
    // a fresh 500 ms per call would end at ~900 ms.
    let platform =
        { GetLocation =
            fun _ ->
                task {
                    do! Task.Delay 400
                    return Ok loc
                }
          ReverseGeocode = fun _ _ _ -> never ()
          Geocode = Platform.unsupported.Geocode }
    let clock = Stopwatch.StartNew()
    let out =
        (App.run { Mode = Current; Json = true } platform (TimeSpan.FromMilliseconds 500.)).GetAwaiter().GetResult()
    Assert.Equal("{\"error\":\"timeout\"}\n", out.Stdout)
    Assert.True(clock.Elapsed < TimeSpan.FromMilliseconds 750., $"took {clock.Elapsed}")

[<Fact>]
let unexpectedExceptionPropagates () =
    let platform = { Platform.unsupported with GetLocation = fun _ -> raise (InvalidOperationException "bug") }
    Assert.Throws<InvalidOperationException>(fun () -> run Current false platform |> ignore) |> ignore
