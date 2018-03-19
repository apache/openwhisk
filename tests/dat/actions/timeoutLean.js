/**
 * Node.js based OpenWhisk action that waits for the specified number
 * of milliseconds before returning. Uses a timer instead of a busy loop.
 * The function actually waits slightly longer than requested.
 *
 * @param parm Object with Number property timeoutInMs
 * @returns Object with String property msg describing how long the function waited
 */
function main(parm) {
    console.log("Specified timeout is " + parm.timeoutInMs + " ms.")

    return new Promise(function(resolve, reject) {
        setTimeout(function () {
            const result = { msg: "Terminated successfully after at least " + parm.timeoutInMs + " ms." }
            console.log(result.msg)
            resolve(result)
        }, parm.timeoutInMs)
    })
}
