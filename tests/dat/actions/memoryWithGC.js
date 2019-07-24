/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

'use strict';

// Usually, Linux systems have a page size of 4096 byte
// The actual value can be obtained with `getconf PAGESIZE`
const pageSizeInB = 4096;

// This array will be used to store all allocated blocks
// such that they won't be garbage collected
let blocks = [];

// Allocates a byte array that has page size
function allocateMemoryBlock(sizeInB) {
    return new Uint8Array(sizeInB);
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
// * The memory is allocated in smaller blocks instead of
//   allocating one large block of memory to prevent
//   virtual OOM
// * Size of allocated blocks is a multiple of page size
// * The number of allocated blocks has an upper bound
//   because a reference to each block is stored in an
//   array. If the number of blocks gets too high, the
//   resulting array grows so large that its contribution
//   to memory consumption causes trouble.
//   For this reason, the block size is adjusted to
//   limit the number of blocks. The resulting allocation
//   granularity can cause a slight over-consumption of
//   memory. That's why the upper limit must be selected
//   carefully.
// * Fill randomly to prevent memory deduplication
function eat(memoryInMiB) {
    const memoryInB = memoryInMiB * 1024 * 1024;
    const memoryInPages = Math.ceil(memoryInB / pageSizeInB);
    console.log('helloEatMemory: memoryInB=' + memoryInB + ', memoryInPages=' + memoryInPages);

    let blockSizeInB = pageSizeInB;
    let memoryInBlocks = memoryInPages;
    let pagesPerBlock = 1;
    const maxBlocks = 8192;
    if (memoryInPages > maxBlocks) {
        pagesPerBlock = Math.ceil(memoryInB / (maxBlocks * pageSizeInB));
        blockSizeInB = pagesPerBlock * pageSizeInB;
        memoryInBlocks = Math.ceil(memoryInB / blockSizeInB);
    }
    console.log('helloEatMemory: pagesPerBlock=' + pagesPerBlock + ', blockSizeInB=' + blockSizeInB + ', memoryInBlocks=' + memoryInBlocks);

    for (let b = 0; b < memoryInBlocks; b++) {
        let byteArray = allocateMemoryBlock(blockSizeInB);
        fillMemoryPage(byteArray);
        blocks.push(byteArray);
    }
    console.log('helloEatMemory: blocks.length=' + blocks.length);
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
    blocks = [];
    global.gc();

    return {msg: 'OK, buffer of size ' + msg.payload + ' MB has been filled.'};
}

