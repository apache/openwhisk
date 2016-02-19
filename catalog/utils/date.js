/**
 * Print and return the current date.
 */
function main(params) {
    var now = new Date();
    console.log('It is now ' + now);
    return { date: now };
}
