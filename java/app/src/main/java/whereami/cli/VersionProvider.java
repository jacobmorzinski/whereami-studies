package whereami.cli;

import picocli.CommandLine;
import whereami.platform.Platforms;

/** The --version text. The OS is only known at run time, hence a provider, not a constant. */
final class VersionProvider implements CommandLine.IVersionProvider {
    static final String VERSION = "0.4.0";

    @Override
    public String[] getVersion() {
        return new String[] {"whereami " + VERSION + " (" + Platforms.osName() + ")"};
    }
}
