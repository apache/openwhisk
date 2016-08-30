/**
 *  word count utility
 */
function main(params) {
    var str = params.payload.toString();
    var words = str.split(" ");
    var count = words.length;
    console.log("The message '"+str+"' has", count, 'words');
    return { count: count };
}
