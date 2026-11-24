package whereami.cli;

/** What to print and which exit code to return. */
record Outcome(String stdout, String stderr, int exitCode) {}
