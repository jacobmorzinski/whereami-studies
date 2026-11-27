#if !WINDOWS
using System.Diagnostics;
using System.Threading.Channels;

namespace Whereami.Tests;

public class LinuxPlatformTests
{
    [Theory]
    [InlineData("org.freedesktop.DBus.Error.AccessDenied", LocationError.PermissionDenied)]
    [InlineData("org.freedesktop.DBus.Error.ServiceUnknown", LocationError.LocationUnavailable)]
    [InlineData("org.freedesktop.DBus.Error.Failed", LocationError.LocationUnavailable)]
    [InlineData("", LocationError.LocationUnavailable)]
    [InlineData(null, LocationError.LocationUnavailable)]
    public void ErrorNames(string? name, LocationError expected) =>
        Assert.Equal(expected, LinuxPlatform.FromDBusErrorName(name));

    private static Location Fix(double accuracy) => new(accuracy, 0, accuracy);

    private static long In(TimeSpan span) => Stopwatch.GetTimestamp() + (long)(span.TotalSeconds * Stopwatch.Frequency);

    private static readonly TimeSpan Far = TimeSpan.FromSeconds(60);

    private static (Channel<Location> channel, ChannelReader<Location> reader) NewChannel()
    {
        var channel = Channel.CreateUnbounded<Location>();
        return (channel, channel.Reader);
    }

    [Fact]
    public async Task PrefersFineAfterCoarse()
    {
        var (ch, reader) = NewChannel();
        ch.Writer.TryWrite(Fix(25000));
        ch.Writer.TryWrite(Fix(10));
        var best = await LinuxPlatform.BestFixAsync(reader, TimeSpan.FromMilliseconds(20), In(Far), CancellationToken.None);
        Assert.Equal(Fix(10), best);
    }

    [Fact]
    public async Task SingleFixReturnsAfterGrace()
    {
        var (ch, reader) = NewChannel();
        ch.Writer.TryWrite(Fix(5));
        var grace = TimeSpan.FromMilliseconds(50);
        var sw = Stopwatch.StartNew();
        var best = await LinuxPlatform.BestFixAsync(reader, grace, In(Far), CancellationToken.None);
        Assert.Equal(Fix(5), best);
        Assert.True(sw.Elapsed >= grace - TimeSpan.FromMilliseconds(5), $"returned early after {sw.Elapsed}");
        Assert.True(sw.Elapsed < TimeSpan.FromSeconds(1), $"waited {sw.Elapsed}");
    }

    [Fact]
    public async Task TieKeepsFirst()
    {
        var (ch, reader) = NewChannel();
        var first = new Location(1, 1, 10);
        ch.Writer.TryWrite(first);
        ch.Writer.TryWrite(new Location(2, 2, 10));
        var best = await LinuxPlatform.BestFixAsync(reader, TimeSpan.FromMilliseconds(20), In(Far), CancellationToken.None);
        Assert.Equal(first, best);
    }

    [Fact]
    public async Task IgnoresFixAfterWindow()
    {
        var (ch, reader) = NewChannel();
        ch.Writer.TryWrite(Fix(100));
        var late = Task.Run(async () =>
        {
            await Task.Delay(200);
            ch.Writer.TryWrite(Fix(1));
        });
        var best = await LinuxPlatform.BestFixAsync(reader, TimeSpan.FromMilliseconds(20), In(Far), CancellationToken.None);
        Assert.Equal(Fix(100), best);
        await late;
    }

    [Fact]
    public async Task ClampsGraceToDeadline()
    {
        var (ch, reader) = NewChannel();
        ch.Writer.TryWrite(Fix(5));
        var sw = Stopwatch.StartNew();
        var best = await LinuxPlatform.BestFixAsync(reader, TimeSpan.FromMilliseconds(250), In(TimeSpan.FromMilliseconds(30)), CancellationToken.None);
        Assert.Equal(Fix(5), best);
        Assert.True(sw.Elapsed < TimeSpan.FromMilliseconds(150), $"waited {sw.Elapsed}");
    }

    [Fact]
    public async Task NullWhenNoFixByDeadline()
    {
        var (_, reader) = NewChannel();
        var sw = Stopwatch.StartNew();
        var best = await LinuxPlatform.BestFixAsync(reader, TimeSpan.FromMilliseconds(250), In(TimeSpan.FromMilliseconds(50)), CancellationToken.None);
        Assert.Null(best);
        Assert.True(sw.Elapsed >= TimeSpan.FromMilliseconds(40), $"returned early after {sw.Elapsed}");
        Assert.True(sw.Elapsed < TimeSpan.FromMilliseconds(500), $"waited {sw.Elapsed}");
    }

    [Fact]
    public async Task NullWhenWriterCompletedWithoutFix()
    {
        var (ch, reader) = NewChannel();
        ch.Writer.Complete();
        var best = await LinuxPlatform.BestFixAsync(reader, TimeSpan.FromMilliseconds(20), In(Far), CancellationToken.None);
        Assert.Null(best);
    }

    [Fact]
    public async Task CancellationThrows()
    {
        var (_, reader) = NewChannel();
        using var cts = new CancellationTokenSource(30);
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() =>
            LinuxPlatform.BestFixAsync(reader, TimeSpan.FromMilliseconds(20), In(Far), cts.Token));
    }
}
#endif
