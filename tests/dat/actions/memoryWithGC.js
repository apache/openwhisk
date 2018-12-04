// Licensed to the Apache Software Foundation (ASF) under one or more contributor
// license agreements; and to You under the Apache License, Version 2.0.

'use strict';

// Usually, Linux systems have a page size of 4096 byte
// The actual value can be obtained with `getconf PAGESIZE`
var pageSizeInB = 4096;

// This array will be used to store all allocated pages
// such that they won't be garbage collected
var pages = [];

// Allocates a byte array that has page size
function allocateMemoryPage() {
    return new Uint8Array(pageSizeInB);
}

// Returns a random number between 0 (inclusive) and
// the specified value maxExclusive (exclusive)
function randomUnsigned(maxExclusive) {
    return Math.floor(Math.random() * maxExclusive)
}

// Fills the first 4 bytes of the passed byte array with random
// numbers
function fillMemoryPage(byteArray) {
    for (var i = 0; (i < 4) && (i < pageSizeInB); i++) {
        byteArray[i] = randomUnsigned(256)
    }
}

// Consumes the specified amount of physical memory
// * The memory is allocated page-wise instead of allocating
//   a large block of memory to prevent virtual OOM
// * Fill randomly to prevent memory deduplication
function eat(memoryInMiB) {
    var memoryInB = memoryInMiB * 1024 * 1024;
    var pageSizeInB = 4096;
    var memoryInPages = Math.ceil(memoryInB / pageSizeInB);

    for(var p = 0; p < memoryInPages; p++) {
        var byteArray = allocateMemoryPage()
        fillMemoryPage(byteArray)
        pages.push(byteArray)
    }
}

function main(msg) {
    console.log('helloEatMemory: memory ' + msg.payload + 'MB');
    global.gc();
    eat(msg.payload);

    console.log('helloEatMemory: completed allocating memory');
    console.log(process.memoryUsage());

    return {msg: 'OK, buffer of size ' + msg.payload + ' MB has been filled.'};
}