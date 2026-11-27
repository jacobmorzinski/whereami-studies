module Whereami.Tests.LinuxTests

// Only the plain net10.0 build has the Linux code to test.
#if !WINDOWS
open System
open System.Diagnostics
open System.Threading
open System.Threading.Channels
open System.Threading.Tasks
open Xunit
open Whereami

[<Theory>]
[<InlineData("org.freedesktop.DBus.Error.AccessDenied", "PermissionDenied")>]
[<InlineData("org.freedesktop.DBus.Error.ServiceUnknown", "LocationUnavailable")>]
[<InlineData("org.freedesktop.DBus.Error.Failed", "LocationUnavailable")>]
[<InlineData("", "LocationUnavailable")>]
[<InlineData(null, "LocationUnavailable")>]
let errorNames (name: string, expected: string) =
    let want = if expected = "PermissionDenied" then PermissionDenied else LocationUnavailable
    Assert.Equal(want, Linux.mapErrorName name)

let private fix a = { Latitude = a; Longitude = 0.; Accuracy = a }

let private inSpan (span: TimeSpan) =
    Stopwatch.GetTimestamp() + int64 (span.TotalSeconds * float Stopwatch.Frequency)

let private far = TimeSpan.FromSeconds 60.
let private ms (n: float) = TimeSpan.FromMilliseconds n
let private newChannel () = Channel.CreateUnbounded<Location>()

[<Fact>]
let prefersFineAfterCoarse () =
    task {
        let ch = newChannel ()
        ch.Writer.TryWrite(fix 25000.) |> ignore
        ch.Writer.TryWrite(fix 10.) |> ignore
        let! best = Linux.bestFix ch.Reader (ms 20.) (inSpan far) CancellationToken.None
        Assert.Equal(Some(fix 10.), best)
    }

[<Fact>]
let singleFixReturnsAfterGrace () =
    task {
        let ch = newChannel ()
        ch.Writer.TryWrite(fix 5.) |> ignore
        let grace = ms 50.
        let sw = Stopwatch.StartNew()
        let! best = Linux.bestFix ch.Reader grace (inSpan far) CancellationToken.None
        Assert.Equal(Some(fix 5.), best)
        Assert.True(sw.Elapsed >= grace - ms 5., $"returned early after {sw.Elapsed}")
        Assert.True(sw.Elapsed < TimeSpan.FromSeconds 1., $"waited {sw.Elapsed}")
    }

[<Fact>]
let tieKeepsFirst () =
    task {
        let ch = newChannel ()
        let first = { Latitude = 1.; Longitude = 1.; Accuracy = 10. }
        ch.Writer.TryWrite first |> ignore
        ch.Writer.TryWrite { Latitude = 2.; Longitude = 2.; Accuracy = 10. } |> ignore
        let! best = Linux.bestFix ch.Reader (ms 20.) (inSpan far) CancellationToken.None
        Assert.Equal(Some first, best)
    }

[<Fact>]
let ignoresFixAfterWindow () =
    task {
        let ch = newChannel ()
        ch.Writer.TryWrite(fix 100.) |> ignore
        let late =
            task {
                do! Task.Delay 200
                ch.Writer.TryWrite(fix 1.) |> ignore
            }
        let! best = Linux.bestFix ch.Reader (ms 20.) (inSpan far) CancellationToken.None
        Assert.Equal(Some(fix 100.), best)
        do! late
    }

[<Fact>]
let clampsGraceToDeadline () =
    task {
        let ch = newChannel ()
        ch.Writer.TryWrite(fix 5.) |> ignore
        let sw = Stopwatch.StartNew()
        let! best = Linux.bestFix ch.Reader (ms 250.) (inSpan (ms 30.)) CancellationToken.None
        Assert.Equal(Some(fix 5.), best)
        Assert.True(sw.Elapsed < ms 150., $"waited {sw.Elapsed}")
    }

[<Fact>]
let noneWhenNoFixByDeadline () =
    task {
        let ch = newChannel ()
        let sw = Stopwatch.StartNew()
        let! best = Linux.bestFix ch.Reader (ms 250.) (inSpan (ms 50.)) CancellationToken.None
        Assert.Equal(None, best)
        Assert.True(sw.Elapsed >= ms 40., $"returned early after {sw.Elapsed}")
        Assert.True(sw.Elapsed < ms 500., $"waited {sw.Elapsed}")
    }

/// Documented choice: a writer that completes before any fix gives None, the
/// same as the deadline passing; the caller tells them apart by Completion.
[<Fact>]
let noneWhenWriterCompletedWithoutFix () =
    task {
        let ch = newChannel ()
        ch.Writer.Complete()
        let! best = Linux.bestFix ch.Reader (ms 20.) (inSpan far) CancellationToken.None
        Assert.Equal(None, best)
    }

[<Fact>]
let cancellationThrows () =
    task {
        let ch = newChannel ()
        use cts = new CancellationTokenSource(30)
        let! ex =
            Assert.ThrowsAnyAsync<OperationCanceledException>(fun () ->
                Linux.bestFix ch.Reader (ms 20.) (inSpan far) cts.Token :> Task)
        Assert.NotNull ex
    }
#endif
