function main(params) {
    var greeting = "Hello" + (params.text || "stranger") + "!";
    console.log(greeting);
    return { payload: greeting };
}
