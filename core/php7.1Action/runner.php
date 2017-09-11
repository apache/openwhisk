<?php
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
 * runner.php
 *
 * This file runs the action code provided by the user. It is executed in the PHP CLI environment
 * by router.php and will require the index.php file and call the main() function (or whatever has
 * been configured).
 *
 * The configuration information is passed in as a JSON object as the first argument to this script
 * and the OpenWhisk action argumentsare passed in as a JSON object via stdin.
 */

// read config from argv[1] and assign
if ($argc != 2) {
    file_put_contents("php://stderr", 'Expected a single config parameter');
    exit(1);
}

$config = json_decode($argv[1], true);
if (!is_array($config)) {
    file_put_contents("php://stderr", "Invalid config: {$argv[1]}.");
    exit(1);
}

$_actionFile = $config['file'] ?? 'index.php';
$_functionName = $config['function'] ?? 'main';
unset($argv[1], $config);

// does the action file exist?
if (! file_exists($_actionFile)) {
    file_put_contents("php://stderr", "Could not find action file: $_actionFile.");
    exit(1);
}

// run the action with arguments from stdin
require __DIR__ . '/src/vendor/autoload.php';
require $_actionFile;

$result = $_functionName(json_decode(file_get_contents('php://stdin') ?? [], true));

if (is_scalar($result)) {
    file_put_contents("php://stderr", 'Result must be an array but has type "'
        . gettype($result) . '": ' . (string)$result . "\n");
    file_put_contents("php://stdout", 'The action did not return a dictionary.');
    exit(1);
} elseif (is_object($result) && method_exists($result, 'getArrayCopy')) {
    $result = $result->getArrayCopy();
}

// cast result to an object for json_encode to ensure that an empty array becomes "{}"
echo "\n";
echo json_encode((object)$result);
