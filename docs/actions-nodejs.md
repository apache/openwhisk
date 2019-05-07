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
  the Node.js runtime to use by explicitly specifying the parameter `--kind nodejs:12`, `--kind nodejs:10`, `--kind nodejs:8`, or `--kind nodejs:6`


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

## Packaging actions as Node.js modules with NPM libraries

Instead of writing all your action code in a single JavaScript source file, actions can be deployed from a zip file containing a [Node.js module](https://nodejs.org/docs/latest-v10.x/api/modules.html#modules_modules).

Archive zip files are extracted into the runtime environment and dynamically imported using `require()` during initialisation. **Actions packaged as a zip file MUST contain a valid `package.json` with a `main` field used to denote the [module index file](https://nodejs.org/docs/latest-v10.x/api/modules.html#modules_folders_as_modules) to return.**

Including a `node_modules` folder in the zip file means external NPM libraries can be used on the platform.

### Simple Example

- Create the following `package.json` file:

```json
{
  "name": "my-action",
  "main": "index.js",
  "dependencies" : {
    "left-pad" : "1.1.3"
  }
}
```

- Create the following `index.js` file:

```javascript
function myAction(args) {
    const leftPad = require("left-pad")
    const lines = args.lines || [];
    return { padded: lines.map(l => leftPad(l, 30, ".")) }
}

exports.main = myAction;
```

Functions are exported from a module by setting properties on the `exports` object. The `--main` property on the action can be used to configure the module function invoked by the platform (this defaults to `main`).

- Install module dependencies using NPM.

```
npm install
```

- Create a `.zip` archive containing all files (including all dependencies).

```
zip -r action.zip *
```

> Please note: Using the Windows Explorer action for creating the zip file will result in an incorrect structure. OpenWhisk zip actions must have `package.json` at the root of the zip, while Windows Explorer will put it inside a nested folder. The safest option is to use the command line `zip` command as shown above.

- Create the action from the zip file.

```
wsk action create packageAction --kind nodejs:10 action.zip
```

When creating an action from a `.zip` archive with the CLI tool, you must explicitly provide a value for the `--kind` flag by using `nodejs:12`, `nodejs:10`, `nodejs:8` or `nodejs:6`.

- Invoke the action as normal.

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

### Handling NPM Libraries with Native Dependencies

Node.js libraries can import native dependencies needed by the modules. These native dependencies are compiled upon installation to ensure they work in the local runtime. Native dependencies for NPM libraries must be compiled for the correct platform architecture to work in Apache OpenWhisk.

There are two approaches to using libraries with native dependencies...

1. Run `npm install` inside a Docker container from the platform images.
2. Building custom runtime image with libraries pre-installed.

**The first approach is easiest but can only be used when a zip file containing all source files and libraries is less than the action size limit (48MB).**

#### Running `npm install` inside runtime container

 - Run the following command to bind the local directory into the runtime container and run `npm install`.

```
docker run -it -v $PWD:/nodejsAction openwhisk/action-nodejs-v10 "npm install"
```
 This will leave a `node_modules` folder with native dependencies compiled for correct runtime.

 - Zip up the action source files including `node_modules` directory.

```
zip -r action.zip *
```

- Create new action with action archive.

```
wsk action create my-action --kind nodejs:10 action.zip
```

#### Building custom runtime image

- Create a `Dockerfile` with the `npm install` command run during build.

```
FROM openwhisk/action-nodejs-v10

RUN npm install <LIB_WITH_NATIVE_DEPS>
```

- Build and push the image to Docker Hub.

```
$ docker build -t <USERNAME>/custom-runtime .
$ docker push <USERNAME>/custom-runtime
```

- Create new action using custom runtime image.

```
wsk action create my-action --docker <USERNAME>/custom-runtime action.zip
```

**Make sure the `node_modules` included in the `action.zip` does not include the same libraries folders.**

## Using JavaScript Bundlers to package action source files

Using a JavaScript module bundler can transform application source files (with external dependencies) into a single compressed JavaScript file. This can lead to faster deployments, lower cold-starts and allow you to deploy large applications where individual sources files in a zip archive are larger than the action size limit.

Here are the instructions for how to use three popular module bundlers with the Node.js runtime. The "left pad" action example will be used as the source file for bundling along with the external library.

### Using rollup.js ([https://rollupjs.org](https://rollupjs.org))

- Re-write the `index.js` to use ES6 Modules, rather than CommonJS module format.

```javascript
import leftPad from 'left-pad';

function myAction(args) {
  const lines = args.lines || [];
  return { padded: lines.map(l => leftPad(l, 30, ".")) }
}

export const main = myAction
```

*Make sure you export the function using the `const main = ...` pattern. Using `export {myAction as main}` does not work due to tree-shaking. See this [blog post](https://boneskull.com/rollup-for-javascript-actions-on-openwhisk/) for full details on why this is necessary.*

- Create the Rollup.js configuration file in `rollup.config.js` with the following contents.

```javascript
import commonjs from 'rollup-plugin-commonjs';
import resolve from 'rollup-plugin-node-resolve';

export default {
  input: 'index.js',
  output: {
    file: 'bundle.js',
    format: 'cjs'
  },
  plugins: [
    resolve(),
    commonjs()
  ]
};
```

- Install the Rollup.js library and plugins using NPM.

```
npm install rollup rollup-plugin-commonjs rollup-plugin-node-resolve --save-dev
```

- Run the Rollup.js tool using the configuration file.

```
npx rollup --config
```

- Create an action using the bundle source file.

```
wsk action create my-action bundle.js --kind nodejs:10
```

- Invoke the action as normal. Results should be the same as the example above.

```
wsk action invoke my-action --result --param lines "[\"and now\", \"for something completely\", \"different\" ]"
```

### Using webpack ([https://webpack.js.org/](https://webpack.js.org/))

- Change `index.js` to export the `main` function using as a global reference.

```javascript
const leftPad = require('left-pad');

function myAction(args) {
  const lines = args.lines || [];
  return { padded: lines.map(l => leftPad(l, 30, ".")) }
}

global.main = myAction
```

This allows the bundle source to "break out" of the closures Webpack uses when defining the modules.

- Create the Webpack configuration file in `webpack.config.js` with the following contents.

```javascript
module.exports = {
  entry: './index.js',
  target: 'node',
  output: {
    filename: 'bundle.js'
  }
};
```

- Install the Webpack library and CLI using NPM.

```
npm install webpack-cli --save-dev
```

- Run the Webpack tool using the configuration file.

```
npx webpack --config webpack.config.js
```

- Create an action using the bundle source file.

```
wsk action create my-action dist/bundle.js --kind nodejs:10
```

- Invoke the action as normal. Results should be the same as the example above.

```
wsk action invoke my-action --result --param lines "[\"and now\", \"for something completely\", \"different\" ]"
```

### Using parcel ([https://parceljs.org/](https://parceljs.org/))

- Change `index.js` to export the `main` function using as a global reference.

```javascript
const leftPad = require('left-pad');

function myAction(args) {
  const lines = args.lines || [];
  return { padded: lines.map(l => leftPad(l, 30, ".")) }
}

global.main = myAction
```

This allows the bundle source to "break out" of the closures Parcel uses when defining the modules.

- Install the Parcel library using NPM.

```
npm install parcel-bundler --save-dev
```

- Run the Parcel tool using the configuration file.

```
 npx parcel index.js
```

- Create an action using the bundle source file.

```
wsk action create my-action dist/index.js --kind nodejs:10
```

- Invoke the action as normal. Results should be the same as the example above.

```
wsk action invoke my-action --result --param lines "[\"and now\", \"for something completely\", \"different\" ]"
```


## Reference

JavaScript actions can be executed in Node.js version 6, version 8 or version 10 environment.
Currently actions are executed by default in a Node.js version 10 environment.

### Node.js version 6 environment
The Node.js  version 6.17.0 environment will be used for an action if the `--kind` flag is explicitly specified with a value of 'nodejs:6' when creating/updating the action.

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
The Node.js version 8.15.1 environment is used if the `--kind` flag is explicitly specified with a value of 'nodejs:8' when creating or updating an Action.

The following packages are pre-installed in the Node.js version 8 environment:

- [openwhisk v3.18.0](https://www.npmjs.com/package/openwhisk) - JavaScript client library for the OpenWhisk platform. Provides a wrapper around the OpenWhisk APIs.

### Node.js version 10 environment
The Node.js version 10.15.2 environment is used if the `--kind` flag is explicitly specified with a value of 'nodejs:10' when creating or updating an Action.

The following packages are pre-installed in the Node.js version 10 environment:

- [openwhisk v3.18.0](https://www.npmjs.com/package/openwhisk) - JavaScript client library for the OpenWhisk platform. Provides a wrapper around the OpenWhisk APIs.

### Node.js version 12 environment
The Node.js version 12.0.0 environment is used if the `--kind` flag is explicitly specified with a value of 'nodejs:12' when creating or updating an Action.

The following packages are pre-installed in the Node.js version 12 environment:

- [openwhisk v3.18.0](https://www.npmjs.com/package/openwhisk) - JavaScript client library for the OpenWhisk platform. Provides a wrapper around the OpenWhisk APIs.
