package whereami.cli;

import whereami.platform.Platforms;

/** Entry point: pick the OS backend, let picocli run the command, exit with its code. */
public final class Main {
    private Main() {}

    public static void main(String[] args) {
        int exitCode = WhereamiCommand.commandLine(Platforms.nativePlatform(), App.TIMEOUT).execute(args);
        System.exit(exitCode);
    }
}
