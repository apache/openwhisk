/**
 * Return word count as a binary number. This demonstrates the use of a blocking
 * invoke.
 */
function main(params) {
    var str = params.payload;
    console.log("The payload is '" + str + "'");
    whisk.invoke({
        name : 'wc',
        parameters : {
            payload : str
        },
        blocking : true,
        next : function(error, activation) {
            console.log('activation:', activation);
            if (!error) {
                var wordsInDecimal = activation.result.count;
                var wordsInBinary = wordsInDecimal.toString(2) + ' (base 2)';
                whisk.done({
                    binaryCount : wordsInBinary
                });
            } else {
                console.log('error:', error);
                whisk.error(error);
            }
        }
    });
    return whisk.async();
}
