module Whereami.Program

open System
open System.Text

/// The entry point: parse, run, print, return the exit code.
[<EntryPoint>]
let main argv =
    Console.OutputEncoding <- UTF8Encoding(false) // non-ASCII addresses print correctly
    match CommandLine.parse argv with
    | Info text ->
        Console.Out.Write text
        0
    | UsageError text ->
        Console.Error.Write text
        2
    | Run request ->
        let out = (App.run request (Native.platform ()) Platform.requestTimeout).GetAwaiter().GetResult()
        Console.Out.Write out.Stdout
        Console.Error.Write out.Stderr
        out.ExitCode
