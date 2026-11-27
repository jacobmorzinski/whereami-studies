/// Linux backend: GeoClue2 over the system D-Bus, through the classic
/// Tmds.DBus library (proxies generated at run time from the interfaces in
/// GeoClue.fs). Compare original/whereami/src/platform/linux.zig, which drives
/// libdbus by hand, and the C# port's LinuxPlatform, whose conversation this
/// follows exactly.
///
/// Extension over the original: GeoClue may send a coarse fix first and a
/// precise one moments later, so after the first fix we listen a little longer
/// and return the most accurate (see bestFix).
module Whereami.Linux

#if !WINDOWS
open System
open System.Diagnostics
open System.Threading
open System.Threading.Channels
open System.Threading.Tasks
open Tmds.DBus
open Whereami.GeoClue

/// RequestedAccuracyLevel for GeoClue's EXACT level (a D-Bus uint32).
let private accuracyExact = 8u

/// After the first fix, how long to keep listening for a better one.
let private bestFixWindow = TimeSpan.FromMilliseconds 250.

/// Time kept in reserve before the overall deadline, for Stop and exit.
let private deadlineMargin = TimeSpan.FromMilliseconds 50.

/// How long we wait for the Stop call to leave the socket.
let private stopBound = TimeSpan.FromMilliseconds 30.

let private toTicks (span: TimeSpan) =
    int64 (span.TotalSeconds * float Stopwatch.Frequency)

/// Translate a D-Bus error name into whereami's error kinds.
let internal mapErrorName (name: string) : LocationError =
    if not (isNull name) && name.Contains("AccessDenied", StringComparison.Ordinal) then
        PermissionDenied
    else
        LocationUnavailable

/// True when a fix is ready to read; false when the time is up or the writer
/// completed. Already-queued fixes count even past the time.
let private waitForFix (fixes: ChannelReader<Location>) (until: int64) (ct: CancellationToken) : Task<bool> =
    task {
        let mutable peeked = Unchecked.defaultof<Location>
        if fixes.TryPeek(&peeked) then
            return true
        else
            let remaining = Stopwatch.GetElapsedTime(Stopwatch.GetTimestamp(), until)
            if remaining <= TimeSpan.Zero then
                return false
            else
                use cts = CancellationTokenSource.CreateLinkedTokenSource ct
                cts.CancelAfter remaining
                try
                    return! fixes.WaitToReadAsync(cts.Token).AsTask()
                with :? OperationCanceledException when not ct.IsCancellationRequested ->
                    return false
    }

/// Wait for the first fix, then keep listening for `grace` (but never past
/// `deadline`, a Stopwatch timestamp) and return the most accurate one seen:
/// smallest accuracy, and on a tie the earlier. The first wait is bounded by
/// the deadline too. None means no fix: the deadline passed, or the writer
/// completed before any fix (the caller tells them apart by the channel's
/// Completion). Throws OperationCanceledException only if `ct` is cancelled.
let internal bestFix
    (fixes: ChannelReader<Location>)
    (grace: TimeSpan)
    (deadline: int64)
    (ct: CancellationToken)
    : Task<Location option> =
    task {
        let! any = waitForFix fixes deadline ct
        let mutable first = Unchecked.defaultof<Location>
        if not any || not (fixes.TryRead(&first)) then
            return None
        else
            let mutable best = first
            let until = min (Stopwatch.GetTimestamp() + toTicks grace) deadline
            let mutable go = true
            while go do
                let! more = waitForFix fixes until ct
                go <- more
                if more then
                    let mutable fix = Unchecked.defaultof<Location>
                    while fixes.TryRead(&fix) do
                        if fix.Accuracy < best.Accuracy then
                            best <- fix
            return Some best
    }

/// Best effort, sent on every path including timeout. Waiting is bounded (a
/// Stop reply must never push us past the deadline), and a failure is
/// ignored: closing the connection ends our client anyway.
let private sendStop (client: IClient) : Task =
    task {
        let mutable stop: Task = null
        try
            stop <- client.StopAsync()
            do! stop.WaitAsync stopBound
        with _ ->
            () // Timed out, or the call failed; nothing more to do.
        // Observe a failure that arrives after we stopped waiting.
        if not (isNull stop) then
            stop.ContinueWith((fun (t: Task) -> t.Exception |> ignore), TaskScheduler.Default) |> ignore
    }

/// Turns each LocationUpdated "new" path into a Location, in order. A fix
/// whose properties can't be read is dropped, not defaulted: an accuracy of 0
/// would always win bestFix.
let private readFixes
    (connection: Connection)
    (paths: ChannelReader<ObjectPath>)
    (fixes: ChannelWriter<Location>)
    (ct: CancellationToken)
    : Task =
    task {
        try
            try
                let e = paths.ReadAllAsync(ct).GetAsyncEnumerator(ct)
                try
                    let mutable go = true
                    while go do
                        let! has = e.MoveNextAsync()
                        go <- has
                        if has then
                            try
                                let location = connection.CreateProxy<ILocation>(Names.Service, e.Current)
                                let! lat = location.GetAsync<float>("Latitude").WaitAsync ct
                                let! lon = location.GetAsync<float>("Longitude").WaitAsync ct
                                let! acc = location.GetAsync<float>("Accuracy").WaitAsync ct
                                fixes.TryWrite { Latitude = lat; Longitude = lon; Accuracy = acc } |> ignore
                            with e when not (e :? OperationCanceledException) ->
                                () // Skip this fix.
                finally
                    e.DisposeAsync().AsTask().Wait()
            with :? OperationCanceledException ->
                ()
        finally
            fixes.TryComplete() |> ignore
    }

exception private Failed of LocationError

let private query (deadline: int64) (ct: CancellationToken) : Task<Location> =
    task {
        // The address is validated by the constructor; a bad one throws here.
        let address =
            match Tmds.DBus.Address.System with
            | null -> raise (Failed LocationUnavailable)
            | a -> a
        use connection = new Connection(address)
        let! _ = connection.ConnectAsync().WaitAsync ct

        let manager = connection.CreateProxy<IManager>(Names.Service, Names.ManagerPath)
        let! clientPath = manager.CreateClientAsync().WaitAsync ct
        let client = connection.CreateProxy<IClient>(Names.Service, clientPath)
        try
            do! client.SetAsync("DesktopId", box "whereami").WaitAsync ct
            do! client.SetAsync("RequestedAccuracyLevel", box accuracyExact).WaitAsync ct

            // Subscribe before Start, so the first fix can't slip past us. The
            // proxy sits on our client's own path, so only our signals arrive.
            // Property reads go through a queue, one at a time, so fixes keep
            // GeoClue's order (a tie must go to the earlier one).
            let paths = Channel.CreateUnbounded<ObjectPath>()
            let fixes = Channel.CreateUnbounded<Location>()
            use! watch =
                client
                    .WatchLocationUpdatedAsync(
                        Action<struct (ObjectPath * ObjectPath)>(fun struct (_, n) -> paths.Writer.TryWrite n |> ignore),
                        Action<exn>(fun _ -> paths.Writer.TryComplete() |> ignore)
                    )
                    .WaitAsync ct
            let _reader = readFixes connection paths.Reader fixes.Writer ct
            try
                do! client.StartAsync().WaitAsync ct
                let! best = bestFix fixes.Reader bestFixWindow (deadline - toTicks deadlineMargin) ct
                match best with
                | Some l -> return l
                | None ->
                    return
                        raise (
                            Failed(
                                if fixes.Reader.Completion.IsCompleted then
                                    LocationUnavailable
                                else
                                    LocationError.Timeout
                            )
                        )
            finally
                paths.Writer.TryComplete() |> ignore
        finally
            // Stop goes out on every path after the client exists, before the
            // connection closes.
            (sendStop client).Wait()
    }

let private getLocation (ct: CancellationToken) : Task<Result<Location, LocationError>> =
    task {
        let budget = Platform.requestTimeout
        let deadline = Stopwatch.GetTimestamp() + toTicks budget
        // Tmds.DBus has no connect timeout and its calls take no token, so the
        // whole conversation is bounded by cancelling the awaits (WaitAsync).
        use cts = CancellationTokenSource.CreateLinkedTokenSource ct
        cts.CancelAfter budget
        try
            let! l = query deadline cts.Token
            return Ok l
        with
        | Failed e -> return Error e
        | :? OperationCanceledException -> return Error LocationError.Timeout
        | :? DBusException as d -> return Error(mapErrorName d.ErrorName)
        | _ ->
            // No bus, malformed bus address, anything the library throws:
            // nothing to locate with. Nothing escapes as an unexpected error.
            return Error LocationUnavailable
    }

let platform: Platform =
    { GetLocation = getLocation
      /// GeoClue has no geocoder.
      ReverseGeocode = fun _ _ _ -> Task.FromResult None
      Geocode = fun _ _ -> Task.FromResult(Error PlatformUnsupported) }
#endif
