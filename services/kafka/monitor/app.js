/**
 * Kafka Monitor
 */
'use strict';

var port       = 8080;
var express    = require('express');
var app        = express();
var spawn      = require('child_process').spawn;
var process    = require('process');

// Just one argument indicating where zookeeper is.
var zookeeper = process.argv[2];
console.log("zookeeper = ", zookeeper);

// Monitor: RAS endpoint, Readiness endpoint
app.get("/ping", function pong(req, res) {
    res.send({msg: 'pong'});
});
app.get("/ready", readyEndpoint);
var server = app.listen(port, function () {
    var host = server.address().address
    var port = server.address().port
    console.log('kafka monitor listening at http://%s:%s', host, port)
})

// This determines if Kafka is up and core topics are set up.
var topics = ['whisk']
for (var i = 0; i < 10; i++) {
    topics.push('invoke' + i.toString());
}

function makeTopics(index) {
    if (index < topics.length)
        makeTopic(topics[index], 1,
                  function(code) {
                      // TODO: retry on makeTopic?
                      if (code != 0)
                          console.log("[kafkaMonitor]", "makeTopic calls failed with code", code);
                      makeTopics(index+1)
                  });
    else
        setTimeout(steadyState, 1000);
}

// Wait 10 seconds for kafka to come up.
setTimeout(function() { makeTopics(0); }, 10000);

function steadyState() {
    checkTopics(function() {
        getOffsets(function() {
            setTimeout(steadyState, 5000);
        });
    });
}

// The state used for the readyEndpoint
var topicsReady = false;

/**
 * Responds with a "yes" or "no" to an HTTP request request
 */
function readyEndpoint(req, res) {
    if (topicsReady)
        res.send({msg: 'yes'});
    else
        res.send({msg: 'no'});
}

/**
 * Get a list of topics - printing them.
 * If there are at least 2 topics, topicsReady is set to true - as an indication of health.
 */
function checkTopics(next) {
    getTopics(function(topicsOrCode) {
        if (typeof topicsOrCode == 'number') {
            console.log("getTopics failed with code", topicsOrCode);
            topicsReady = false;
        } else {
            var topics = topicsOrCode;
            var numTopics = topics.length;
            var topicString = "";
            for (var i=0; i<topics.length; i++)
                topicString += " " + topics[i];
            console.log('[monitor]', numTopics, "topics:", topicString);
            topicsReady = numTopics >= 2;
        }
        next();
    });
}

/**
 * Make a topic with the given replication topic passing to the continuation
 * a status code of the call.
 */
function makeTopic(topic, numPartitions, next) {
    exec("/kafka/bin/kafka-topics.sh",
         [ '--zookeeper', zookeeper, '--create', '--topic', topic, '--partition', numPartitions,  '--replication-factor', '1'],
         function(stdout, stderr, code) {
             next(code);
         });
}

/**
 * Until the consumer group exists, we will get an exception.
 */
function getOffsets(next) {
    exec("/kafka/bin/kafka-run-class.sh", [ 'kafka.tools.ConsumerOffsetChecker', '--zkconnect', zookeeper, '--group', 'invokers' ],
            function(stdout,stderr,code) {
                if (code == 0) {
                    console.log("getOffsets:\n" + stdout);
                } else {
                    if (stderr.length > 80)
        stderr = stderr.substring(0,80);
                }
                next();
            });
}

/**
 * Get a list of existing topics passing to the continuation either a failure code
 * or a list of the topics.
 */
function getTopics(next) {
    exec("/kafka/bin/kafka-topics.sh", [ '--zookeeper', zookeeper, '--list' ],
            function(stdout, stderr, code) {
                if (code != 0)
        return next(code);
                else {
                    var topics = cleanArray(stdout.split("\n"));
                    return next(topics);
                }
            });
}

/**
 * Return array with empty elements removed.
 */
function cleanArray(actual) {
    var newArray = new Array();
    for(var i = 0; i<actual.length; i++){
        if (actual[i]) {
            newArray.push(actual[i]);
        }
    }
    return newArray;
}

/**
 * Wraps a spawn and captures output.  The callback is executed
 * when the child process exits and given a triple of stdout/stderr/code.
 */
function exec(command, args, callback) {

    //    console.log('spawning', command);
    var child = spawn(command, args);
    var tmp = { stdout : "", stderr : "", code : "undefined" };
    child.stdout.on('data', function (data) {
        //console.log('SOME stdout: ' + data);
        tmp.stdout = tmp.stdout + data;
    });

    child.stderr.on('data', function (data) {
        //console.log('SOME stderr: ' + data);
        tmp.stderr = tmp.stderr + data;
    });

    child.on('close', function (code) {
        tmp.code = code;
        //        console.log('exec.closed: ' + code);
        callback(tmp.stdout, tmp.stderr, tmp.code);
    });
}


