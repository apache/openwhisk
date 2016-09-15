/**
 * An action that invokes itself recursively, programmatically using the whisk
 * Javascript API.
 */
function main(params) {
    var n = parseInt(params.n);
    console.log(n);
    if (n === 0) {
        console.log('Happy New Year!');
    } else if (n > 0) {
        return whisk.invoke({
            name : 'countdown',
            parameters : {
                n : n - 1
            }
        });
    }
}
