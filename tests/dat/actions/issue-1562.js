// We expect this action to always result in errored activations.
function main(args) {
    return new Promise((resolve, reject) => {
        reject();
    });
}
