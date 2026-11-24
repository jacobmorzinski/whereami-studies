module Whereami.Native

open System

/// The implementation for the OS we're running on. Two layers, as in C#:
/// #if WINDOWS — is the Windows code compiled into this build at all?
/// OperatingSystem.IsWindows() — are we actually running on Windows?
let platform () : Platform =
#if WINDOWS
    if OperatingSystem.IsWindows() then Windows.platform else
#endif
    Platform.unsupported
