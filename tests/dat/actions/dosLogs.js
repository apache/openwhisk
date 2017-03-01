function main(msg) {
    // Each log line with 16 characters (new line character counts)
    var lines = msg.payload / 16 || 1;
    for(var i = 1; i <= lines; i++) {
        console.log("123456789abcdef");
    }
    return {msg: lines};
}
