function main(args) {
    // it should be OK to use 'return' with whisk.error.
    return whisk.error("This error thrown on purpose by the action.");
}
