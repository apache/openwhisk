function sleep(milliseconds) {
  var start = new Date().getTime();
  while (true) {
    var now = new Date().getTime();
    if ((now - start) > milliseconds){
      break;
    }
  }
}

function main(msg) {
    console.log('dosTimeout', 'timeout ' + msg.payload+'');
    var n = msg.payload;
    sleep(n);
    var res = "[OK] message terminated successfully after " + msg.payload + " milliseconds.";
    console.log('dosTimeout', 'result:', res);
    return {msg: res};
}
