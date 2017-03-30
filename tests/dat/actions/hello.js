/**
 * Hello, world.
 */
function main(params) {
    greeting = 'hello, ' + params.payload + '!'
    console.log(greeting);
    return {payload: greeting}
}
