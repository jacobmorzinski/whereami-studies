package whereami.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import whereami.platform.LocationError;
import whereami.platform.LocationException;
import whereami.platform.Platforms;

class WhereamiCommandTest {
    record Result(int exit, String out, String err) {}

    private final AppTest.Fake fake = new AppTest.Fake();

    private Result run(String... args) {
        var out = new StringWriter();
        var err = new StringWriter();
        CommandLine cli = WhereamiCommand.commandLine(fake, Duration.ofSeconds(1));
        cli.setOut(new PrintWriter(out, true));
        cli.setErr(new PrintWriter(err, true));
        int exit = cli.execute(args);
        return new Result(exit, out.toString(), err.toString());
    }

    @Test
    void versionBothSpellings() {
        for (String flag : new String[] {"--version", "-v"}) {
            var r = run(flag);
            assertEquals(0, r.exit());
            assertEquals("whereami 0.4.0 (" + Platforms.osName() + ")", r.out().strip());
        }
    }

    @Test
    void unknownFlag() {
        var r = run("--bogus");
        assertEquals(2, r.exit());
        assertEquals("", r.out());
    }

    @Test
    void atSignIsAddress() {
        fake.geocode = () -> { throw new LocationException(LocationError.PLATFORM_UNSUPPORTED); };
        assertEquals(1, run("@home").exit());
        assertEquals("@home", fake.lastQuery);
    }

    @Test
    void multiwordAddressJoined() {
        fake.geocode = Optional::empty;
        run("350", "5th", "Ave");
        assertEquals("350 5th Ave", fake.lastQuery);
    }

    @Test
    void completionsReserved() {
        var r = run("completions", "bash");
        assertEquals(2, r.exit());
        assertTrue(r.err().contains("completions are not available in this port"), r.err());
        assertEquals("", r.out());
    }

    @Test
    void atOutOfRange() {
        assertEquals(2, run("--at=91,0").exit());
    }

    @Test
    void atAndMockConflict() {
        assertEquals(2, run("--at=1,2", "--mock=1,2").exit());
    }

    @Test
    void addressAndMockConflict() {
        assertEquals(2, run("--mock=1,2", "Paris").exit());
    }

    @Test
    void emptyAddress() {
        assertEquals(2, run("   ").exit());
    }

    @Test
    void jsonUsageErrorPrintsNothing() {
        var r = run("--json", "--mock=x");
        assertEquals(2, r.exit());
        assertEquals("", r.out());
    }
}
