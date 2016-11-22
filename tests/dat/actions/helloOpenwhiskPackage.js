var openwhisk = require('openwhisk')

function main(args) {
    var wsk = openwhisk({ignore_certs: args.ignore_certs})

    return new Promise(function (resolve, reject) {
        return wsk.actions.list().then(list => {
            console.log("action list has this many actions:", list.length)
            if (args.name) {
                console.log('deleting')
                return wsk.actions.delete({actionName: args.name}).then(result => {
                    resolve({delete: true})
                }).catch(function (reason) {
                    console.log('error', reason)
                    reject(reason)
                })
            } else {
                console.log('ok')
                resolve({list: true})
            }
        }).catch(function (reason) {
            console.log('error', reason)
            reject(reason);
        })
    })
}
