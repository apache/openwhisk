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

var fs = require("fs");

function main(params) {

    var numFiles = params.numFiles;
    var openFiles = [];
    var error = undefined;

    try {
        for (var i = 0; i < numFiles; i++) {
            var fh = fs.openSync("/dev/zero", "r");
            openFiles.push(fh);
        }
    } catch (err) {
        console.log("ERROR: opened files = ", openFiles.length);
        error = err;
    }

    console.log("opened files = ", openFiles.length);

    openFiles.forEach(function(fh) {
        fs.close(fh, (err) => {} );
    })

    if (error === undefined) {
        return {
            filesToOpen : numFiles,
            filesOpen : openFiles.length,
        }
    } else {
        return {
            error : {
                filesToOpen : numFiles,
                filesOpen : openFiles.length,
                message : error
            }
        }
    }
}
