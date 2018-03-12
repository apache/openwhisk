function sleep(milliseconds) {
    var start = new Date().getTime();
    while (true) {
        var now = new Date().getTime();
        if ((now - start) > milliseconds) {
            break;
        }
    }
}

function main(msg) {
    var busytime = msg.busytime || 100;
    sleep(busytime);
    var res = '[OK] message terminated successfully after ' + busytime + ' ms.';
    console.log('dosCPU', 'result:', res);
    return { msg: res };
}
