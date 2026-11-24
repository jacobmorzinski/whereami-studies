module Whereami.Tests.CommandLineTests

open Xunit
open Whereami

let private parse (args: string list) = CommandLine.parse (Array.ofList args)

let private request args =
    match parse args with
    | Run r -> r
    | other -> failwithf "expected Run, got %A" other

let private assertUsage args =
    match parse args with
    | UsageError _ -> ()
    | other -> failwithf "expected UsageError for %A, got %A" args other

let private coord lat lon = { Latitude = lat; Longitude = lon; Accuracy = 0. }

[<Theory>]
[<InlineData("40.7128,-74.0060", 40.7128, -74.006)>]
[<InlineData(" 51.5 , -0.1276", 51.5, -0.1276)>]
[<InlineData("1e1,+2", 10., 2.)>]
let parsesCoord (text: string, lat: float, lon: float) =
    Assert.Equal(Some(coord lat lon), CommandLine.parseCoord text)

[<Theory>]
[<InlineData("40.7128")>]
[<InlineData("abc,def")>]
[<InlineData("1,2,3")>]
[<InlineData("nan,0")>]
[<InlineData("NaN,0")>]
[<InlineData("0,inf")>]
[<InlineData("Infinity,0")>]
[<InlineData("∞,0")>]
[<InlineData("0x10,0")>]
[<InlineData("1e999,0")>]
[<InlineData("\t1,2")>]
[<InlineData(",")>]
let rejectsCoord (text: string) =
    Assert.Equal(None, CommandLine.parseCoord text)

[<Fact>]
let versionBothSpellings () =
    for flag in [ "--version"; "-v" ] do
        Assert.Equal(Info $"whereami 0.4.0 ({Platform.osName})\n", parse [ flag ])

[<Fact>]
let shortHelpIsInfo () =
    for flag in [ "-h"; "--help" ] do
        match parse [ flag ] with
        | Info text -> Assert.Contains("--mock", text)
        | other -> failwithf "expected Info for %s, got %A" flag other

[<Fact>]
let unknownFlagIsUsageError () =
    assertUsage [ "--bogus" ]
    assertUsage [ "Paris"; "-x" ]

[<Fact>]
let atSignIsAddress () =
    Assert.Equal(Lookup "@home", (request [ "@home" ]).Mode)

[<Fact>]
let multiwordAddressJoined () =
    Assert.Equal(Lookup "350 5th Ave", (request [ "350"; "5th"; "Ave" ]).Mode)

[<Fact>]
let mockNegativeEqualsForm () =
    Assert.Equal(Mock(coord -33.8688 151.2093), (request [ "--mock=-33.8688,151.2093" ]).Mode)

[<Fact>]
let jsonFlag () =
    Assert.True((request [ "--json"; "--mock=1,2" ]).Json)

[<Fact>]
let completionsReserved () =
    match parse [ "completions"; "bash" ] with
    | UsageError message -> Assert.Contains("completions are not available in this port", message)
    | other -> failwithf "expected UsageError, got %A" other

[<Fact>]
let conflictsAreUsageErrors () =
    assertUsage [ "--at=1,2"; "--mock=1,2" ]
    assertUsage [ "--mock=1,2"; "Paris" ]
    assertUsage [ "   " ]
    assertUsage [ "--at=91,0" ]
    assertUsage [ "--at=0,-180.5" ]
    assertUsage [ "--mock=x" ]

[<Fact>]
let atBoundaryAccepted () =
    Assert.Equal(At(coord 90. -180.), (request [ "--at=90,-180" ]).Mode)

[<Fact>]
let noArgumentsIsCurrent () =
    Assert.Equal({ Mode = Current; Json = false }, request [])
