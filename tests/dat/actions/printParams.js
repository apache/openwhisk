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

/**
 * Print the parameters to the console, sorted alphabetically by key
 */
function main(params) {
    var sep = '';
    var retn = {};
    var keys = [];

    for (var key in params) {
        if (params.hasOwnProperty(key)) {
            keys.push(key);
        }
    }

    keys.sort();
    for (var i in keys) {
        var key = keys[i];
        var value = params[key];
        console.log(sep + 'params.' + key + ':', value);
        sep = ' ';
        retn[key] = value;
    }

    return {params: retn};
}
