//! whereami — ask the operating system where you are.

mod app;
mod cli;
mod model;
mod output;
mod platform;

use std::io::Write;

fn main() {
    // On --help/--version this prints and exits 0; on a usage error, exits 2.
    let request = cli::parse(std::env::args_os()).unwrap_or_else(|e| e.exit());

    let outcome = app::run(&request, &*platform::native());

    print!("{}", outcome.stdout);
    eprint!("{}", outcome.stderr);
    let _ = std::io::stdout().flush();
    std::process::exit(outcome.code);
}
