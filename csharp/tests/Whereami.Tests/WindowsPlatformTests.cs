#if WINDOWS
using System.Runtime.InteropServices;

namespace Whereami.Tests;

public class WindowsPlatformTests
{
    [Fact]
    public void AccessDenied() =>
        Assert.Equal(LocationError.PermissionDenied, WindowsPlatform.MapException(new UnauthorizedAccessException()));

    [Fact]
    public void AccessDeniedHResult() =>
        Assert.Equal(LocationError.PermissionDenied, WindowsPlatform.MapException(new COMException("x", unchecked((int)0x80070005))));

    [Fact]
    public void TimeoutHResult() =>
        Assert.Equal(LocationError.Timeout, WindowsPlatform.MapException(new COMException("x", unchecked((int)0x800705B4))));

    [Fact]
    public void Canceled() =>
        Assert.Equal(LocationError.Timeout, WindowsPlatform.MapException(new TaskCanceledException()));

    [Fact]
    public void Other() =>
        Assert.Equal(LocationError.LocationUnavailable, WindowsPlatform.MapException(new COMException("x", unchecked((int)0x80004005))));
}
#endif
