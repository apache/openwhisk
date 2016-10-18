function main(params) {
    var str = params.payload;

    return whisk.invoke({
        name: '!',    // Invalid action name
        parameters: {
            payload: str
        },
        blocking: true
    }).then(function (activation) {
        return {};
    });
}
