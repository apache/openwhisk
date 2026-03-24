use std::io::{self, Read, Write};

fn fib(n: i32) -> i32 {
    if n <= 1 {
        return n;
    }
    fib(n - 1) + fib(n - 2)
}

fn main() {
    let mut input = String::new();
    io::stdin().read_to_string(&mut input).unwrap_or_default();
    let trimmed = input.trim();

    let n = trimmed.parse::<i32>().unwrap_or(10);

    let res = fib(n);
    io::stdout().write_all(b"{ \"res\": ").unwrap();
    io::stdout().write_all(res.to_string().as_bytes()).unwrap();
    io::stdout().write_all(b" }").unwrap();
}