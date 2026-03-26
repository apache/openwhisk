use std::io::{self, Write};
use std::env;

fn fib(n: i32) -> i32 {
    if n <= 1 {
        return n;
    }
    fib(n - 1) + fib(n - 2)
}

fn main() {
    let args: Vec<String> = env::args().collect();

    let n = args[1].parse::<i32>().unwrap_or(10);
    let res = fib(n);
    io::stdout().write_all(b"{ \"res\": ").unwrap();
    io::stdout().write_all(res.to_string().as_bytes()).unwrap();
    io::stdout().write_all(b" }").unwrap();
}