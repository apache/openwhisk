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

# Web Actions

Web actions are OpenWhisk actions annotated to quickly enable you to build web based applications. This allows you to program backend logic which your web application can access anonymously without requiring an OpenWhisk authentication key. It is up to the action developer to implement their own desired authentication and authorization (i.e. OAuth flow).

Web action activations will be associated with the user that created the action. This actions defers the cost of an action activation from the caller to the owner of the action.

Let's take the following JavaScript action `hello.js`,
```javascript
$ cat hello.js
function main({name}) {
  var msg = 'you did not tell me who you are.';
  if (name) {
    msg = `hello ${name}!`
  }
  return {body: `<html><body><h3>${msg}</h3></body></html>`}
}
```

You may create a _web action_ `hello` in the package `demo` for the namespace `guest` using the CLI's `--web` flag with a value of `true` or `yes`:
```bash
$ wsk package create demo
ok: created package demo
```

```
$ wsk action create /guest/demo/hello hello.js --web true
ok: created action /guest/demo/hello
```

```
$ wsk action get /guest/demo/hello --url
ok: got action hello
https://${APIHOST}/api/v1/web/guest/demo/hello
```

Using the `--web` flag with a value of `true` or `yes` allows an action to be accessible via REST interface without the
need for credentials. To configure a web action with credentials refer to [Securing web actions](webactions.md#securing-web-actions). A web action can be invoked using a URL that is structured as follows:
`https://{APIHOST}/api/v1/web/{QUALIFIED ACTION NAME}.{EXT}`. The fully qualified name of an action consists of three
parts: the namespace, the package name, and the action name.

*The fully qualified name of the action must include its package name, which is `default` if the action is not in a named package.*

An example is `guest/demo/hello`. The last part of the URI called the `extension` which is typically `.http` although other values are permitted as described later. The web action API path may be used with `curl` or `wget` without an API key. It may even be entered directly in your browser.

Try opening [https://${APIHOST}/api/v1/web/guest/demo/hello.http?name=Jane](https://${APIHOST}/api/v1/web/guest/demo/hello.http?name=Jane) in your web browser. Or try invoking the action via `curl`:
```
$ curl https://${APIHOST}/api/v1/web/guest/demo/hello.http?name=Jane
```

Here is an example of a web action that performs an HTTP redirect:
```javascript
function main() {
  return {
    headers: { location: 'http://openwhisk.org' },
    statusCode: 302
  }
}
```

Or sets a cookie:
```javascript
function main() {
  return {
    headers: {
      'Set-Cookie': 'UserID=Jane; Max-Age=3600; Version=',
      'Content-Type': 'text/html'
    },
    statusCode: 200,
    body: '<html><body><h3>hello</h3></body></html>' }
}
```

Or sets multiple cookies:
```javascript
function main() {
  return {
    headers: {
      'Set-Cookie': [
        'UserID=Jane; Max-Age=3600; Version=',
        'SessionID=asdfgh123456; Path = /'
      ],
      'Content-Type': 'text/html'
    },
    statusCode: 200,
    body: '<html><body><h3>hello</h3></body></html>' }
}
```

Or returns an `image/png`:
```javascript
function main() {
    let png = <base 64 encoded string>
    return { headers: { 'Content-Type': 'image/png' },
             statusCode: 200,
             body: png };
}
```

Or returns `application/json`:
```javascript
function main(params) {
    return {
        statusCode: 200,
        headers: { 'Content-Type': 'application/json' },
        body: params
    };
}
```

The default content-type for an HTTP response is `application/json` and the body may be any allowed JSON value. The default content-type may be omitted from the headers.

It is important to be aware of the [response size limit](reference.md) for actions since a response that exceeds the predefined system limits will fail. Large objects should not be sent inline through OpenWhisk, but instead deferred to an object store, for example.

## Handling HTTP requests with actions

An OpenWhisk action that is not a web action requires both authentication and must respond with a JSON object. In contrast, web actions may be invoked without authentication, and may be used to implement HTTP handlers that respond with _headers_, _statusCode_, and _body_ content of different types. The web action must still return a JSON object, but the OpenWhisk system (namely the `controller`) will treat a web action differently if its result includes one or more of the following as top level JSON properties:

1. `headers`: a JSON object where the keys are header-names and the values are string, number, or boolean values for those headers (default is no headers). To send multiple values for a single header, the header's value should be a JSON array of values.
1. `statusCode`: a valid HTTP status code (default is 200 OK if body is not empty otherwise 204 No Content).
1. `body`: a string which is either plain text, JSON object or array, or a base64 encoded string for binary data (default is empty response).

The `body` is considered empty if it is `null`, the empty string `""` or undefined.

The controller will pass along the action-specified headers, if any, to the HTTP client when terminating the request/response. Similarly the controller will respond with the given status code when present. Lastly, the body is passed along as the body of the response. If a `content-type header` is not declared in the action result’s `headers`, the body is interpreted as `application/json` for non-string values, and `text/html` otherwise. When the `content-type` is defined, the controller will determine if the response is binary data or plain text and decode the string using a base64 decoder as needed. Should the body fail to decoded correctly, an error is returned to the caller.

## HTTP Context

All web actions, when invoked, receives additional HTTP request details as parameters to the action input argument. They are:

1. `__ow_method` (type: string): the HTTP method of the request.
1. `__ow_headers` (type: map string to string): the request headers.
1. `__ow_path` (type: string): the unmatched path of the request (matching stops after consuming the action extension).
1. `__ow_user` (type: string): the namespace identifying the OpenWhisk authenticated subject.
1. `__ow_body` (type: string): the request body entity, as a base64 encoded string when content is binary or JSON object/array, or plain string otherwise.
1. `__ow_query` (type: string): the query parameters from the request as an unparsed string.

A request may not override any of the named `__ow_` parameters above; doing so will result in a failed request with status equal to 400 Bad Request.

The `__ow_user` is only present when the web action is [annotated to require authentication](annotations.md#annotations-specific-to-web-actions) and allows a web action to implement its own authorization policy. The `__ow_query` is available only when a web action elects to handle the ["raw" HTTP request](#raw-http-handling). It is a string containing the query parameters parsed from the URI (separated by `&`). The `__ow_body` property is present either when handling "raw" HTTP requests, or when the HTTP request entity is not a JSON object or form data. Web actions otherwise receive query and body parameters as first class properties in the action arguments with body parameters taking precedence over query parameters, which in turn take precedence over action and package parameters.

## Additional features

Web actions bring some additional features that include:

1. `Content extensions`: the request must specify its desired content type as one of `.json`, `.html`, `.http`, `.svg` or `.text`. This is done by adding an extension to the action name in the URI, so that an action `/guest/demo/hello` is referenced as `/guest/demo/hello.http` for example to receive an HTTP response back. For convenience, the `.http` extension is assumed when no extension is detected.
1. `Query and body parameters as input`: the action receives query parameters as well as parameters in the request body. The precedence order for merging parameters is: package parameters, binding parameters, action parameters, query parameter, body parameters with each of these overriding any previous values in case of overlap . As an example `/guest/demo/hello.http?name=Jane` will pass the argument `{name: "Jane"}` to the action.
1. `Form data`: in addition to the standard `application/json`, web actions may receive URL encoded from data `application/x-www-form-urlencoded data` as input.
1. `Activation via multiple HTTP verbs`: a web action may be invoked via any of these HTTP methods: `GET`, `POST`, `PUT`, `PATCH`, and `DELETE`, as well as `HEAD` and `OPTIONS`.
1. `Non JSON body and raw HTTP entity handling`: A web action may accept an HTTP request body other than a JSON object, and may elect to always receive such values as opaque values (plain text when not binary, or base64 encoded string otherwise).

The example below briefly sketches how you might use these features in a web action. Consider an action `/guest/demo/hello` with the following body:
```javascript
function main(params) {
    return { response: params };
}
```

This is an example of invoking the web action using the `.json` extension, indicating a JSON response.

```bash
$ curl https://${APIHOST}/api/v1/web/guest/demo/hello.json
{
  "response": {
    "__ow_method": "get",
    "__ow_headers": {
      "accept": "*/*",
      "connection": "close",
      "host": "172.17.0.1",
      "user-agent": "curl/7.43.0"
    },
    "__ow_path": ""
  }
}
```

You can supply query parameters.

```bash
$ curl https://${APIHOST}/api/v1/web/guest/demo/hello.json?name=Jane
{
  "response": {
    "name": "Jane",
    "__ow_method": "get",
    "__ow_headers": {
      "accept": "*/*",
      "connection": "close",
      "host": "172.17.0.1",
      "user-agent": "curl/7.43.0"
    },
    "__ow_path": ""
  }
}
```

You may use form data as input.

```bash
$ curl https://${APIHOST}/api/v1/web/guest/demo/hello.json -d "name":"Jane"
{
  "response": {
    "name": "Jane",
    "__ow_method": "post",
    "__ow_headers": {
      "accept": "*/*",
      "connection": "close",
      "content-length": "10",
      "content-type": "application/x-www-form-urlencoded",
      "host": "172.17.0.1",
      "user-agent": "curl/7.43.0"
    },
    "__ow_path": ""
  }
}
```

You may also invoke the action with a JSON object.

```bash
$ curl https://${APIHOST}/api/v1/web/guest/demo/hello.json -H 'Content-Type: application/json' -d '{"name":"Jane"}'
{
  "response": {
    "name": "Jane",
    "__ow_method": "post",
    "__ow_headers": {
      "accept": "*/*",
      "connection": "close",
      "content-length": "15",
      "content-type": "application/json",
      "host": "172.17.0.1",
      "user-agent": "curl/7.43.0"
    },
    "__ow_path": ""
  }
}
```

You see above that for convenience, query parameters, form data, and JSON object body entities are all treated as dictionaries, and their values are directly accessible as action input properties. This is not the case for web actions which opt to instead handle HTTP request entities more directly, or when the web action receives an entity that is not a JSON object.

Here is an example of using a "text" content-type with the same example shown above.

```bash
$ curl https://${APIHOST}/api/v1/web/guest/demo/hello.json -H 'Content-Type: text/plain' -d "Jane"
{
  "response": {
    "__ow_method": "post",
    "__ow_headers": {
      "accept": "*/*",
      "connection": "close",
      "content-length": "4",
      "content-type": "text/plain",
      "host": "172.17.0.1",
      "user-agent": "curl/7.43.0"
    },
    "__ow_path": "",
    "__ow_body": "Jane"
  }
}
```


## Content extensions

A content extension is generally required when invoking a web action; the absence of an extension assumes `.http` as the default. The fully qualified name of the action must include its package name, which is `default` if the action is not in a named package.


## Protected parameters

Action parameters are protected and treated as immutable. Parameters are automatically finalized when enabling web actions.

```bash
$ wsk action create /guest/demo/hello hello.js \
      --parameter name Jane \
      --web true
```

The result of these changes is that the `name` is bound to `Jane` and may not be overridden by query or body parameters because of the final annotation. This secures the action against query or body parameters that try to change this value whether by accident or intentionally.

## Securing web actions

By default, a web action can be invoked by anyone having the web action's invocation URL. Use the `require-whisk-auth` [web action annotation](annotations.md#annotations-specific-to-web-actions) to secure the web action. When the `require-whisk-auth` annotation is set to `true`, the action will authenticate the invocation request's Basic Authorization credentials to confirm they represent a valid OpenWhisk identity.  When set to a number or a case-sensitive string, the action's invocation request must include a `X-Require-Whisk-Auth` header having this same value. Secured web actions will return a `Not Authorized` when credential validation fails.

Alternatively, use the `--web-secure` flag to automatically set the `require-whisk-auth` annotation.  When set to `true` a random number is generated as the `require-whisk-auth` annotation value. When set to `false` the `require-whisk-auth` annotation is removed.  When set to any other value, that value is used as the `require-whisk-auth` annotation value.

```bash
$ wsk action update /guest/demo/hello hello.js --web true --web-secure my-secret
```
or
```bash
$ wsk action update /guest/demo/hello hello.js --web true -a require-whisk-auth my-secret
```

```bash
$ curl https://${APIHOST}/api/v1/web/guest/demo/hello.json?name=Jane -X GET -H "X-Require-Whisk-Auth: my-secret"
```

It's important to note that the owner of the web action owns all of the web action's activations records and will incur the cost of running the action in the system regardless of how the action was invoked.

## Disabling web actions

To disable a web action from being invoked via web API (`https://APIHOST/api/v1/web/`), pass a value of `false` or `no` to the `--web` flag while updating an action with the CLI.

```bash
$ wsk action update /guest/demo/hello hello.js --web false
```

## Raw HTTP handling

A web action may elect to interpret and process an incoming HTTP body directly, without the promotion of a JSON object to first class properties available to the action input (e.g., `args.name` vs parsing `args.__ow_query`). This is done via a `raw-http` [annotation](annotations.md). Using the same example show earlier, but now as a "raw" HTTP web action receiving `name` both as a query parameters and as JSON value in the HTTP request body:
```bash
$ curl https://${APIHOST}/api/v1/web/guest/demo/hello.json?name=Jane -X POST -H "Content-Type: application/json" -d '{"name":"Jane"}'
{
  "response": {
    "__ow_method": "post",
    "__ow_query": "name=Jane",
    "__ow_body": "eyJuYW1lIjoiSmFuZSJ9",
    "__ow_headers": {
      "accept": "*/*",
      "connection": "close",
      "content-length": "15",
      "content-type": "application/json",
      "host": "172.17.0.1",
      "user-agent": "curl/7.43.0"
    },
    "__ow_path": ""
  }
}
```

OpenWhisk uses the [Akka Http](http://doc.akka.io/docs/akka-http/current/scala/http/) framework to [determine](http://doc.akka.io/api/akka-http/10.0.4/akka/http/scaladsl/model/MediaTypes$.html) which content types are binary and which are plain text.


### Enabling raw HTTP handling

Raw HTTP web actions are enabled via the `--web` flag using a value of `raw`.

```bash
$ wsk action create /guest/demo/hello hello.js --web raw
```

### Disabling raw HTTP handling

Disabling raw HTTP can be accomplished by passing a value of `false` or `no` to the `--web` flag.

```bash
$ wsk update create /guest/demo/hello hello.js --web false
```

### Decoding binary body content from Base64

When using raw HTTP handling, the `__ow_body` content will be encoded in Base64 when the request content-type is binary.
Below are functions demonstrating how to decode the body content in Node, Python, Swift and PHP. Simply save a method shown
below to file, create a raw HTTP web action utilizing the saved artifact, and invoke the web action.

#### Node

```javascript
function main(args) {
    decoded = new Buffer(args.__ow_body, 'base64').toString('utf-8')
    return {body: decoded}
}
```

#### Python

```python
def main(args):
    try:
        decoded = args['__ow_body'].decode('base64').strip()
        return {"body": decoded}
    except:
        return {"body": "Could not decode body from Base64."}
```

#### Swift

```swift
extension String {
    func base64Decode() -> String? {
        guard let data = Data(base64Encoded: self) else {
            return nil
        }

        return String(data: data, encoding: .utf8)
    }
}

func main(args: [String:Any]) -> [String:Any] {
    if let body = args["__ow_body"] as? String {
        if let decoded = body.base64Decode() {
            return [ "body" : decoded ]
        }
    }

    return ["body": "Could not decode body from Base64."]
}
```

#### PHP

```php
<?php

function main(array $args) : array
{
    $decoded = base64_decode($args['__ow_body']);
    return ["body" => $decoded];
}
```

As an example, save the Node function as `decode.js` and execute the following commands:
```bash
$ wsk action create decode decode.js --web raw
ok: created action decode
$ curl -k -H "content-type: application" -X POST -d "Decoded body" https://${APIHOST}/api/v1/web/guest/default/decodeNode.json
{
  "body": "Decoded body"
}
```
## Options Requests

By default, an OPTIONS request made to a web action will result in CORS headers being automatically added to the
response headers. These headers allow all origins and the options, get, delete, post, put, head, and patch HTTP verbs.
In addition, the header `Access-Control-Request-Headers` is echoed back as the header `Access-Control-Allow-Headers`
if it is present in the HTTP request. Otherwise, a default value is generated as shown below.

```
Access-Control-Allow-Origin: *
Access-Control-Allow-Methods: OPTIONS, GET, DELETE, POST, PUT, HEAD, PATCH
Access-Control-Allow-Headers: Authorization, Origin, X-Requested-With, Content-Type, Accept, User-Agent
```

Alternatively, OPTIONS requests can be handled manually by a web action. To enable this option add a
`web-custom-options` annotation with a value of `true` to a web action. When this feature is enabled, CORS headers will
not automatically be added to the request response. Instead, it is the developer's responsibility to append their
desired headers programmatically. Below is an example of creating custom responses to OPTIONS requests.

```
function main(params) {
  if (params.__ow_method == "options") {
    return {
      headers: {
        'Access-Control-Allow-Methods': 'OPTIONS, GET',
        'Access-Control-Allow-Origin': 'example.com'
      },
      statusCode: 200
    }
  }
}
```

Save the above function to `custom-options.js` and execute the following commands:

```
$ wsk action create custom-option custom-options.js --web true -a web-custom-options true
$ curl https://${APIHOST}/api/v1/web/guest/default/custom-options.http -kvX OPTIONS
< HTTP/1.1 200 OK
< Server: nginx/1.11.13
< Content-Length: 0
< Connection: keep-alive
< Access-Control-Allow-Methods: OPTIONS, GET
< Access-Control-Allow-Origin: example.com
```

## Web Actions in Shared Packages

A web action in a shared (i.e., public) package is accessible as a web action either directly via the package's fully
qualified name, or via a package binding. It is important to note that a web action in a public package will be
accessible for all bindings of the package even if the binding is private. This is because the web action annotation
is carried on the action and cannot be overridden. If you do not wish to expose a web action through your package
bindings, then you should clone-and-own the package instead.

Action parameters are inherited from its package, and the binding if there is one. You can make package parameters
[immutable](./annotations.md#protected-parameters) by defining their values through a package binding.

## Error Handling

When an OpenWhisk action fails, there are two different failure modes. The first is known as an _application error_ and is analogous to a caught exception: the action returns a JSON object containing a top level `error` property. The second is a _developer error_ which occurs when the action fails catastrophically and does not produce a response (this is similar to an uncaught exception). For web actions, the controller handles application errors as follows:

1. The controller projects an `error` property from the response object.
1. The controller applies the content handling implied by the action extension to the value of the `error` property.

Developers should be aware of how web actions might be used and generate error responses accordingly. For example, a web action that is used with the `.http` extension
should return an HTTP response, for example: `{error: { statusCode: 400 }`. Failing to do so will in a mismatch between the implied content-type from the extension and the action content-type in the error response. Special consideration must be given to web actions that are sequences, so that components that make up a sequence can generate adequate errors when necessary.

## Vanity URL

Web actions may be accessed via an alternate URL which treats the OpenWhisk namespace as a subdomain to the API host. This is suitable for developing web actions that use cookies or local storage so that data is not inadvertently made visible to other web actions in other namespaces. The namespaces must match the regular expression `[a-zA-Z0-9-]+` (and should be 63 characters or fewer) for the edge router to rewrite the subdomain to the corresponding URI. For a conforming namespace, the URL `https://guest.openwhisk-host/public/index.html` becomes a alias for `https://openwhisk-host/api/v1/web/guest/public/index.html`.

For added convenience, and to provide the equivalent of an `index.html`, the edge router will also proxy `https://guest.openwhisk-host` to `https://openwhisk-host/api/v1/web/guest/public/index.html` where `/guest/public/index.html` (i.e., action is called `index` in a package called `public`) is a web action that responds with HTML content. If the action does not exist, the API host will respond with 404 Not Found.

For a local deployment, you will need to provide name resolution for the vanity URL to work. The easiest solution is to add an entry in `/etc/host` for `<namespace>.openwhisk-host`, as in:
```bash
127.0.0.1  guest.openwhisk-host
```
or using a name resolver in combination with `curl` for example, as in:
```bash
$ curl -k https://guest.openwhisk-host --resolve guest.openwhisk-host:443:127.0.0.1
```

You also need to generate an edge router configuration (and SSL certificate) that uses the proper hostname. This may be done by modifying a proper host name (see [global environment variables](../ansible/group_vars/all#L18)) and running the [`setup.yml`](../ansible/setup.yml) and [`edge.yml`](../ansible/edge.yml) playbooks.
