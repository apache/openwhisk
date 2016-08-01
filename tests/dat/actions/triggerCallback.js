function main(params) {
    return new Promise(function (resolve, reject) {
        whisk.trigger({
            name: 'UnitTestTrigger',
            next: function (error, activation) {
                if (error !== undefined) {
                    reject(error);
                } else {
                    resolve(activation);
                }
            }
        });
    });
}
