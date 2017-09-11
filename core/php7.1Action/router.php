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
 * router.php
 *
 * This file is the API client for the action. The controller POSTs /init to set up the action and
 * then POSTs to /run to invoke it.
 */

// set up an output buffer to redirect any script output to stdout, rather than the default
// php://output, so that it goes to the logs, not the HTTP client.
ob_start(function ($data) {
    file_put_contents("php://stdout", $data);
    return '';
}, 1, PHP_OUTPUT_HANDLER_CLEANABLE | PHP_OUTPUT_HANDLER_FLUSHABLE | PHP_OUTPUT_HANDLER_REMOVABLE);

const ACTION_SRC_FILENAME = 'index.php';
const SRC_DIR  = __DIR__ . '/src';
const ACTION_CONFIG_FILE = __DIR__. '/config.json';
const ACTION_SRC_FILE = SRC_DIR . '/' . ACTION_SRC_FILENAME;
const ACTION_RUNNER_FILE = __DIR__. '/runner.php';
const TMP_ZIP_FILE = '/action.zip';

// execute the revelant endpoint
$result = route($_SERVER['REQUEST_URI']);

// send response
$body = json_encode((object)$result);
header('Content-Type: application/json');
header("Content-Length: " . mb_strlen($body));
ob_end_clean();
echo $body;
exit;

/**
 * executes the relevant method for a given URL and return an array of data to send to the client
 */
function route(string $uri) : array
{
    try {
        switch ($uri) {
            case '/init':
                return init();

            case '/run':
                return run();

            default:
                throw new RuntimeException('Unexpected call to ' . $_SERVER["REQUEST_URI"], 500);
        }
    } catch (Throwable $e) {
        $code = $e->getCode() < 400 ? 500 : $e->getCode();

        if ($code != 502) {
            writeTo("php://stdout", 'Error: ' . $e->getMessage());
        }
        writeSentinels();

        http_response_code($code);
        return ['error' => $e->getMessage()];
    }

    return '';
}

/**
 * Handle the /init endpoint
 *
 * This end point is called once per container creation. It gives us the code we need
 * to run and the name of the function within that code that's the entry point. As PHP
 * has a setup/teardown model, we store the function name to a config file for retrieval
 * in the /run end point.
 *
 * @return array Data to return to the client
 */
function init() : array
{
    // data is POSTed to us as a JSON string
    $post = file_get_contents('php://input');
    $data = json_decode($post, true)['value'] ?? [];

    $name = $data['name'] ?? '';         // action name
    $main = $data['main'] ?? 'main';     // function to call (default: main)
    $code = trim($data['code'] ?? '');   // source code to run
    $binary = $data['binary'] ?? false;  // code is binary?

    if (!$code) {
        throw new RuntimeException("No code to execute");
    }

    if ($binary) {
        // binary code is a zip file that's been base64 encoded, so unzip it
        unzipString($code, SRC_DIR);

        // if the zip file didn't contain a vendor directory, move our vendor into the src folder
        if (! file_exists(SRC_DIR . '/vendor/autoload.php')) {
            exec('mv ' . escapeshellarg(__DIR__ . '/vendor') . ' ' . escapeshellarg(SRC_DIR . '/vendor'));
        }

        // check that we have the expected action source file
        if (! file_exists(ACTION_SRC_FILE)) {
            throw new RuntimeException('Zipped actions must contain ' . ACTION_SRC_FILENAME . ' at the root.', 500);
        }
    } else {
        // non-binary code is a text string, so save to disk
        file_put_contents(ACTION_SRC_FILE, $code);

        // move vendor folder into the src folder
        exec('mv ' . escapeshellarg(__DIR__ . '/vendor') . ' ' . escapeshellarg(SRC_DIR . '/vendor'));
    }

    // is action file valid PHP? run `php -l` to find out
    list($returnCode, $stdout, $stderr) = runPHP(['-l', '-f', ACTION_SRC_FILE]);
    if ($returnCode != 0) {
        writeTo("php://stderr", $stderr);
        writeTo("php://stdout", $stdout);

        $message = 'PHP syntax error in ' . ($binary ? ACTION_SRC_FILENAME : 'action.');
        throw new RuntimeException($message, 500);
    }

    // does the action have the expected function name?
    $testCode = 'require "' . ACTION_SRC_FILENAME . '"; exit((int)(! function_exists("' . $main .'")));';
    list($returnCode, $stdout, $stderr) = runPHP(['-r', $testCode]);
    if ($returnCode != 0) {
        writeTo("php://stderr", $stderr);
        writeTo("php://stdout", $stdout);
        throw new RuntimeException("The function $main is missing.");
    }

    // write config file for use by /run
    $config = [
        'file' => ACTION_SRC_FILENAME,
        'function' => $main,
        'name' => $name,
    ];
    file_put_contents(ACTION_CONFIG_FILE, json_encode($config));

    return ["OK" => true];
}

/**
 * Handle the /run endpoint
 *
 * This end point is called once per action invocation. We load the function name from
 * the config file and then invoke it. Note that as PHP writes to php://output, we
 * capture in an output buffer and write the buffer to stdout for the OpenWhisk logs.
 *
 * @return array Data to return to the client
 */
function run() : array
{
    if (! file_exists(ACTION_SRC_FILE)) {
        error_log('NO ACTION FILE: ' . ACTION_SRC_FILE);
        throw new RuntimeException('!Could not find action file: ' . ACTION_SRC_FILENAME, 500);
    }

    // load config to pass to runner
    if (! file_exists(ACTION_CONFIG_FILE)) {
        error_log('NO CONFIG FILE: ' . ACTION_CONFIG_FILE);
        throw new RuntimeException('Could not find config file', 500);
    }
    $config = file_get_contents(ACTION_CONFIG_FILE);
    
    // Extract the posted data
    $post = json_decode(file_get_contents('php://input'), true);
    if (!is_array($post)) {
        $post = [];
    }

    // assign environment variables from the posted data
    $env = array_filter($_ENV, function ($k) {
        // only pass OpenWhisk environment variables to the action
        return stripos($k, '__OW_') === 0;
    }, ARRAY_FILTER_USE_KEY);
    $env['PHP_VERSION'] = $_ENV['PHP_VERSION'];
    foreach (['api_key', 'namespace', 'action_name', 'activation_id', 'deadline'] as $param) {
        if (array_key_exists($param, $post)) {
            $env['__OW_' . strtoupper($param)] = $post[$param];
        }
    }

    // extract the function arguments from the posted data's "value" field
    $args = '{}';
    if (array_key_exists('value', $post) && is_array($post['value'])) {
        $args = json_encode($post['value']);
    }
    $env['WHISK_INPUT'] = $args;

    // run the action
    list($returnCode, $stdout, $stderr) = runPHP(
        ['-d', 'error_reporting=E_ALL', '-f', ACTION_RUNNER_FILE, '--', $config],
        $args,
        $env
    );

    // separate the esponse to send back to the client: it's the last line of stdout
    $pos = strrpos($stdout, PHP_EOL);
    if ($pos == false) {
        // just one line of output
        $lastLine = $stdout;
        $stdout = '';
    } else {
        $pos++;
        $lastLine = trim(substr($stdout, $pos));
        $stdout = trim(substr($stdout, 0, $pos));
    }

    // write out the action's stderr and stdout
    writeTo("php://stderr", $stderr);
    writeTo("php://stdout", $stdout);

    $output = json_decode($lastLine, true);
    if ($returnCode != 0 || !is_array($output)) {
        // an error occurred while running the action
        // the return code will be 1 if the stdout is printable to the user
        if ($returnCode != 1) {
            // otherwise put out a generic message and send $lastLine to stdout
            writeTo("php://stdout", $lastLine);
            $lastLine = 'An error occurred running the action.';
        }
        throw new RuntimeException($lastLine, 502);
    }

    // write sentinels as action is completed
    writeSentinels();

    return $output;
}

/**
 * Unzip a base64 encoded string to a directory
 */
function unzipString(string $b64Data, $dir): void
{
    file_put_contents(TMP_ZIP_FILE, base64_decode($b64Data));

    $zip = new ZipArchive();
    $res = $zip->open(TMP_ZIP_FILE);
    if ($res !== true) {
        $reasons = [
            ZipArchive::ER_EXISTS => "File already exists.",
            ZipArchive::ER_INCONS => "Zip archive inconsistent.",
            ZipArchive::ER_INVAL => "Invalid argument.",
            ZipArchive::ER_MEMORY => "Malloc failure.",
            ZipArchive::ER_NOENT => "No such file.",
            ZipArchive::ER_NOZIP => "Not a zip archive.",
            ZipArchive::ER_OPEN => "Can't open file.",
            ZipArchive::ER_READ => "Read error.",
            ZipArchive::ER_SEEK => "Seek error.",
        ];
        $reason = $reasons[$res] ?? "Unknown error: $res.";
        throw new RuntimeException("Failed to open zip file: $reason", 500);
    }

    $res = $zip->extractTo($dir . '/');
    $zip->close();
}

/**
 * Write the OpenWhisk sentinels to stdout and stderr so that it knows that we've finished
 * writing data to them.
 *
 * @return void
 */
function writeSentinels() : void
{
    // write out sentinels as we've finished all log output
    writeTo("php://stderr", "XXX_THE_END_OF_A_WHISK_ACTIVATION_XXX");
    writeTo("php://stdout", "XXX_THE_END_OF_A_WHISK_ACTIVATION_XXX");
}

/**
 * Run the PHP command in a separate process
 *
 * This ensures that if the action causes a fatal error, we can handle it.
 *
 * @param  array  $args  arguments to the PHP executable
 * @param  string $stdin stdin to pass to the process
 * @param  array  $env   environment variables to set for the process
 * @return array         array containing [int return code, string stdout string stderr]
 */
function runPHP(array $args, string $stdin = '', array $env = []) : array
{
    $cmd = '/usr/local/bin/php ' . implode(' ', array_map('escapeshellarg', $args));

    $process = proc_open(
        $cmd,
        [
            0 => ['pipe', 'r'],
            1 => ['pipe', 'w'],
            2 => ['pipe', 'w'],
        ],
        $pipes,
        SRC_DIR,
        $env
    );

    // write to the process' stdin
    $bytes = fwrite($pipes[0], $stdin);
    fclose($pipes[0]);

    // read the process' stdout
    $stdout = stream_get_contents($pipes[1]);
    fclose($pipes[1]);

    // read the process' stderr
    $stderr = stream_get_contents($pipes[2]);
    fclose($pipes[2]);

    // close process & get return code
    $returnCode = proc_close($process);

    // tidy up paths in any PHP stack traces
    $stderr = str_replace(__DIR__ . '/', '', trim($stderr));
    $stdout = str_replace(__DIR__ . '/', '', trim($stdout));

    return [$returnCode, $stdout, $stderr];
}

function writeTo($pipe, $text)
{
    if ($text) {
        file_put_contents($pipe, $text . PHP_EOL);
    }
}
