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
            console.log('error:', error, 'activation:', activation);
            if (!error) {
                var wordsInDecimal = activation.result.count;
                var wordsInBinary = wordsInDecimal.toString(2) + ' (base 2)';
                whisk.done({
                    binaryCount : wordsInBinary
                });
            } else
                whisk.error(error);
        }
    });
    return whisk.async();
}
