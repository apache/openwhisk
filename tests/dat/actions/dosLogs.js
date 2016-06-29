function main(msg) {
    // Each log line with 16 characters
    var lines = msg.payload / 16 || 1
    for(var i = 0; i <= lines; i++) {
        console.log("0123456789abcdef");
    }
    return {msg: lines};
}
