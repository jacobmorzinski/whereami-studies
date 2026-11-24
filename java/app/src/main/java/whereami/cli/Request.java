package whereami.cli;

/** A fully validated command line. */
record Request(Mode mode, boolean json) {}
