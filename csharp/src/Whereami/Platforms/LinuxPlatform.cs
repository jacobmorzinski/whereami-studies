#if !WINDOWS
using System.Diagnostics;
using System.Threading.Channels;
using Tmds.DBus;
using Whereami.GeoClue;

namespace Whereami;

/// Linux backend: GeoClue2 over the system D-Bus, through the classic
/// Tmds.DBus library (async/await; proxies generated at run time from the
/// interfaces in GeoClue.cs). Compare original/whereami/src/platform/linux.zig,
/// which drives libdbus by hand, and rust/src/platform/linux.rs, whose
/// conversation this follows exactly.
///
/// Extension over the original: GeoClue may send a coarse fix first and a
/// precise one moments later, so after the first fix we listen a little longer
/// and return the most accurate (see BestFixAsync).
public sealed class LinuxPlatform(TimeSpan budget) : IPlatform
{
    /// RequestedAccuracyLevel for GeoClue's EXACT level (a D-Bus uint32).
    private const uint AccuracyExact = 8;

    /// After the first fix, how long to keep listening for a better one.
    private static readonly TimeSpan BestFixWindow = TimeSpan.FromMilliseconds(250);

    /// Time kept in reserve before the overall deadline, for Stop and exit.
    private static readonly TimeSpan DeadlineMargin = TimeSpan.FromMilliseconds(50);

    /// How long we wait for the Stop call to leave the socket.
    private static readonly TimeSpan StopBound = TimeSpan.FromMilliseconds(30);

    public async Task<Location> GetLocationAsync(CancellationToken ct)
    {
        // IPlatform only passes a token, so the budget comes from the constructor.
        var deadline = Stopwatch.GetTimestamp() + (long)(budget.TotalSeconds * Stopwatch.Frequency);
        // Tmds.DBus has no connect timeout and its calls take no token, so the
        // whole conversation is bounded by cancelling the awaits (WaitAsync).
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        cts.CancelAfter(budget);
        try
        {
            return await QueryAsync(deadline, cts.Token);
        }
        catch (Exception e) when (e is not (LocationException or OperationCanceledException))
        {
            // DBusException, no bus, malformed bus address, anything the library
            // throws: nothing to locate with. Nothing escapes as an unexpected error.
            throw new LocationException(e is DBusException d ? FromDBusErrorName(d.ErrorName) : LocationError.LocationUnavailable);
        }
    }

    private static async Task<Location> QueryAsync(long deadline, CancellationToken ct)
    {
        // The address is validated by the constructor; a bad one throws here.
        using var connection = new Connection(Tmds.DBus.Address.System ?? throw new LocationException(LocationError.LocationUnavailable));
        await connection.ConnectAsync().WaitAsync(ct);

        var manager = connection.CreateProxy<IManager>(Names.Service, Names.ManagerPath);
        var clientPath = await manager.CreateClientAsync().WaitAsync(ct);
        var client = connection.CreateProxy<IClient>(Names.Service, clientPath);
        try
        {
            await client.SetAsync("DesktopId", "whereami").WaitAsync(ct);
            await client.SetAsync("RequestedAccuracyLevel", AccuracyExact).WaitAsync(ct);

            // Subscribe before Start, so the first fix can't slip past us. The
            // proxy sits on our client's own path, so only our signals arrive.
            // Property reads go through a queue, one at a time, so fixes keep
            // GeoClue's order (a tie must go to the earlier one).
            var paths = Channel.CreateUnbounded<ObjectPath>();
            var fixes = Channel.CreateUnbounded<Location>();
            using var watch = await client.WatchLocationUpdatedAsync(
                update => paths.Writer.TryWrite(update.New),
                _ => paths.Writer.TryComplete()).WaitAsync(ct);
            var reader = ReadFixesAsync(connection, paths.Reader, fixes.Writer, ct);
            try
            {
                await client.StartAsync().WaitAsync(ct);

                var best = await BestFixAsync(fixes.Reader, BestFixWindow, deadline - ToTicks(DeadlineMargin), ct);
                return best ?? throw new LocationException(
                    fixes.Reader.Completion.IsCompleted ? LocationError.LocationUnavailable : LocationError.Timeout);
            }
            finally
            {
                paths.Writer.TryComplete();
            }
        }
        finally
        {
            // Stop goes out on every path after the client exists, before the
            // connection closes.
            await SendStopAsync(client);
        }
    }

    /// Best effort, sent on every path including timeout. Waiting is bounded
    /// (a Stop reply must never push us past the deadline), and a failure
    /// is ignored: closing the connection ends our client anyway.
    private static async Task SendStopAsync(IClient client)
    {
        Task? stop = null;
        try
        {
            stop = client.StopAsync();
            await stop.WaitAsync(StopBound);
        }
        catch (Exception)
        {
            // Timed out, or the call failed; nothing more to do.
        }
        // Observe a failure that arrives after we stopped waiting.
        _ = stop?.ContinueWith(t => _ = t.Exception, TaskScheduler.Default);
    }

    /// Turns each LocationUpdated "new" path into a Location, in order. A fix
    /// whose properties can't be read is dropped, not defaulted: an accuracy
    /// of 0 would always win BestFixAsync.
    private static async Task ReadFixesAsync(
        Connection connection, ChannelReader<ObjectPath> paths, ChannelWriter<Location> fixes, CancellationToken ct)
    {
        try
        {
            await foreach (var path in paths.ReadAllAsync(ct))
            {
                try
                {
                    var location = connection.CreateProxy<ILocation>(Names.Service, path);
                    var fix = new Location(
                        await location.GetAsync<double>("Latitude").WaitAsync(ct),
                        await location.GetAsync<double>("Longitude").WaitAsync(ct),
                        await location.GetAsync<double>("Accuracy").WaitAsync(ct));
                    fixes.TryWrite(fix);
                }
                catch (Exception e) when (e is not OperationCanceledException)
                {
                    // Skip this fix.
                }
            }
        }
        catch (OperationCanceledException)
        {
        }
        finally
        {
            fixes.TryComplete();
        }
    }

    private static long ToTicks(TimeSpan span) => (long)(span.TotalSeconds * Stopwatch.Frequency);

    /// Translate a D-Bus error name into whereami's error kinds.
    internal static LocationError FromDBusErrorName(string? name) =>
        name is not null && name.Contains("AccessDenied", StringComparison.Ordinal)
            ? LocationError.PermissionDenied
            : LocationError.LocationUnavailable;

    /// Wait for the first fix, then keep listening for <paramref name="grace"/>
    /// (but never past <paramref name="deadline"/>, a Stopwatch timestamp) and
    /// return the most accurate one seen: smallest accuracy, and on a tie the
    /// earlier. The first wait is bounded by the deadline too. Null means no
    /// fix: the deadline passed, or the writer completed before any fix.
    /// Throws OperationCanceledException only if <paramref name="ct"/> is cancelled.
    internal static async Task<Location?> BestFixAsync(
        ChannelReader<Location> fixes, TimeSpan grace, long deadline, CancellationToken ct)
    {
        if (!await WaitForFixAsync(fixes, deadline, ct) || !fixes.TryRead(out var best))
        {
            return null;
        }

        var end = Math.Min(Stopwatch.GetTimestamp() + ToTicks(grace), deadline);
        while (await WaitForFixAsync(fixes, end, ct))
        {
            while (fixes.TryRead(out var fix))
            {
                if (fix.Accuracy < best.Accuracy)
                {
                    best = fix;
                }
            }
        }
        return best;
    }

    /// True when a fix is ready to read; false when the time is up or the
    /// writer completed. Already-queued fixes count even past the time.
    private static async Task<bool> WaitForFixAsync(ChannelReader<Location> fixes, long until, CancellationToken ct)
    {
        if (fixes.TryPeek(out _))
        {
            return true;
        }
        var remaining = Stopwatch.GetElapsedTime(Stopwatch.GetTimestamp(), until);
        if (remaining <= TimeSpan.Zero)
        {
            return false;
        }
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        cts.CancelAfter(remaining);
        try
        {
            return await fixes.WaitToReadAsync(cts.Token);
        }
        catch (OperationCanceledException) when (!ct.IsCancellationRequested)
        {
            return false;
        }
    }

    /// GeoClue has no geocoder.
    public Task<Address?> ReverseGeocodeAsync(double lat, double lon, CancellationToken ct) =>
        Task.FromResult<Address?>(null);

    public Task<GeocodeResult?> GeocodeAsync(string query, CancellationToken ct) =>
        throw new LocationException(LocationError.PlatformUnsupported);
}
#endif
