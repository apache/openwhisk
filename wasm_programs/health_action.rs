use std::io::{self, Read, Write};

fn main() {
    let mut input = String::new();
    io::stdin().read_to_string(&mut input).unwrap_or_default();
    let trimmed = input.trim();
    if trimmed.is_empty() {
        io::stdout().write_all(b"{}").unwrap();
    } else {
        io::stdout().write_all(trimmed.as_bytes()).unwrap();
    }
}
