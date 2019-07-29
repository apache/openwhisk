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
