/**
 * Splits a string into an array of strings
 * Return lines in an array.
 * @param payload A string.
 * @param separator The character, or the regular expression, to use for splitting the string
 */
function main(msg) {
    var separator = msg.separator || /\r?\n/;
    var payload = msg.payload.toString();
    var lines = payload.split(separator);
    var retn = {lines: lines, payload: msg.payload};
    console.log('split: returning ' + JSON.stringify(retn));
    return retn;
}
