// Licensed to the Apache Software Foundation (ASF) under one or more contributor
// license agreements; and to You under the Apache License, Version 2.0.

'use strict';

// Usually, Linux systems have a page size of 4096 byte
// The actual value can be obtained with `getconf PAGESIZE`
const pageSizeInB = 4096;

// This array will be used to store all allocated pages
// such that they won't be garbage collected
let pages = [];

// Allocates a byte array that has page size
function allocateMemoryPage() {
    return new Uint8Array(pageSizeInB);
}

// Returns a random number between 0 (inclusive) and
// the specified value maxExclusive (exclusive)
function randomUnsigned(maxExclusive) {
    return Math.floor(Math.random() * maxExclusive);
}

// Fills the first 4 bytes of the passed byte array with random
// numbers
function fillMemoryPage(byteArray) {
    for (let i = 0; (i < 4) && (i < pageSizeInB); i++) {
        byteArray[i] = randomUnsigned(256);
    }
}

// Consumes the specified amount of physical memory
// * The memory is allocated page-wise instead of allocating
//   a large block of memory to prevent virtual OOM
// * Fill randomly to prevent memory deduplication
function eat(memoryInMiB) {
    const memoryInB = memoryInMiB * 1024 * 1024;
    const memoryInPages = Math.ceil(memoryInB / pageSizeInB);
    console.log('helloEatMemory: memoryInB=' + memoryInB + ', memoryInPages='+memoryInPages);

    for(let p = 0; p < memoryInPages; p++) {
        let byteArray = allocateMemoryPage();
        fillMemoryPage(byteArray);
        pages.push(byteArray);
    }
    console.log('helloEatMemory: pages.length=' + pages.length);
}

function main(msg) {
    console.log('helloEatMemory: memory ' + msg.payload + 'MB');
    global.gc();
    eat(msg.payload);

    console.log('helloEatMemory: completed allocating memory');
    console.log(process.memoryUsage());

    // This Node.js code is invoked as Apache OW action
    // We need to explicitly clear the array such that all
    // allocated memory gets garbage collected and
    // we have a "fresh" instance on next invocation
    // Clean up after ourselves such that the warm container
    // does not keep memory
    pages = [];
    global.gc();

    return {msg: 'OK, buffer of size ' + msg.payload + ' MB has been filled.'};
}
