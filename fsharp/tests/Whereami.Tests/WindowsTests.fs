module Whereami.Tests.WindowsTests

// Only the -windows target framework has the Windows code to test.
#if WINDOWS
open System
open System.Runtime.InteropServices
open Xunit
open Whereami

[<Fact>]
let accessDeniedIsPermission () =
    Assert.Equal(PermissionDenied, Windows.mapException (UnauthorizedAccessException()))

[<Fact>]
let accessDeniedHResult () =
    Assert.Equal(PermissionDenied, Windows.mapException (COMException("", 0x80070005)))

[<Fact>]
let timeoutHResult () =
    Assert.Equal(LocationError.Timeout, Windows.mapException (COMException("", 0x800705B4)))

[<Fact>]
let cancellationIsTimeout () =
    Assert.Equal(LocationError.Timeout, Windows.mapException (OperationCanceledException()))

[<Fact>]
let otherIsUnavailable () =
    Assert.Equal(LocationUnavailable, Windows.mapException (COMException("", 0x80004005)))
#endif
