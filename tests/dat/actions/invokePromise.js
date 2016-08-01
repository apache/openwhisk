function main(params) {
    // invoke action named ThreeSecondRule utilizing the returned promise
    // resolve if the invoke resolves or rejects as expected
    // reject otherwise
    var resolveOrRejectPromise = new Promise(function (resolve, reject) {
        whisk.invoke({
                name: 'ThreeSecondRule',
                blocking: true,
                parameters: {
                    resolveOrReject: params.resolveOrReject
                }
            })
            .then(function (resolution) {
                // make sure we were expecting this to be resolved
                if (params.resolveOrReject === 'resolve') {
                    // this is what we expecting
                    resolve(resolution);
                } else {
                    reject(resolution);
                }
            })
            .catch(function (rejection) {
                // make sure we were expecting this to be rejected
                if (params.resolveOrReject === 'reject') {
                    // this is what we were expecting
                    resolve(rejection);
                } else {
                    reject(rejection);
                }
            });
    });

    return resolveOrRejectPromise;
}
