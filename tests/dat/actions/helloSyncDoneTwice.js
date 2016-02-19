function wc(msg) {
    var str = msg.payload.toString();
    var words = str.split(" ");
    var count = words.length;
    console.log("The message '"+str+"' has", count, 'words');
    return whisk.done({count: count});
}

function main(msg) {
    wc(msg);
    return {count: -1};
}
