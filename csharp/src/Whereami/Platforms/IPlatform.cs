namespace Whereami;

public static class Platforms
{
    /// "windows", "macos" or "linux" — decided at run time.
    public static string OsName =>
        OperatingSystem.IsWindows() ? "windows" : OperatingSystem.IsMacOS() ? "macos" : "linux";
}
