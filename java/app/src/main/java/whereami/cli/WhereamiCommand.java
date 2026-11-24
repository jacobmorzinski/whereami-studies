package whereami.cli;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import whereami.platform.Location;
import whereami.platform.Platform;

/**
 * The command line, declared with picocli annotations. picocli fills the
 * annotated fields from the arguments, then calls {@link #call()}. Its return
 * value becomes the exit code. Parse errors never reach {@code call()}:
 * picocli prints them with the usage text and exits 2.
 */
@Command(
        name = "whereami",
        versionProvider = VersionProvider.class,
        sortOptions = false,
        description = "Get your current location using native OS location services, "
                + "or look up an address or a pair of coordinates.")
final class WhereamiCommand implements Callable<Integer> {
    private final Platform platform;
    private final Duration timeout;

    @Spec
    CommandSpec spec; // injected by picocli: gives access to getOut()/getErr()

    @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help and exit.")
    boolean help;

    @Option(names = {"-v", "--version"}, versionHelp = true, description = "Print the version and exit.")
    boolean version;

    @Option(names = "--json", description = "Print JSON instead of text.")
    boolean json;

    /** --mock and --at are mutually exclusive; picocli enforces that for the group. */
    @ArgGroup(exclusive = true, multiplicity = "0..1")
    Coords coords;

    static final class Coords {
        @Option(names = "--mock", paramLabel = "LAT,LON", converter = Coordinates.Converter.class,
                description = "Use these coordinates instead of location services (no address lookup).")
        Location mock;

        @Option(names = "--at", paramLabel = "LAT,LON", converter = Coordinates.Converter.class,
                description = "Look up the address at these coordinates.")
        Location at;
    }

    @Parameters(arity = "0..*", paramLabel = "ADDRESS", description = "An address to look up (macOS only).")
    List<String> address = new ArrayList<>();

    WhereamiCommand(Platform platform, Duration timeout) {
        this.platform = platform;
        this.timeout = timeout;
    }

    /** Builds the picocli runner. {@code @file} expansion is off, so "@home" is an address. */
    static CommandLine commandLine(Platform platform, Duration timeout) {
        return new CommandLine(new WhereamiCommand(platform, timeout)).setExpandAtFiles(false);
    }

    @Override
    public Integer call() {
        Outcome outcome = App.run(toRequest(), platform, timeout);
        var out = spec.commandLine().getOut();
        var err = spec.commandLine().getErr();
        out.print(outcome.stdout());
        out.flush();
        err.print(outcome.stderr());
        err.flush();
        return outcome.exitCode();
    }

    /** The rules picocli can't express declaratively. A ParameterException is a usage error (exit 2). */
    Request toRequest() {
        if (!address.isEmpty()) {
            if (address.getFirst().equals("completions")) {
                throw usage("completions are not available in this port");
            }
            String query = String.join(" ", address).strip();
            if (query.isEmpty()) {
                throw usage("the address is empty");
            }
            if (coords != null) {
                throw usage("an address can't be combined with --mock or --at");
            }
            return new Request(new Mode.Lookup(query), json);
        }
        if (coords == null) {
            return new Request(new Mode.Current(), json);
        }
        if (coords.mock != null) {
            return new Request(new Mode.Mock(coords.mock), json);
        }
        Location at = coords.at;
        if (Math.abs(at.latitude()) > 90 || Math.abs(at.longitude()) > 180) {
            throw usage("--at must be within latitude -90..90 and longitude -180..180");
        }
        return new Request(new Mode.At(at), json);
    }

    private ParameterException usage(String message) {
        return new ParameterException(spec.commandLine(), message);
    }
}
