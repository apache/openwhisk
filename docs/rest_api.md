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

# Using REST APIs with OpenWhisk

After your OpenWhisk environment is enabled, you can use OpenWhisk with your web apps or mobile apps with REST API calls.

For more details about the APIs for actions, activations, packages, rules, and triggers, see the [OpenWhisk API documentation](http://petstore.swagger.io/?url=https://raw.githubusercontent.com/openwhisk/openwhisk/master/core/controller/src/main/resources/apiv1swagger.json).


All the capabilities in the system are available through a REST API. There are collection and entity endpoints for actions, triggers, rules, packages, activations, and namespaces.

These are the collection endpoints:
- `https://$APIHOST/api/v1/namespaces`
- `https://$APIHOST/api/v1/namespaces/{namespace}/actions`
- `https://$APIHOST/api/v1/namespaces/{namespace}/triggers`
- `https://$APIHOST/api/v1/namespaces/{namespace}/rules`
- `https://$APIHOST/api/v1/namespaces/{namespace}/packages`
- `https://$APIHOST/api/v1/namespaces/{namespace}/activations`
- `https://$APIHOST/api/v1/namespaces/{namespace}/limits`

The `$APIHOST` is the OpenWhisk API hostname (for example, localhost, 172.17.0.1, and so on).
For the `{namespace}`, the character `_` can be used to specify the user's *default
namespace*.

You can perform a GET request on the collection endpoints to fetch a list of entities in the collection.

There are entity endpoints for each type of entity:
- `https://$APIHOST/api/v1/namespaces/{namespace}`
- `https://$APIHOST/api/v1/namespaces/{namespace}/actions/[{packageName}/]{actionName}`
- `https://$APIHOST/api/v1/namespaces/{namespace}/triggers/{triggerName}`
- `https://$APIHOST/api/v1/namespaces/{namespace}/rules/{ruleName}`
- `https://$APIHOST/api/v1/namespaces/{namespace}/packages/{packageName}`
- `https://$APIHOST/api/v1/namespaces/{namespace}/activations/{activationName}`

The namespace and activation endpoints support only GET requests. The actions, triggers, rules, and packages endpoints support GET, PUT, and DELETE requests. The endpoints of actions, triggers, and rules also support POST requests, which are used to invoke actions and triggers and enable or disable rules.

All APIs are protected with HTTP Basic authentication.
You can use the [wskadmin](../tools/admin/wskadmin) tool to generate a new namespace and authentication.
The Basic authentication credentials are in the `AUTH` property in your `~/.wskprops` file, delimited by a colon.
You can also retrieve these credentials using the CLI running `wsk property get --auth`.


The following is an example that uses the [cURL](https://curl.haxx.se) command tool to get the list of all packages in the `whisk.system` namespace:

```bash
curl -u USERNAME:PASSWORD https://$APIHOST/api/v1/namespaces/whisk.system/packages
```

```json
[
  {
    "name": "slack",
    "binding": false,
    "publish": true,
    "annotations": [
      {
        "key": "description",
        "value": "Package that contains actions to interact with the Slack messaging service"
      }
    ],
    "version": "0.0.1",
    "namespace": "whisk.system"
  }
]
```

In this example the authentication was passed using the `-u` flag, you can pass this value also as part of the URL as `https://USERNAME:PASSWORD@$APIHOST`.

The OpenWhisk API supports request-response calls from web clients. OpenWhisk responds to `OPTIONS` requests with Cross-Origin Resource Sharing headers. Currently, all origins are allowed (that is, Access-Control-Allow-Origin is "`*`"), the standard set of methods are allowed (that is, Access-Control-Allow-Methods is "`GET, DELETE, POST, PUT, HEAD`"), and Access-Control-Allow-Headers yields "`Authorization, Origin, X-Requested-With, Content-Type, Accept, User-Agent`".

**Attention:** Because OpenWhisk currently supports only one key per namespace, it is not recommended to use CORS beyond simple experiments. Use [Web Actions](webactions.md) or [API Gateway](apigateway.md) to expose your actions to the public and not use the OpenWhisk authorization key for client applications that require CORS.

## Using the CLI verbose mode

The OpenWhisk CLI is an interface to the OpenWhisk REST API.
You can run the CLI in verbose mode with the flag `-v`, this will print truncated information about the HTTP request and response. To print all information use the flag `-d` for debug.

**Note:** HTTP request and response bodies will only be truncated if they exceed 1000 bytes.

Let's try getting the namespace value for the current user.
```
wsk namespace list -v
```
```
REQUEST:
[GET]  https://$APIHOST/api/v1/namespaces
Req Headers
{
  "Authorization": [
    "Basic XXXYYYY"
  ],
  "User-Agent": [
    "OpenWhisk-CLI/1.0 (2017-08-10T20:09:30+00:00)"
  ]
}
RESPONSE:Got response with code 200
Resp Headers
{
  "Content-Type": [
    "application/json; charset=UTF-8"
  ]
}
Response body size is 28 bytes
Response body received:
["john@example.com_dev"]
```

As you can see you the printed information provides the properties of the HTTP request, it performs a HTTP method `GET` on the URL `https://$APIHOST/api/v1/namespaces` using a User-Agent header `OpenWhisk-CLI/1.0 (<CLI-Build-version>)` and Basic Authorization header `Basic XXXYYYY`.
Notice that the authorization value is your base64-encoded OpenWhisk authorization string.
The response is of content type `application/json`.

## Actions

**Note:** In the examples that follow, `$AUTH` and `$APIHOST` represent environment variables set respectively to your OpenWhisk authorization key and API host.

To create or update an action send a HTTP request with method `PUT` on the the actions collection. For example, to create a `nodejs:6` action with the name `hello` using a single file content use the following:
```bash
curl -u $AUTH -d '{"namespace":"_","name":"hello","exec":{"kind":"nodejs:6","code":"function main(params) { return {payload:\"Hello \"+params.name}}"}}' -X PUT -H "Content-Type: application/json" https://$APIHOST/api/v1/namespaces/_/actions/hello?overwrite=true
```

To perform a blocking invocation on an action, send a HTTP request with a method `POST` and body containing the input parameter `name` use the following:
```bash
curl -u $AUTH https://$APIHOST/api/v1/namespaces/_/actions/hello?blocking=true \
-X POST -H "Content-Type: application/json" \
-d '{"name":"John"}'
```
You get the following response:
```json
{
  "duration": 2,
  "name": "hello",
  "subject": "john@example.com_dev",
  "activationId": "c7bb1339cb4f40e3a6ccead6c99f804e",
  "publish": false,
  "annotations": [{
    "key": "limits",
    "value": {
      "timeout": 60000,
      "memory": 256,
      "logs": 10
    }
  }, {
    "key": "path",
    "value": "john@example.com_dev/hello"
  }],
  "version": "0.0.1",
  "response": {
    "result": {
      "payload": "Hello John"
    },
    "success": true,
    "status": "success"
  },
  "end": 1493327653769,
  "logs": [],
  "start": 1493327653767,
  "namespace": "john@example.com_dev"
}
```
If you just want to get the `response.result`, run the command again with the query parameter `result=true`
```bash
curl -u $AUTH "https://$APIHOST/api/v1/namespaces/_/actions/hello?blocking=true&result=true" \
-X POST -H "Content-Type: application/json" \
-d '{"name":"John"}'
```
You get the following response:
```json
{
  "payload": "hello John"
}
```

## Annotations and Web Actions

To create an action as a web action, you need to add an [annotation](annotations.md) of `web-export=true` for web actions. Since web-actions are publicly accessible, you should protect pre-defined parameters (i.e., treat them as final) using the annotation `final=true`. If you create or update an action using the CLI flag `--web true` this command will add both annotations `web-export=true` and `final=true`.

Run the curl command providing the complete list of annotations to set on the action
```bash
curl -u $AUTH https://$APIHOST/api/v1/namespaces/_/actions/hello?overwrite=true \
-X PUT -H "Content-Type: application/json" \
-d '{"namespace":"_","name":"hello","exec":{"kind":"nodejs:6","code":"function main(params) { return {payload:\"Hello \"+params.name}}"},"annotations":[{"key":"web-export","value":true},{"key":"raw-http","value":false},{"key":"final","value":true}]}'
```
You can now invoke this action as a public URL with no OpenWhisk authorization. Try invoking using the web action public URL including an optional extension such as `.json` or `.http` for example at the end of the URL.
```bash
curl https://$APIHOST/api/v1/web/john@example.com_dev/default/hello.json?name=John
```
```json
{
  "payload": "Hello John"
}
```
Note that this example source code will not work with `.http`, see [web actions](webactions.md) documentation on how to modify.

## Sequences

To create an action sequence, you need to create it by providing the names of the actions that compose the sequence in the desired order, so the output from the first action is passed as input to the next action.

$ wsk action create sequenceAction --sequence /whisk.system/utils/split,/whisk.system/utils/sort

Create a sequence with the actions `/whisk.system/utils/split` and `/whisk.system/utils/sort`.
```bash
curl -u $AUTH https://$APIHOST/api/v1/namespaces/_/actions/sequenceAction?overwrite=true \
-X PUT -H "Content-Type: application/json" \
-d '{"namespace":"_","name":"sequenceAction","exec":{"kind":"sequence","components":["/whisk.system/utils/split","/whisk.system/utils/sort"]},"annotations":[{"key":"web-export","value":true},{"key":"raw-http","value":false},{"key":"final","value":true}]}'
```

Take into account when specifying the names of the actions, they have to be full qualified.

## Triggers

To create a trigger, the minimum information you need is a name for the trigger. You could also include default parameters that get passed to the action through a rule when the trigger gets fired.

Create a trigger with name `events` with a default parameter `type` with value `webhook` set.
```bash
curl -u $AUTH https://$APIHOST/api/v1/namespaces/_/triggers/events?overwrite=true \
-X PUT -H "Content-Type: application/json" \
-d '{"name":"events","parameters":[{"key":"type","value":"webhook"}]}'
```

Now whenever you have an event that needs to fire this trigger it just takes an HTTP request with a method `POST` using the OpenWhisk Authorization key.

To fire the trigger `events` with a parameter `temperature`, send the following HTTP request.

```bash
curl -u $AUTH https://$APIHOST/api/v1/namespaces/_/triggers/events \
-X POST -H "Content-Type: application/json" \
-d '{"temperature":60}'
```

### Triggers with Feed Actions

There are special triggers that can be created using a feed action. The feed action configures a feed provider such that events from the provider results in triggers being fired. Learn more about these feed providers in the [feeds.md] documentation.

Some of the available triggers that leverage a feed action are periodic/alarms, Slack, Github, Cloudant/Couchdb, and messageHub/Kafka. You also can create your own feed action and feed provider.

Let's create a trigger with name `periodic` to be fired at a specified frequency, every 2 hours (i.e. 02:00:00, 04:00:00, ...).

Using the CLI this will be done with one command
```bash
wsk trigger create periodic --feed /whisk.system/alarms/alarm \
  --param cron "0 */2 * * *" -v
```
As you will see because we are using the `-v` flag is that two HTTP requests are sent, one is to create a trigger `periodic` and the other is to invoke a feed action `/whisk.system/alarms/alarm` with the parameters to configure the feed provider to fire the trigger every 2 hours.

To do the same with the REST API, lets create the trigger first
```bash
curl -u $AUTH https://$APIHOST/api/v1/namespaces/_/triggers/periodic?overwrite=true \
-X PUT -H "Content-Type: application/json" \
-d '{"name":"periodic","annotations":[{"key":"feed","value":"/whisk.system/alarms/alarm"}]}'
```

As you can see the annotation `feed` is stored in the trigger. Later we will use this annotation to know which feed action to use when deleting the trigger.

Now that the trigger is created, lets invoke the feed action
```bash
curl -u $AUTH "https://$APIHOST/api/v1/namespaces/whisk.system/actions/alarms/alarm?blocking=true&result=false" \
-X POST -H "Content-Type: application/json" \
-d "{\"authKey\":\"$AUTH\",\"cron\":\"0 */2 * * *\",\"lifecycleEvent\":\"CREATE\",\"triggerName\":\"/_/periodic\"}"
```

Deleting the trigger is a similar to creating the trigger, this time deleting the trigger and also using the feed action to configure the feed provider to delete the handler for the trigger.

Invoke the feed action to delete the trigger handler from the feed provider
```bash
curl -u $AUTH "https://$APIHOST/api/v1/namespaces/whisk.system/actions/alarms/alarm?blocking=true&result=false" \
-X POST -H "Content-Type: application/json" \
-d "{\"authKey\":\"$AUTH\",\"lifecycleEvent\":\"DELETE\",\"triggerName\":\"/_/periodic\"}"
```

Now delete the trigger with a HTTP request using `DELETE` method
```bash
curl -u $AUTH https://$APIHOST/api/v1/namespaces/_/triggers/periodic \
-X DELETE -H "Content-Type: application/json"
```

## Rules

To create a rule that associates a trigger with an action, send a HTTP request with a `PUT` method providing the trigger and action in the body of the request.
```bash
curl -u $AUTH https://$APIHOST/api/v1/namespaces/_/rules/t2a?overwrite=true \
-X PUT -H "Content-Type: application/json" \
-d '{"name":"t2a","status":"","trigger":"/_/events","action":"/_/hello"}'
```

Rules can be enabled or disabled, and you can change the status of the rule by updating its status property. For example, to disable the rule `t2a` send in the body of the request `status: "inactive"` with a `POST` method.
```bash
curl -u $AUTH https://$APIHOST/api/v1/namespaces/_/rules/t2a?overwrite=true \
-X POST -H "Content-Type: application/json" \
-d '{"status":"inactive","trigger":null,"action":null}'
```

## Packages

To create an action in a package you have to create a package first, to create a package with name `iot` send an HTTP request with a `PUT` method

```bash
curl -u $AUTH https://$APIHOST/api/v1/namespaces/_/packages/iot?overwrite=true \
-X PUT -H "Content-Type: application/json" \
-d '{"namespace":"_","name":"iot"}'
```

To force delete a package that contains entities, set the force parameter to true. Failure
will return an error either for failure to delete an action within the package or the package itself.
The package will not be attempted to be deleted until all actions are successfully deleted.

```bash
curl -u $AUTH https://$APIHOST/api/v1/namespaces/_/packages/iot?force=true \
-X DELETE
```

## Activations

To get the list of the last 3 activations use a HTTP request with a `GET` method, passing the query parameter `limit=3`
```bash
curl -u $AUTH https://$APIHOST/api/v1/namespaces/_/activations?limit=3
```

To get all the details of an activation including results and logs, send a HTTP request with a `GET` method passing the activation identifier as a path parameter
```bash
curl -u $AUTH https://$APIHOST/api/v1/namespaces/_/activations/f81dfddd7156401a8a6497f2724fec7b
```

## Limits

To get the limits set for a namespace (i.e. invocationsPerMinute, concurrentInvocations, firesPerMinute)
```bash
curl -u $AUTH https://$APIHOST/api/v1/namespaces/_/limits
```
Note that the default system values are returned if no specific limits are set for the user corresponding to the authenticated identity.
