function wc(msg) {
    var str = msg.payload.toString();
    var words = str.split(" ");
    var count = words.length;
    console.log("The message '"+str+"' has", count, 'words');
    whisk.done({ count: count });
    whisk.done({ count: -1 });
}

function main(msg) {
    setTimeout(function() {
        wc(msg);
    }, 100);

    return whisk.async();
}
