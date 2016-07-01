/**
 * Return a hello message as an array of strings. This demonstrates the use of returning
 * a Promise for asynchronous actions.
 *
 * @param name A person's name.
 * @param place Where the person is from.
 */
function main(params) {
    return new Promise(function(resolve, reject) {
        whisk.invoke({
            name : '/whisk.system/samples/greeting',
            parameters : {
                name : params.name,
                place : params.place
            },
            blocking : true,
            next : function(error, activation) {
                console.log('activation:', activation);
                if (!error) {
                    var payload = activation.result.payload.toString();
                    var lines = payload.split(' ');
                    resolve({ lines: lines });
                } else {
                    console.log('error:', error);
                    reject(error);
                }
            }
        });
    });
}
