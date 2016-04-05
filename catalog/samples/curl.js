function main(msg) {
    var hostToCurl = msg.payload;
    console.log('Curl to ' + hostToCurl);

    var spawn = require('child_process').exec;
    var child = spawn('curl --connect-timeout 3 ' + hostToCurl);

    var tmp = { stdout : "", stderr : "", code : "undefined" };

    child.stdout.on('data', function (data) {
        tmp.stdout = tmp.stdout + data;
    });

    child.stderr.on('data', function (data) {
        tmp.stderr = tmp.stderr + data;
    });

    child.on('close', function (code) {
        tmp.code = code;
        if (tmp.code == 0){
            console.log(tmp.stdout);
            whisk.done({ msg: tmp.stdout });
        } else {
            console.log(tmp.stderr);
            whisk.done({ msg: tmp.stderr})
        }

    });

    return whisk.async();
}
