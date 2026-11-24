package whereami.cli;

import whereami.platform.Location;

/**
 * What the user asked for. A sealed interface with record cases is Java's
 * version of a Rust enum with data: the compiler knows every case, so a
 * {@code switch} over a {@code Mode} needs no {@code default}.
 */
sealed interface Mode {
    /** Ask the OS where we are. */
    record Current() implements Mode {}

    /** --mock: use these coordinates, no OS calls at all. */
    record Mock(Location location) implements Mode {}

    /** --at: use these coordinates and look up their address. */
    record At(Location location) implements Mode {}

    /** Positional words: look up this address. */
    record Lookup(String query) implements Mode {}
}
