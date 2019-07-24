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
 * Splits a string into an array of strings
 * Return lines in an array.
 * @param payload A string.
 * @param separator The character, or the regular expression, to use for splitting the string
 */
function main(msg) {
    var separator = msg.separator || /\r?\n/;
    var payload = msg.payload.toString();
    var lines = payload.split(separator);
    var retn = {lines: lines, payload: msg.payload};
    console.log('split: returning ' + JSON.stringify(retn));
    return retn;
}
