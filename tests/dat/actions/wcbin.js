/**
 * Return word count as a binary number. This demonstrates the use of a blocking
 * invoke.
 */
var openwhisk = require('openwhisk')

function main(params) {
    var wsk = openwhisk({ignore_certs: true})

    var str = params.payload;
    console.log("The payload is '" + str + "'");

    return wsk.actions.invoke({
        actionName: 'wc',
        params: {
            payload: str
        },
        blocking: true
    }).then(activation => {
        var wordsInDecimal = activation.response.result.count;
        var wordsInBinary = wordsInDecimal.toString(2) + ' (base 2)';
        return {
            binaryCount: wordsInBinary
        };
    });
}
