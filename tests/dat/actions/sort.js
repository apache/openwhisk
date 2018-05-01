// Licensed to the Apache Software Foundation (ASF) under one or more contributor
// license agreements; and to You under the Apache License, Version 2.0.

/**
 * Sort a set of lines.
 * @param lines An array of strings to sort.
 */
function main(msg) {
    var lines = msg.lines || [];
    //console.log('sort got ' + lines.length + ' lines');
    console.log('sort input msg: ' + JSON.stringify(msg));
    console.log('sort before: ' + lines);
    lines.sort();
    console.log('sort after: ' + lines);
    return {lines: lines, length: lines.length};
}
