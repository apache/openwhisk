function main() {

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
          resolve({ timedout: true });
       }, timeleft - 500);
    })

}
