package whereami.platform;

import whereami.platform.linux.LinuxPlatform;
import whereami.platform.windows.WindowsPlatform;

/**
 * Picks the implementation for the OS we're running on. Java has no
 * conditional compilation: every backend is compiled on every OS, and the
 * choice is made at run time from the {@code os.name} system property.
 */
public final class Platforms {
    private Platforms() {}

    /** "windows", "macos" or "linux". */
    public static String osName() {
        String name = System.getProperty("os.name", "");
        if (name.startsWith("Windows")) {
            return "windows";
        }
        if (name.startsWith("Mac")) {
            return "macos";
        }
        return "linux";
    }

    public static Platform nativePlatform() {
        return switch (osName()) {
            case "windows" -> new WindowsPlatform();
            case "linux" -> new LinuxPlatform();
            default -> new UnsupportedPlatform();
        };
    }
}
