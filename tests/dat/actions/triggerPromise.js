function main() {
    return new Promise(function (resolve, reject) {
        whisk.trigger({
                name: 'UnitTestTrigger'
            })
            .then(function (result) {
                resolve(result);
            })
            .catch(function (reason) {
                reject(reason);
            });
    });
}
