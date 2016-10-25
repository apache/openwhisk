function main(params) {
    // invoke action named ThreeSecondRule providing a callback function
    // resolve if the invoke behaves as expected
    // reject otherwise
    var resolveOrRejectPromise = new Promise(function (resolve, reject) {
        whisk.invoke({
            name: 'ThreeSecondRule',
            blocking: true,
            parameters: {
                resolveOrReject: params.resolveOrReject
            },
            next: function (err, activation) {
                if (params.resolveOrReject === 'resolve') {
                    if (err === undefined) {
                        // this is what we expecting
                        resolve(activation);
                    } else {
                        reject({
                            error: err,
                            activation: activation
                        });
                    }
                } else {
                    if (err !== undefined) {
                        // this is what we were expecting
                        reject(err);
                    } else {
                        resolve(err);
                    }
                }
            }
        });
    });

    return resolveOrRejectPromise;
}
