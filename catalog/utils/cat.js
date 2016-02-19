/**
 * Equivalent to unix cat command.
 * Return all the lines in an array. All other fields in the input message are stripped.
 * @param lines An array of strings.
 * @param payload A newline separated string. (Only used if the lines parameter is empty.)
 */
function main(msg) {
    var lines = null;
    if (msg.lines) {
        lines = msg.lines;
    } else if (msg.payload) {
        lines = msg.payload.split(/\r?\n/);
    } else {
        lines = [];
    }
    var retn = {lines: lines, payload: lines.join("\n")};
    console.log('cat: returning ' + JSON.stringify(retn));
    return retn;
}


