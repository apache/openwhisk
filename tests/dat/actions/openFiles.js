var fs = require("fs");

function main(params){

    var numFiles = params.numFiles;
    var openFiles = [];
    try {
        for (var i=0; i < numFiles; i++) {
            var fh = fs.openSync("/dev/zero", "r");
            openFiles.push(fh);
        }
    } catch (err) {
        console.log("ERROR: opened files = ", openFiles.length);
        throw(err);
    }
    console.log("opened files = ", openFiles.length);

    openFiles.forEach(function(fh){
        fs.close(fh);
    })
    return { filesToOpen: numFiles, filesOpen: openFiles.length}
}
