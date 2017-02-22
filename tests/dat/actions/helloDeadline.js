function main(args) {

    var deadline = process.env['__OW_DEADLINE']
    var timeleft = deadline - new Date().getTime()
    console.log("deadline in " + timeleft + " msecs");

    var timer = function () {
       var timeleft = deadline - new Date().getTime()
       console.log("deadline in " + timeleft + " msecs");
    }
    var alarm = setInterval(timer, 1000);

    return new Promise(function(resolve, reject) {
       setTimeout(function() {
          clearInterval(alarm);
          if (args.forceHang) {
              // do not resolve the promise and make the action timeout
          } else {
              resolve({ timedout: true });
          }
       }, timeleft - 500);
    })

}
