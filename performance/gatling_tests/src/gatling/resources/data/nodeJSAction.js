function main(params) {
    var greeting = "Hello stranger!";
    if (params.text) {
        greeting = "Hello " + params.text + "!";
    }
    console.log(greeting);
    return { payload: greeting };
}
