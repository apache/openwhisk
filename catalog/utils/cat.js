/**
 * Equivalent to unix cat command.
 * Return all the lines in an array. All other fields in the input message are stripped.
 * @param lines An array of strings.
 */
function main(msg) {
    var lines = msg.lines || [];
    var retn = {lines: lines, payload: lines.join("\n")};
    console.log('cat: returning ' + JSON.stringify(retn));
    return retn;
}


