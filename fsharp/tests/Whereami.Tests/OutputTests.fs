module Whereami.Tests.OutputTests

open System.Text.Json
open Xunit
open Whereami

let loc0 = { Latitude = 40.7128; Longitude = -74.0060; Accuracy = 0. }

[<Fact>]
let humanWithAccuracy () =
    Assert.Equal("Location: 40.7128, -74.0060\nAccuracy: 0m\n", Output.human loc0 None true)

[<Fact>]
let humanWithoutAccuracySkipsEmptyFields () =
    let address =
        { Street = "1600 Pennsylvania Ave NW"; City = "Washington"; State = ""; PostalCode = "20500"; Country = "US" }
    Assert.Equal(
        "Location: 38.8977, -77.0366\nAddress: 1600 Pennsylvania Ave NW, Washington, 20500, US\n",
        Output.human { Latitude = 38.8977; Longitude = -77.0366; Accuracy = 5. } (Some address) false)

[<Fact>]
let jsonNullAddress () =
    use doc = JsonDocument.Parse(Output.json { loc0 with Longitude = -74.006 } None)
    let root = doc.RootElement
    Assert.Equal(40.7128, root.GetProperty("latitude").GetDouble())
    Assert.Equal(JsonValueKind.Null, root.GetProperty("address").ValueKind)
    Assert.Equal<string list>(
        [ "latitude"; "longitude"; "accuracy"; "address" ],
        [ for p in root.EnumerateObject() -> p.Name ])

[<Fact>]
let jsonEscapesAddress () =
    let address = { Street = "5 \"Q\" St\\"; City = "Montréal"; State = ""; PostalCode = "H2X"; Country = "CA" }
    let json = Output.json { Latitude = 1.; Longitude = 2.; Accuracy = 3. } (Some address)
    use doc = JsonDocument.Parse(json)
    let a = doc.RootElement.GetProperty("address")
    Assert.Equal(JsonValueKind.Object, a.ValueKind) // a plain object, not {"Value": …}
    Assert.Equal("5 \"Q\" St\\", a.GetProperty("street").GetString())
    Assert.Equal("H2X", a.GetProperty("postal_code").GetString())
    Assert.Contains("Montréal", json)

[<Fact>]
let jsonErrorShape () =
    Assert.Equal("{\"error\":\"timeout\"}\n", Output.jsonError "timeout")

[<Fact>]
let jsonKeys () =
    Assert.Equal<string list>(
        [ "permission_denied"; "location_unavailable"; "timeout"; "unknown_error"; "platform_unsupported" ],
        [ PermissionDenied; LocationUnavailable; Timeout; GeocodingFailed; PlatformUnsupported ]
        |> List.map LocationError.jsonKey)
