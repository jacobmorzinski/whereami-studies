/// Formats results for stdout. Pure: values in, strings out.
module Whereami.Output

open System.Text.Encodings.Web
open System.Text.Json

/// F#'s sprintf formats numbers the same on every machine: "40.7128", never "40,7128".
let human (loc: Location) (address: Address option) (showAccuracy: bool) =
    let lines =
        [ sprintf "Location: %.4f, %.4f" loc.Latitude loc.Longitude
          if showAccuracy then
              sprintf "Accuracy: %.0fm" loc.Accuracy
          match address with
          | Some a ->
              let parts =
                  [ a.Street; a.City; a.State; a.PostalCode; a.Country ]
                  |> List.filter (fun s -> s <> "")
              if not parts.IsEmpty then
                  "Address: " + String.concat ", " parts
          | None -> () ]
    lines |> List.map (fun line -> line + "\n") |> String.concat ""

let private options =
    JsonSerializerOptions(
        PropertyNamingPolicy = JsonNamingPolicy.SnakeCaseLower, // PostalCode → "postal_code"
        Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping) // "é" stays "é"

/// The JSON shape. System.Text.Json knows F# records and options: None is
/// written as null. It has to be public: F# gives a private or internal
/// record non-public properties, and the serializer only writes public ones
/// (the output was "{}"). An anonymous record {| … |} would also be public,
/// but F# sorts its fields alphabetically, which would reorder the keys.
type JsonBody =
    { Latitude: float
      Longitude: float
      Accuracy: float
      Address: Address option }

let json (loc: Location) (address: Address option) =
    let body: JsonBody =
        { Latitude = loc.Latitude
          Longitude = loc.Longitude
          Accuracy = loc.Accuracy
          Address = address }
    JsonSerializer.Serialize(body, options) + "\n"

let jsonError (key: string) =
    JsonSerializer.Serialize({| error = key |}, options) + "\n"
