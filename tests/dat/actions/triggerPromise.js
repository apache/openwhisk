function main(params) {
    return new Promise(function (resolve, reject) {
        whisk.trigger({
                name: params.triggerName
            })
            .then(function (result) {
                resolve(result);
            })
            .catch(function (reason) {
                reject(reason);
            });
    });
}
