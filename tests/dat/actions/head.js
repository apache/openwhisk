/**
 * Return the first num lines of an array.
 * @param lines An array of strings.
 * @param num Number of lines to return.
 */
function main(msg) {
    var lines = msg.lines || [];
    var num = msg.num || 1;
    var head = lines.slice(0, num);
    console.log('head get first ' + num + ' lines of ' + lines + ': ' + head);
    return {lines: head, num: num};
}


