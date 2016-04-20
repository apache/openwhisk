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
        whisk.invoke({
            name : 'countdown',
            parameters : {
                n : n - 1
            },
            next : function(error, activation) {
                if (!error) {
                    whisk.done();
                } else {
                    whisk.error(error);
                }
            }
        });
        return whisk.async();
    }
}
