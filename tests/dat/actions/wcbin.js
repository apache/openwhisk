/**
 * Return word count as a binary number. This demonstrates the use of a blocking
 * invoke.
 */
function main(params) {
    var str = params.payload;
    console.log("The payload is '" + str + "'");

    return whisk.invoke({
        name: 'wc',
        parameters: {
            payload: str
        },
        blocking: true
    }).then(function (activation) {
        console.log('activation:', activation);
        var wordsInDecimal = activation.result.count;
        var wordsInBinary = wordsInDecimal.toString(2) + ' (base 2)';
        return {
            binaryCount: wordsInBinary
        };
    });
}
