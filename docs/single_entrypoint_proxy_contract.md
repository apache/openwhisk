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
# Action Proxy Single Entrypoint Interface

The typical endpoints used by the OpenWhisk control plane are not used in single entrypoint execution environments such as Knative. Initialization and running are still essential to how OpenWhisk runtimes function, but they are done in a different methodology than `/init` and `/run` endpoints. The proxy that shapes how the calls are preprocessed and postprocessed to emulate some of the functionality provided by the OpenWhisk control plane. In single entrypoint supported runtime proxy implementations, both initailization and running are done via the `/` root endpoint. The sections below explain the interface the runtime proxy must adhere to initialize and run via a single entrypoint execution environment.

## Init

To initialize an undifferintiated stem cell, the interface is to pass a JSON object containing the key `init` to the `/` endpoint. The value corresponding to the `init` key is the same JSON object as the [initialization of standard OpenWhisk actions](actions-new.md#initialization). For example:
```json
{
  "init": {
    "name" : "hello",
    "main" : "main",
    "code" : "function main(params) {return { payload: 'Hello ' + params.name + ' from ' + params.place +  '!' };}",
    "binary": false,
    "env": {}
  }
}
```
Just as with the OpenWhisk control plane, specialized function containers need no explicit initialization.

## Run

To run an action, the interface is to pass a JSON object containing the key `activation` to the `/` endpoint. The value corresponding to the `activation` key is largely the same JSON object as the [activation of standard OpenWhisk actions](actions-new.md#activation). The key difference is that `value` is not used under the `activation` key to pass parameters to the underlying function. To see the interface for passing keys to the underlying functions see section below.
Example of an activation:
```json
{
  "activation": {
    "namespace": "",
    "action_name": "hello",
    "api_host": "",
    "api_key": "",
    "activation_id": "",
    "transaction_id": "",
    "deadline": 1000000
  },
  "value": {
    "name": "Alan Turing",
    "place": "England"
  }
}
```
One thing to note is when these values are present outside of the context of the OpenWhisk control plane, they may not actually be used for anything. However, the `activation` key is still necessary to signal the intent to run the function.

## Passing parameters

Similar to the description of the `value` key in the `activation` object during the [activation of standard OpenWhisk actions](actions-new.md#activation), a top level `value` key in the JSON object passed to the `/` endpoint (with a corresponding top level `activation` key) is how parameters are passed to the underlying function being run.
In the following example:
```json
{
  "activation": {
    "namespace": "",
    "action_name": "hello",
    "api_host": "",
    "api_key": "",
    "activation_id": "",
    "transaction_id": "",
    "deadline": 1000000
  },
  "value": {
    "name": "Alan Turing",
    "location": "England"
  }
}
```

The underlying function would recieve a parameters map with the keys `name` and `location` with the values `Alan Turing` and `England` respectively.

## Init/Run

OpenWhisk stem cell runtimes being executed in a single entrypoint execution environment can be both initialized and activated at the same time by passing both `init` and `activation` keys in the same JSON object to the `/` endpoint. This will first initialize the runtime, following the same procedures described above, and then subsequently activate the same runtime.
For example:
```json
{
  "init": {
    "name" : "hello",
    "main" : "main",
    "code" : "function main(params) {return { payload: 'Hello ' + params.name + ' from ' + params.place +  '!' };}",
    "binary": false,
    "env": {}
  },
  "activation": {
    "namespace": "",
    "action_name": "hello",
    "api_host": "",
    "api_key": "",
    "activation_id": "",
    "transaction_id": "",
    "deadline": 1000000
  },
  "value": {
    "name": "Alan Turing",
    "location": "England"
  }
}
```
The above JSON object would instruct the runtime to be initialized with the function under `init.code` and be run with the function being passed the object `{name: "Alan Turing", location: "England"}`. It would then return the JSON object
```json
{
  "payload": "Hello Alan Turing from England!"
}
```

## Example Cases
Below is a table outlining the standardized behaviors that any action proxy implementation needs to fulfill. NodeJS was the sample language used, but corresponding example cases could be written in the language of the corresponding runtime it is showcasing.


<table border="2" cellspacing="0" cellpadding="6" rules="groups" frame="hsides">


<colgroup>
<col/>

<col/>

<col/>

<col/>

<col/>

<col/>

<col/>
</colgroup>
<thead>
<tr>
<th scope="col" >Test Name</th>
<th scope="col" >Action Code (In NodeJS)</th>
<th scope="col" >Input</th>
<th scope="col" >Output</th>
<th scope="col" >Status code</th>
<th scope="col" >Mime type</th>
<th scope="col" >Notes</th>
<th scope="col" >Environment Variables</th>
</tr>
</thead>
<tbody>
<tr>
<td>Hello World</td>
<td>
  <code><pre>
function main() {
  return {payload: 'Hello World!'};
}
  </pre></code>
</td>
<td>
  <code><pre>
{
 "value": {
   "name": "Joe",
   "place": "TX"
 }
}
  </pre></code>
</td>
<td>
  <code><pre>
{
  "payload": 'Hello World!'
}
  </pre></code>
</td>
<td>200</td>
<td>application/json</td>
<td>&#xa0;</td>
</tr>
</tbody>
<tbody>
<tr>
<td>Hello World With Params</td>
<td>
  <code><pre>
function main(params) {
  return { payload: 'Hello ' + params.name + ' from ' + params.place +  '!' };
}
  </pre></code>
</td>
<td>
  <code><pre>
{
 "value": {
   "name": "Joe",
   "place": "TX"
 }
}
  </pre></code>
</td>
<td><code><pre>{
  "payload": "Hello Joe from TX!"
}</pre></code></td>
<td>200</td>
<td>application/json</td>
<td>&#xa0;</td>
</tr>
</tbody>
<tbody>
<tr>
<td>Web Action Hello World (With Input)</td>
<td>
  <code><pre>
function main({name}) {
  var msg = 'you did not tell me who you are.';
  if (name) {
    msg = `hello \({name}`
  }
  return {body: `&lt;html&gt;&lt;body&gt;&lt;h3&gt;{msg}&lt;/h3&gt;&lt;/body&gt;&lt;/html&gt;`}
}
  </pre></code>
</td>
<td>
  <code><pre>
{
  "value": {
    "name": "Joe"
  }
}</pre></code></td>
<td><code><pre>
&lt;html&gt;&lt;body&gt;&lt;h3&gt;hello Joe&lt;/h3&gt;&lt;/body&gt;&lt;/html&gt;
  </pre></code>
</td>
<td >200</td>
<td >text/html</td>
<td >&#xa0;</td>
</tr>
</tbody>
<tbody>
<tr>
<td>Web Action Hello World (Without Input)</td>
<td>&lt;Same as above&gt;</td>
<td>n/a</td>
<td>
  <code><pre>
&lt;html&gt;&lt;body&gt;&lt;h3&gt;you did not tell me who you are.&lt;/h3&gt;&lt;/body&gt;&lt;/html&gt;
  </pre></code></td>
<td>200</td>
<td>text/html</td>
<td>&#xa0;</td>
</tr>
</tbody>
<tbody>
<tr>
<td>Web Action Raw</td>
<td><code><pre>function main(params) {
  return { response: params };
}</pre></code></td>
<td ><code><pre>{
  "value": {
    "name": "Joe"
  }
}</pre></code></td>
<td ><code><pre>{
  "response": {
    "__ow_body": "eyJuYW1lIjoiSm9lIn0=",
    "__ow_query": {},
    "__ow_user": "",
    "__ow_method": "POST",
    "__ow_headers": {
      "host": "localhost",
      "user-agent": "curl/7.54.0",
      "accept": "*/*",
      "content-type": "application/json",
      "content-length": "394"
    },
    "__ow_path": ""
  }
}</td>
<td >200</td>
<td >application/json</td>
<td >OpenWhisk controller plays an important role in handling web actions and that's why when run from OpenWhisk the response is lacking _<sub>ow</sub><sub>*</sub> parameters</td>
<td>__OW_ACTION_RAW=true</td>
</tr>
</tbody>
<tbody>
<tr>
<td >run sample with init that does nothing</td>
<td >No code Init'd</td>
<td >No Input</td>
<td >No Output</td>
<td >Init should 403</br>Run should 500</td>
<td ></td>
<td >&#xa0;</td>
</tr>
</tbody>
<tbody>
<tr>
<td >deploy a zip based script</td>
<td >Zipped version of Hello World:<code><pre>
function main() {
  return {payload: 'Hello World!'};
}</pre></code></td>
<td ><code><pre>
{
 "value": {
   "name": "Joe",
   "place": "TX"
 }
</td>
<td ><code><pre>{
  "payload": 'Hello World!'
}</pre><code></td>
<td >Init should 200</br>Run should 200</td>
<td >application/json</td>
<td >&#xa0;</td>
</tr>
</tbody>
<tbody>
<tr>
<td >accept a src not-main action</td>
<td >__OW_ACTION_MAIN set to hello:<code><pre>
function hello() {
  return {payload: 'Hello World!'};
}</pre></code></td>
<td ><code><pre>
{
 "value": {
   "name": "Joe",
   "place": "TX"
p }
</td>
<td ><code><pre>{
  "payload": 'Hello World!'
}</pre><code></td>
<td >Init should 200</br>Run should 200</td>
<td >application/json</td>
<td >&#xa0;</td>
<td> __OW_ACTION_MAIN=hello
</tr>
  </tbody>
<tbody>
<tr>
<td >accept a zipped src not-main action</td>
<td >__OW_ACTION_MAIN set to hello and zipped:<code><pre>
function hello() {
  return {payload: 'Hello World!'};
}</pre></code></td>
<td ><code><pre>
{
 "value": {
   "name": "Joe",
   "place": "TX"
 }
</td>
<td ><code><pre>{
  "payload": 'Hello World!'
}</pre><code></td>
<td >Init should 200</br>Run should 200</td>
<td >application/json</td>
<td >&#xa0;</td>
<td>__OW_ACTION_MAIN=hello
</tr>
</tbody>
</table>

## Implementations

### [NodeJS](https://github.com/apache/openwhisk-runtime-nodejs)
---
The links below will point to the [OpenWhisk Test repo](https://github.com/apache/openwhisk-test/) where the example cases are being stored.

| Action Code                                                                                                                                                   | Init                                                                                                                                                                          | Run                                                                                                                                                                         | Init/Run                                                                                                                                                                              | Output                                                                                                                                                               |
|---------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| [Hello World](https://github.com/apache/openwhisk-test/blob/master/runtimes/proxy/single_entrypoint/hello_world.js)                                           | [hello_world-init](https://github.com/apache/openwhisk-test/blob/master/runtimes/proxy/single_entrypoint/input/hello_world-init.json)                                         | [hello_world-run](https://github.com/apache/openwhisk-test/blob/master/runtimes/proxy/single_entrypoint/input/hello_world-run.json)                                         | [hello_world-init-run](https://github.com/apache/openwhisk-test/blob/master/runtimes/proxy/single_entrypoint/input/hello_world-init-run.json)                                         | [hello_world](https://github.com/apache/openwhisk-test/blob/master/runtimes/proxy/single_entrypoint/output/hello_world.json)                                         |
| [Hello World with Params](https://github.com/apache/openwhisk-test/blob/master/runtimes/proxy/single_entrypoint/hello_world_with_params.js)                   | [hello_world_with_params-init](https://github.com/apache/openwhisk-test/blob/master/runtimes/proxy/single_entrypoint/input/hello_world_with_params-init.json)                 | [hello_world_with_params-run](https://github.com/apache/openwhisk-test/blob/master/runtimes/proxy/single_entrypoint/input/hello_world_with_params-run.json)                 | [hello_world_with_params-init-run](https://github.com/apache/openwhisk-test/blob/master/runtimes/proxy/single_entrypoint/input/hello_world_with_params-init-run.json)                 | [hello_world_with_params](https://github.com/apache/openwhisk-test/blob/master/runtimes/proxy/single_entrypoint/output/hello_world_with_params.json)                 |
| [Web Action Hello World](https://github.com/apache/openwhisk-test/blob/master/runtimes/proxy/single_entrypoint/web_action_hello_world.js)                     | [web_action_hello_world-init](https://github.com/apache/openwhisk-test/blob/master/runtimes/proxy/single_entrypoint/input/web_action_hello_world-init.json)                   | [web_action_hello_world-run](https://github.com/apache/openwhisk-test/blob/master/runtimes/proxy/single_entrypoint/input/web_action_hello_world-run.json)                   | [web_action_hello_world-init-run](https://github.com/apache/openwhisk-test/blob/master/runtimes/proxy/single_entrypoint/input/web_action_hello_world-init-run.json)                   | [web_action_hello_world](https://github.com/apache/openwhisk-test/blob/master/runtimes/proxy/single_entrypoint/output/web_action_hello_world.json)                   |
| [Web Action Hello World (no input)](https://github.com/apache/openwhisk-test/blob/master/runtimes/proxy/single_entrypoint/web_action_hello_world_no_input.js) | [web_action_hello_world_no_input-init](https://github.com/apache/openwhisk-test/blob/master/runtimes/proxy/single_entrypoint/input/web_action_hello_world_no_input-init.json) | [web_action_hello_world_no_input-run](https://github.com/apache/openwhisk-test/blob/master/runtimes/proxy/single_entrypoint/input/web_action_hello_world_no_input-run.json) | [web_action_hello_world_no_input-init-run](https://github.com/apache/openwhisk-test/blob/master/runtimes/proxy/single_entrypoint/input/web_action_hello_world_no_input-init-run.json) | [web_action_hello_world_no_input](https://github.com/apache/openwhisk-test/blob/master/runtimes/proxy/single_entrypoint/output/web_action_hello_world_no_input.json) |
| [Web Action Raw](https://github.com/apache/openwhisk-test/blob/master/runtimes/proxy/single_entrypoint/web_action_raw.js)                                     | [web_action_raw-init](https://github.com/apache/openwhisk-test/blob/master/runtimes/proxy/single_entrypoint/input/web_action_raw-init.json)                                   | [web_action_raw-run](https://github.com/apache/openwhisk-test/blob/master/runtimes/proxy/single_entrypoint/input/web_action_raw-run.json)                                   | [web_action_raw-init-run](https://github.com/apache/openwhisk-test/blob/master/runtimes/proxy/single_entrypoint/input/web_action_raw-init-run.json)                                   | [web_action_raw](https://github.com/apache/openwhisk-test/blob/master/runtimes/proxy/single_entrypoint/output/web_action_raw.json)                                   |
