use std::io::{self, Write};
use std::env;

fn main() {
    let args: Vec<String> = env::args().collect();

    if args.len() < 2 {
        io::stdout().write_all(b"{}").unwrap();
    } else {
        io::stdout().write_all(args[1].as_bytes()).unwrap();
    }
}
