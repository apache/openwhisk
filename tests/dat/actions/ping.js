function main(msg) {
    var hostToPing = msg.payload;
    console.log('Pinging to ' + hostToPing);

    var spawn = require('child_process').exec;
    var promise = new Promise(function(resolve, reject) {
        var child = spawn('ping -c 3 ' + hostToPing);

        var tmp = {stdout: "", stderr: ""};

        child.stdout.on('data', function (data) {
            tmp.stdout = tmp.stdout + data;
        });

        child.stderr.on('data', function (data) {
            tmp.stderr = tmp.stderr + data;
        });

        child.on('close', function () {
            console.log('stdout', tmp.stdout);
            console.log('stderr', tmp.stderr);
            resolve(tmp);
        });
    });

    return promise;
}
