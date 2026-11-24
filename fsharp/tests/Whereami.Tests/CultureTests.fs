namespace Whereami.Tests

open System
open System.Globalization
open Xunit
open Whereami

/// Runs with a culture whose decimal separator is a comma.
type CultureTests() =
    let saved = CultureInfo.CurrentCulture
    do CultureInfo.CurrentCulture <- CultureInfo "de-DE"

    interface IDisposable with
        member _.Dispose() = CultureInfo.CurrentCulture <- saved

    [<Fact>]
    member _.HumanOutputIsInvariant() =
        Assert.StartsWith(
            "Location: 40.7128, -74.0060",
            Output.human { Latitude = 40.7128; Longitude = -74.0060; Accuracy = 0. } None true)
