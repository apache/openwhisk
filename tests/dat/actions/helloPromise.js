function main(args) {
    return new Promise(function(resolve, reject) {
        setTimeout(function() {
            resolve({
                done : true
            });
        }, 2000);
    })
}
