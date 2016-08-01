/*
 * After 3 seconds, either resolve or reject depending on the value
 * of the resolveOrReject parameter.
 *
 * This craziness is so that the whisk.invoke() promise support can be
 * thoroughly tested (for invocations that both resolve and reject).
 */
function main(params) {
    return new Promise(function (resolve, reject) {
        setTimeout(function () {
            if (params.resolveOrReject === 'resolve') {
                console.log('Resolving');
                resolve({
                    message: 'Three second rule!'
                });
            } else {
                console.log('Rejecting');
                reject({
                    message: 'Three second rule!'
                });
            }
        }, 3000);
    });
}
