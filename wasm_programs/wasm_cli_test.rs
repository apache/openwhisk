use std::env;

fn main() {
    // Collect command-line arguments into a vector of strings.
    // The first argument is the path to the executable itself.
    let args: Vec<String> = env::args().collect();

    // Print all arguments using debug formatting
    println!("All arguments: {:?}", args);
}
