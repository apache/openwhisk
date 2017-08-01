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

var logger = {
    info : console.log,
    error : console.log
}

function malformed() {
    init({
        main : 'main',
        code : 'x',
    });
    run({
        payload : "hi there sync"
    });
}

function sync() {
    init({
        main : 'main',
        code : 'function foo(msg) { var wc = msg.payload.split(" ").length; console.log("wc:", wc); return { wc: wc}; } function main(msg) { return foo(msg); }',
    });
    run({
        payload : "hi there sync"
    });
}

function async() {
    init({
        main : 'main',
        code : 'function foo(msg) { var wc = msg.payload.split(" ").length; console.log("wc:", wc); whisk.done({wc : wc}); } function main(msg) { setTimeout(function() { foo(msg) }, 1000); return whisk.async(); }',
    });
    run({
        payload : "hi there a sync"
    });
}

function syncTwice() {
    init({
        main : 'main',
        code : 'function foo(msg) { var wc = msg.payload.split(" ").length; console.log("wc:", wc); return whisk.done({wc: wc}); } function main(msg) { return foo(msg); }',
    });
    run({
        payload : "hi there sync twice :)"
    });
}

function asyncTwice() {
    init({
        main : 'main',
        code : 'function foo(msg) { var wc = msg.payload.split(" ").length; console.log("wc:", wc); whisk.done({wc: wc}); whisk.done({bad: 2*wc}); } function main(msg) { setTimeout(function() { foo(msg) }, 1000); return whisk.async(); }',
    });
    run({
        payload : "hi there a sync twice :)"
    });
}

function init(msg) {
    request({
        url : 'http://localhost:8080/' + 'init',
        method : 'post',
        parameters : {
            value : msg
        }
    }, function(response) {
        console.log(response);
    }, logger);
}

function run(msg) {
    request({
        url : 'http://localhost:8080/' + 'run',
        method : 'post',
        parameters : {
            value : msg
        }
    }, function(response) {
        console.log(response);
    }, logger);
}

function request(packet, next, logger) {
    var http = require('request');
    var btoa = require('btoa');

    var options = {
        method: packet.method,
        url : packet.url,
        agentOptions : {
            rejectUnauthorized : false
        },
        headers : {
            'Content-Type' : 'application/json',
        },
        json : packet.parameters,
    };

    if (packet.auth) {
        options.headers.Authorization = 'Basic ' + btoa(packet.auth);
    }

    http(options, function(error, response, body) {
        if (error) console.log('[error]', error);
        else next(body);
    });
}

//sync();
//async();
//syncTwice();
//asyncTwice();
malformed();
