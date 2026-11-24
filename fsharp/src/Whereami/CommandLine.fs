namespace Whereami

open System
open System.Globalization
open Argu

/// What the user asked for.
type Mode =
    | Current
    | Mock of Location
    | At of Location
    | Lookup of string

/// A fully validated command line.
type Request = { Mode: Mode; Json: bool }

/// What parsing produced. `Run` rather than `Ok`, so it can't be confused with Result.Ok.
type Parsed =
    /// Do the work.
    | Run of Request
    /// Help or version text for stdout; exit 0.
    | Info of string
    /// The complete text for stderr; exit 2.
    | UsageError of string

/// The command line, declared as a discriminated union: each case is one
/// argument, and Argu reads the attributes to know how it's spelled.
[<RequireQualifiedAccess>] // cases are written Args.Mock, so Mock alone means Mode.Mock
[<HelpFlags("--help", "-h")>] // without this, only --help is help (-h would be an address)
type Args =
    | Json
    | [<EqualsAssignmentOrSpaced>] Mock of lat_lon: string
    | [<EqualsAssignmentOrSpaced>] At of lat_lon: string
    | [<AltCommandLine("-v")>] Version
    | [<MainCommand>] Address of address: string list

    interface IArgParserTemplate with
        member a.Usage =
            match a with
            | Args.Json -> "print JSON instead of text."
            | Args.Mock _ -> "use these coordinates instead of location services (no address lookup)."
            | Args.At _ -> "look up the address at these coordinates."
            | Args.Version -> "print the version and exit."
            | Args.Address _ -> "an address to look up (macOS only)."

module CommandLine =
    /// Sign, digits, decimal point and exponent only. Whitespace, thousands
    /// separators and hex aren't allowed, and the text is read the same way
    /// whatever the machine's language.
    let private styles =
        NumberStyles.AllowLeadingSign ||| NumberStyles.AllowDecimalPoint ||| NumberStyles.AllowExponent

    let private number (text: string) =
        match Double.TryParse(text.Trim(' '), styles, CultureInfo.InvariantCulture) with
        | true, value when Double.IsFinite value -> Some value // rejects NaN, ∞ and overflow (1e999)
        | _ -> None

    /// "LAT,LON": split at the first comma, trim spaces from each half.
    let parseCoord (text: string) : Location option =
        match text.IndexOf ',' with
        | -1 -> None
        | comma ->
            match number (text.Substring(0, comma)), number (text.Substring(comma + 1)) with
            | Some lat, Some lon -> Some { Latitude = lat; Longitude = lon; Accuracy = 0. }
            | _ -> None

    let private parser =
        ArgumentParser.Create<Args>(
            programName = "whereami",
            helpTextMessage =
                "Get your current location using native OS location services, "
                + "or look up an address or a pair of coordinates.")

    let private usage (message: string) = UsageError $"Error: {message}\n"

    let private lines (text: string) = text.ReplaceLineEndings("\n").TrimEnd() + "\n"

    /// Turns an optional --mock/--at value into an optional Location, or an error message.
    let private coordArg name (value: string option) =
        match value with
        | None -> Ok None
        | Some text ->
            match parseCoord text with
            | Some loc -> Ok(Some loc)
            | None -> Error $"{name} expects LAT,LON with two decimal numbers, got '{text}'"

    let private outOfRange (loc: Location) = abs loc.Latitude > 90. || abs loc.Longitude > 180.

    /// The rules Argu can't express declaratively, in order.
    let private validate (results: ParseResults<Args>) : Parsed =
        let words = results.GetResult(Args.Address, defaultValue = [])
        let query = (String.concat " " words).Trim()
        let hasAddress = not words.IsEmpty
        if results.Contains Args.Version then
            Info $"whereami 0.4.0 ({Platform.osName})\n"
        elif List.tryHead words = Some "completions" then
            usage "completions are not available in this port"
        else
            // A [<MainCommand>] list takes every leftover argument, unknown flags included.
            match words |> List.tryFind (fun w -> w.StartsWith '-') with
            | Some flag -> usage $"unknown flag '{flag}'"
            | None ->
                match coordArg "--mock" (results.TryGetResult Args.Mock), coordArg "--at" (results.TryGetResult Args.At) with
                | Error message, _
                | _, Error message -> usage message
                | Ok _, Ok(Some at) when outOfRange at ->
                    usage "--at must be within latitude -90..90 and longitude -180..180"
                | Ok _, Ok _ when hasAddress && query = "" -> usage "the address is empty"
                | Ok(Some _), Ok(Some _) -> usage "--mock and --at can't be used together"
                | Ok mock, Ok at when hasAddress && (mock.IsSome || at.IsSome) ->
                    usage "an address can't be combined with --mock or --at"
                | Ok mock, Ok at ->
                    let mode =
                        match mock, at with
                        | Some loc, _ -> Mock loc
                        | _, Some loc -> At loc
                        | None, None when hasAddress -> Lookup query
                        | None, None -> Current
                    Run { Mode = mode; Json = results.Contains Args.Json }

    let parse (argv: string array) : Parsed =
        try
            validate (parser.Parse(argv, raiseOnUsage = true))
        with :? ArguParseException as e ->
            match e.ErrorCode with
            | ErrorCode.HelpText -> Info(lines e.Message)
            | _ -> UsageError(lines e.Message) // Argu's message already says what's wrong, plus usage
