module Whereami.Native

open System

/// The implementation for the OS we're running on. Two layers, as in C#:
/// #if WINDOWS / #if !WINDOWS — is that OS's code compiled into this build at all?
/// OperatingSystem.IsWindows() / IsLinux() — are we actually running on it?
/// (macOS is still the unsupported stand-in.)
let platform () : Platform =
#if WINDOWS
    if OperatingSystem.IsWindows() then Windows.platform else
#else
    if OperatingSystem.IsLinux() then Linux.platform else
#endif
    Platform.unsupported
