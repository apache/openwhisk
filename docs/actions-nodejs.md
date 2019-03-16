<!--
#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
-->

## Creating and invoking JavaScript actions

The process of creating JavaScript actions is similar to that of [other actions](actions.md#the-basics).
The following sections guide you through creating and invoking a single JavaScript action,
and demonstrate how to bundle multiple JavaScript files and third party dependencies.

1. Create a JavaScript file with the following content. For this example, the file name is `hello.js`.

  ```javascript
  function main() {
      return { msg: 'Hello world' };
  }
  ```

  The JavaScript file might contain additional functions.
  However, by convention, a function called `main` must exist to provide the entry point for the action.

2. Create an action from the following JavaScript function. For this example, the action is called `hello`.

  ```
  wsk action create hello hello.js
  ok: created action hello
  ```

  The CLI automatically infers the type of the action by using the source file extension.
  For `.js` source files, the action runs by using a Node.js runtime. You may specify
  the Node.js runtime to use by explicitly specifying the parameter `--kind nodejs:10`, `--kind nodejs:8`, or `--kind nodejs:6`


## Creating asynchronous actions

JavaScript functions that run asynchronously may need to return the activation result after the `main` function has returned. You can accomplish this by returning a Promise in your action.

1. Save the following content in a file called `asyncAction.js`.

  ```javascript
  function main(args) {
       return new Promise(function(resolve, reject) {
         setTimeout(function() {
           resolve({ done: true });
         }, 2000);
      })
   }
  ```

  Notice that the `main` function returns a Promise, which indicates that the activation hasn't completed yet, but is expected to in the future.

  The `setTimeout()` JavaScript function in this case waits for two seconds before calling the callback function.  This represents the asynchronous code and goes inside the Promise's callback function.

  The Promise's callback takes two arguments, resolve and reject, which are both functions.  The call to `resolve()` fulfills the Promise and indicates that the activation has completed normally.

  A call to `reject()` can be used to reject the Promise and signal that the activation has completed abnormally.

2. Run the following commands to create the action and invoke it:

  ```
  wsk action create asyncAction asyncAction.js
  ```
  ```
  wsk action invoke --result asyncAction
  ```
  ```json
  {
      "done": true
  }
  ```

  Notice that you performed a blocking invocation of an asynchronous action.

3. Fetch the activation log to see how long the activation took to complete:

  ```
  wsk activation list --limit 1 asyncAction
  ```
<pre>
Datetime            Activation ID                    Kind      Start Duration   Status  Entity
2019-03-16 19:46:43 64581426b44e4b3d981426b44e3b3d19 nodejs:6  cold  2.033s     success guest/asyncAction:0.0.1
</pre>
  ```
  wsk activation get 64581426b44e4b3d981426b44e3b3d19
  ```
 ```json
  {
      "start": 1552762003015,
      "end":   1552762005048,
      ...
  }
  ```

  Comparing the `start` and `end` time stamps in the activation record, you can see that this activation took slightly over two seconds to complete.

## Using actions to call an external API

The examples so far have been self-contained JavaScript functions. You can also create an action that calls an external API.

This example invokes a Yahoo Weather service to get the current conditions at a specific location.

1. Save the following content in a file called `weather.js`.

  ```javascript
  var request = require('request');

  function main(params) {
      var location = params.location || 'Vermont';
      var url = 'https://query.yahooapis.com/v1/public/yql?q=select item.condition from weather.forecast where woeid in (select woeid from geo.places(1) where text="' + location + '")&format=json';

      return new Promise(function(resolve, reject) {
          request.get(url, function(error, response, body) {
              if (error) {
                  reject(error);
              }
              else {
                  var condition = JSON.parse(body).query.results.channel.item.condition;
                  var text = condition.text;
                  var temperature = condition.temp;
                  var output = 'It is ' + temperature + ' degrees in ' + location + ' and ' + text;
                  resolve({msg: output});
              }
          });
      });
  }
  ```

  Note that the action in the example uses the JavaScript `request` library to make an HTTP request to the Yahoo Weather API, and extracts fields from the JSON result.
  See the JavaScript [reference](#reference) for the Node.js packages available in the runtime environment.

  This example also shows the need for asynchronous actions. The action returns a Promise to indicate that the result of this action is not available yet when the function returns. Instead, the result is available in the `request` callback after the HTTP call completes, and is passed as an argument to the `resolve()` function.

2. Create an action from the `weather.js` file:

  ```
  wsk action create weather weather.js
  ```

3. Use the following command to run the action, and observe the output:
  ```
  wsk action invoke --result weather --param location "Brooklyn, NY"
  ```

  Using the `--result` flag means that the value returned from the action is shown as output on the command-line:

  ```json
  {
      "msg": "It is 28 degrees in Brooklyn, NY and Cloudy"
  }
  ```

This example also passed a parameter to the action by using the `--param` flag and a value that can be changed each time the action is invoked. Find out more about parameters in the [Working with parameters](./parameters.md) section.

## Packaging an action as a Node.js module

As an alternative to writing all your action code in a single JavaScript source file, you can write an action as a `npm` package. Consider as an example a directory with the following files:

First, `package.json`:

```json
{
  "name": "my-action",
  "main": "index.js",
  "dependencies" : {
    "left-pad" : "1.1.3"
  }
}
```

Then, `index.js`:

```javascript
function myAction(args) {
    const leftPad = require("left-pad")
    const lines = args.lines || [];
    return { padded: lines.map(l => leftPad(l, 30, ".")) }
}

exports.main = myAction;
```

Note that the action is exposed through `exports.main`; the action handler itself can have any name, as long as it conforms to the usual signature of accepting an object and returning an object (or a `Promise` of an object). Per Node.js convention, you must either name this file `index.js` or specify the file name you prefer as the `main` property in package.json.

To create an OpenWhisk action from this package:

1. Install first all dependencies locally

  ```
  $ npm install
  ```

2. Create a `.zip` archive containing all files (including all dependencies):

  ```
  $ zip -r action.zip *
  ```

  > Please note: Using the Windows Explorer action for creating the zip file will result in an incorrect structure. OpenWhisk zip actions must have `package.json` at the root of the zip, while Windows Explorer will put it inside a nested folder. The safest option is to use the command line `zip` command as shown above.

3. Create the action:

  ```
  wsk action create packageAction --kind nodejs:10 action.zip
  ```

  When creating an action from a `.zip` archive with the CLI tool, you must explicitly provide a value for the `--kind` flag by using `nodejs:10`, `nodejs:8` or `nodejs:6`.

4. You can invoke the action like any other:

  ```
  wsk action invoke --result packageAction --param lines "[\"and now\", \"for something completely\", \"different\" ]"
  ```
  ```json
  {
      "padded": [
          ".......................and now",
          "......for something completely",
          ".....................different"
      ]
  }
  ```

Finally, note that while most `npm` packages install JavaScript sources on `npm install`, some also install and compile binary artifacts. The archive file upload currently does not support binary dependencies but rather only JavaScript dependencies. Action invocations may fail if the archive includes binary dependencies.

### Package an action as a single bundle

It is convenient to only include the minimal code into a single `.js` file that includes dependencies. This approach allows for faster deployments, and in some circumstances where packaging the action as a zip might be too large because it includes unnecessary files.

You can use a JavaScript module bundler such as [webpack](https://webpack.js.org/concepts/). When webpack processes your code, it recursively builds a dependency graph that includes every module that your action needs.

Here is a quick example using webpack:

Taking the previous example `package.json` add `webpack` as a development dependency and add some npm script commands.
```json
{
  "name": "my-action",
  "main": "dist/bundle.js",
  "scripts": {
    "build": "webpack --config webpack.config.js",
    "deploy": "wsk action update my-action dist/bundle.js --kind nodejs:8"
  },
  "dependencies": {
    "left-pad": "1.1.3"
  },
  "devDependencies": {
    "webpack": "^3.8.1"
  }
}
```

Create the webpack configuration file `webpack.config.js`.
```javascript
var path = require('path');
module.exports = {
  entry: './index.js',
  output: {
    path: path.resolve(__dirname, 'dist'),
    filename: 'bundle.js'
  },
  target: 'node'
};
```

Set the variable `global.main` to the main function of the action.
From the previous example:
```javascript
function myAction(args) {
    const leftPad = require("left-pad")
    const lines = args.lines || [];
    return { padded: lines.map(l => leftPad(l, 30, ".")) }
}
global.main = myAction;
```

If your function name is `main`, use this syntax instead:
```javascript
global.main = main;
```

To build and deploy an OpenWhisk Action using `npm` and `webpack`:

1. First, install dependencies locally:

  ```
  npm install
  ```

2. Build the webpack bundle:

  ```
  npm run build
  ```

  The file `dist/bundle.js` is created, and is used to deploy as the Action source code.

3. Create the Action using the `npm` script or the CLI.
  Using `npm` script:
  ```
  npm run deploy
  ```

  Using the CLI:
  ```
  wsk action update my-action dist/bundle.js
  ```

Finally, the bundle file that is built by `webpack` doesn't support binary dependencies but rather JavaScript dependencies. So Action invocations will fail if the bundle depends on binary dependencies, because this is not included with the file `bundle.js`.


## Reference

JavaScript actions can be executed in Node.js version 6 or Node.js version 8.
Currently actions are executed by default in a Node.js version 6 environment.

### Node.js version 6 environment
The Node.js 6.14.4 environment will be used for an action if the `--kind` flag is explicitly specified with a value of 'nodejs:6' when creating/updating the action.

The following packages are available to be used in the Node.js 6 environment:

- [apn v2.1.2](https://www.npmjs.com/package/apn) - A Node.js module for interfacing with the Apple Push Notification service.
- [async v2.1.4](https://www.npmjs.com/package/async) - Provides functions for working with asynchronous functions.
- [btoa v1.1.2](https://www.npmjs.com/package/btoa) - A port of the browser's btoa function.
- [cheerio v0.22.0](https://www.npmjs.com/package/cheerio) - Fast, flexible & lean implementation of core jQuery designed specifically for the server.
- [cloudant v1.6.2](https://www.npmjs.com/package/cloudant) - This is the official Cloudant library for Node.js.
- [commander v2.9.0](https://www.npmjs.com/package/commander) - The complete solution for Node.js command-line interfaces.
- [consul v0.27.0](https://www.npmjs.com/package/consul) - A client for Consul, involving service discovery and configuration.
- [cookie-parser v1.4.3](https://www.npmjs.com/package/cookie-parser) - Parse Cookie header and populate req.cookies with an object keyed by the cookie names.
- [cradle v0.7.1](https://www.npmjs.com/package/cradle) - A high-level, caching, CouchDB client for Node.js.
- [errorhandler v1.5.0](https://www.npmjs.com/package/errorhandler) - Development-only error handler middleware.
- [glob v7.1.1](https://www.npmjs.com/package/glob) - Match files by using patterns that the shell uses, like stars and stuff.
- [gm v1.23.0](https://www.npmjs.com/package/gm) - GraphicsMagick and ImageMagick for Node.
- [lodash v4.17.2](https://www.npmjs.com/package/lodash) - The Lodash library exported as Node.js modules.
- [log4js v0.6.38](https://www.npmjs.com/package/log4js) - A conversion of the log4js framework designed to work with Node.
- [iconv-lite v0.4.15](https://www.npmjs.com/package/iconv-lite) - Pure JS character encoding conversion
- [marked v0.3.6](https://www.npmjs.com/package/marked) - A full-featured markdown parser and compiler, which is written in JavaScript. Built for speed.
- [merge v1.2.0](https://www.npmjs.com/package/merge) - Merge multiple objects into one, optionally creating a new cloned object.
- [moment v2.17.0](https://www.npmjs.com/package/moment) - A lightweight JavaScript date library for parsing, validating, manipulating, and formatting dates.
- [mongodb v2.2.11](https://www.npmjs.com/package/mongodb) - The official MongoDB driver for Node.js.
- [mustache v2.3.0](https://www.npmjs.com/package/mustache) - Mustache.js is an implementation of the mustache template system in JavaScript.
- [nano v6.2.0](https://www.npmjs.com/package/nano) - Minimalistic CouchDB driver for Node.js.
- [node-uuid v1.4.7](https://www.npmjs.com/package/node-uuid) - Deprecated UUID packaged.
- [nodemailer v2.6.4](https://www.npmjs.com/package/nodemailer) - Send e-mails from Node.js – easy as cake!
- [oauth2-server v2.4.1](https://www.npmjs.com/package/oauth2-server) - Complete, compliant, and well tested module for implementing an OAuth2 Server/Provider with express in Node.js.
- [openwhisk v3.18.0](https://www.npmjs.com/package/openwhisk) - JavaScript client library for the OpenWhisk platform. Provides a wrapper around the OpenWhisk APIs.
- [pkgcloud v1.4.0](https://www.npmjs.com/package/pkgcloud) - pkgcloud is a standard library for Node.js that abstracts away differences among multiple cloud providers.
- [process v0.11.9](https://www.npmjs.com/package/process) - Require('process'); just like any other module.
- [pug v2.0.0-beta6](https://www.npmjs.com/package/pug) - Implements the Pug templating language.
- [redis v2.6.3](https://www.npmjs.com/package/redis) - This is a complete and feature-rich Redis client for Node.js.
- [request v2.79.0](https://www.npmjs.com/package/request) - Request is the simplest way possible to make HTTP calls.
- [request-promise v4.1.1](https://www.npmjs.com/package/request-promise) - The simplified HTTP request client 'request' with Promise support. Powered by Bluebird.
- [rimraf v2.5.4](https://www.npmjs.com/package/rimraf) - The UNIX command rm -rf for node.
- [semver v5.3.0](https://www.npmjs.com/package/semver) - Supports semantic versioning.
- [sendgrid v4.7.1](https://www.npmjs.com/package/sendgrid) - Provides email support via the SendGrid API.
- [serve-favicon v2.3.2](https://www.npmjs.com/package/serve-favicon) - Node.js middleware for serving a favicon.
- [socket.io v1.6.0](https://www.npmjs.com/package/socket.io) - Socket.IO enables real-time bidirectional event-based communication.
- [socket.io-client v1.6.0](https://www.npmjs.com/package/socket.io-client) - Client-side support for Socket.IO.
- [superagent v3.0.0](https://www.npmjs.com/package/superagent) - SuperAgent is a small progressive client-side HTTP request library, and Node.js module with the same API, sporting many high-level HTTP client features.
- [swagger-tools v0.10.1](https://www.npmjs.com/package/swagger-tools) - Tools that are related to working with Swagger, a way to document APIs.
- [tmp v0.0.31](https://www.npmjs.com/package/tmp) - A simple temporary file and directory creator for Node.js.
- [twilio v2.11.1](https://www.npmjs.com/package/twilio) - A wrapper for the Twilio API, related to voice, video, and messaging.
- [underscore v1.8.3](https://www.npmjs.com/package/underscore) - Underscore.js is a utility-belt library for JavaScript that supports the usual functional suspects (each, map, reduce, filter...) without extending any core JavaScript objects.
- [uuid v3.0.0](https://www.npmjs.com/package/uuid) - Simple, fast generation of RFC4122 UUIDS.
- [validator v6.1.0](https://www.npmjs.com/package/validator) - A library of string validators and sanitizers.
- [watson-developer-cloud v2.29.0](https://www.npmjs.com/package/watson-developer-cloud) - Node.js client library to use the Watson Developer Cloud services, a collection of APIs that use cognitive computing to solve complex problems.
- [when v3.7.7](https://www.npmjs.com/package/when) - When.js is a rock solid, battle-tested Promises/A+ and when() implementation, including a complete ES6 Promise shim.
- [winston v2.3.0](https://www.npmjs.com/package/winston) - A multi-transport async logging library for Node.js. "CHILL WINSTON! ... I put it in the logs."
- [ws v1.1.1](https://www.npmjs.com/package/ws) - ws is a simple to use, blazing fast, and thoroughly tested WebSocket client and server implementation.
- [xml2js v0.4.17](https://www.npmjs.com/package/xml2js) - Simple XML to JavaScript object converter. It supports bi-directional conversion.
- [xmlhttprequest v1.8.0](https://www.npmjs.com/package/xmlhttprequest) - node-XMLHttpRequest is a wrapper for the built-in http client to emulate the browser XMLHttpRequest object.
- [yauzl v2.7.0](https://www.npmjs.com/package/yauzl) - Yet another unzip library for node. For zipping.

### Node.js version 8 environment
The Node.js version 8.12.0 environment is used if the `--kind` flag is explicitly specified with a value of 'nodejs:8' when creating or updating an Action.

The following packages are pre-installed in the Node.js version 8 environment:

- [openwhisk v3.18.0](https://www.npmjs.com/package/openwhisk) - JavaScript client library for the OpenWhisk platform. Provides a wrapper around the OpenWhisk APIs.

### Node.js version 10 environment
The Node.js version 10.13.0 environment is used if the `--kind` flag is explicitly specified with a value of 'nodejs:10' when creating or updating an Action.

The following packages are pre-installed in the Node.js version 10 environment:

- [openwhisk v3.18.0](https://www.npmjs.com/package/openwhisk) - JavaScript client library for the OpenWhisk platform. Provides a wrapper around the OpenWhisk APIs.

### Packaging npm packages with your actions
For any `npm` packages that are not pre-installed in the Node.js environment, you can bundle them as dependencies when you create or update your action.

For more information, see [Packaging an action as a Node.js module](./actions.md#packaging-an-action-as-a-nodejs-module) or [Packaging an action as a single bundle](./actions.md#packaging-an-action-as-a-single-bundle).

