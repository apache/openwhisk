function main(params) {
    return new Promise(function (resolve, reject) {
        whisk.trigger({
            name: params.triggerName,
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
