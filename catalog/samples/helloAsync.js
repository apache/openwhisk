/**
 * word count utility, coded as an asynchronous action for pedagogical
 * purposes
 */
function wc(params) {
    var str = params.payload;
    var words = str.split(" ");
    var count = words.length;
    console.log("The message '"+str+"' has", count, 'words');
    whisk.done({count: count});
}

function main(params) {
    setTimeout(function() {
        wc(params);
    }, 100);

    return whisk.async();
}
