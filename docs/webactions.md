# Web Actions (Experimental)

Web actions are OpenWhisk actions annotated to quickly enable you to build web based applications. This allows you to program backend logic which your web application can access anonymously without requiring an OpenWhisk authentication key. It is up to the action developer to implement their own desired authentication and authorization (i.e. OAuth flow).

Web action activations will be associated with the user that created the action. This actions defers the cost of an action activation from the caller to the owner of the action. **Note**: This feature is currently an experimental offering that enables users an early opportunity to try it out and provide feedback.

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

You may create a _web action_ `hello` in the package `demo` for the namespace `guest` using the annotation `web-export`:
```bash
$ wsk package create demo
$ wsk action create /guest/demo/hello hello.js -a web-export true
```

The `web-export` annotation allows the action to be accessible as a web action via a new REST interface. The URL that is structured as follows: `https://{APIHOST}/api/v1/web/{QUALIFIED ACTION NAME}.{EXT}`. The fully qualified name of an action consists of three parts: the namespace, the package name, and the action name.

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
        body: new Buffer(JSON.stringify(params)).toString('base64'),
    };
}
```

It is important to be aware of the [response size limit](reference.md) for actions since a response that exceeds the predefined system limits will fail. Large objects should not be sent inline through OpenWhisk, but instead deferred to an object store, for example.

## Handling HTTP requests with actions

An OpenWhisk action that is not a web action requires both authentication and must respond with a JSON object. In contrast, web actions may be invoked without authentication, and may be used to implement HTTP handlers that respond with _headers_, _statusCode_, and _body_ content of different types. The web action must still return a JSON object, but the OpenWhisk system (namely the `controller`) will treat a web action differently if its result includes one or more of the following as top level JSON properties:

1. `headers`: a JSON object where the keys are header-names and the values are string values for those headers (default is no headers).
2. `statusCode`: a valid HTTP status code (default is 200 OK).
3. `body`: a string which is either plain text or a base64 encoded string (for binary data).

The controller will pass along the action-specified headers, if any, to the HTTP client when terminating the request/response. Similarly the controller will respond with the given status code when present. Lastly, the body is passed along as the body of the response. Unless a `content-type header` is declared in the action result’s `headers`, the body is passed along as is if it’s a string (or results in an error otherwise). When the `content-type` is defined, the controller will determine if the response is binary data or plain text and decode the string using a base64 decoder as needed. Should the body fail to decoded correctly, an error is returned to the caller.

_Note_: A JSON object or array is treated as binary data and must be base64 encoded as shown in the example above.

## HTTP Context

A web action, when invoked, receives all the HTTP request information available as additional parameters to the action input argument. They are:

1. `__ow_method`: the HTTP method of the request.
2. `__ow_headers`: the request headers.
3. `__ow_path`: the unmatched path of the request (matching stops after consuming the action extension).

The request may not override any of the named `__ow_` parameters above; doing so will result in a failed request with status equal to 400 Bad Request.

## Additional features

Web actions bring some additional features that include:

1. `Content extensions`: the request must specify its desired content type as one of `.json`, `.html`, `.text` or `.http`. This is done by adding an extension to the action name in the URI, so that an action `/guest/demo/hello` is referenced as `/guest/demo/hello.http` for example to receive an HTTP response back.
2. `Projecting fields from the result`: the path that follows the action name is used to project out one or more levels of the response. For example, 
`/guest/demo/hello.html/body`. This allows an action which returns a dictionary `{body: "..." }` to project the `body` property and directly return its string value instead. The projected path follows an absolute path model (as in XPath).
3. `Query and body parameters as input`: the action receives query parameters as well as parameters in the request body. The precedence order for merging parameters is: package parameters, action parameters, query parameter, body parameters with each of these overriding any previous values in case of overlap . As an example `/guest/demo/hello.http?name=Jane` will pass the argument `{name: "Jane"}` to the action.
4. `Form data`: in addition to the standard `application/json`, web actions may receive URL encoded from data `application/x-www-form-urlencoded data` as input.
5. `Activation via multiple HTTP verbs`: a web action may be invoked via one of four HTTP methods: `GET`, `POST`, `PUT` or `DELETE`.


The example below briefly sketches how you might use these features in a web action. Consider an action `/guest/demo/hello` with the following body:
```javascript
function main(params) { 
    return { response: params };
}
```

When this action is invoked as a web action, you can alter the response of the web action by projecting different paths from the result.
For example, to return the entire object, and see what arguments the action receives:

```bash
$ curl https://${APIHOST}/api/v1/web/guest/demo/hello.json
{
  "response": {
    "__ow_method": "get",
    "__ow_headers": {
      "host": "172.17.0.1",
      "connection": "close",
      "user-agent": "curl/7.43.0",
      "accept": "*/*"
    },
    "__ow_path": ""
  }
}
```

and with a query parameter:
```bash
$ curl https://${APIHOST}/api/v1/web/guest/demo/hello.json?name=Jane
{
  "response": {
    "name": "Jane",
    "__ow_verb": "get",
    "__ow_headers": {
      "host": "172.17.0.1",
      "connection": "close",
      "user-agent": "curl/7.43.0",
      "accept": "*/*"
    },
    "__ow_path": ""
  }
}
```

and to project just the name (as text):
```bash
$ curl https://${APIHOST}/api/v1/web/guest/demo/hello.text/response/name?name=Jane
Jane
```

## Content extensions

A content extension is required when invoking a web action. The `.json` and `.http` extensions do not require a projection path. The `.text` and `.html` extensions do, however for convenience, the default path is assumed to match the extension name. So to invoke a web action and receive an `.html` response, the action must respond with a JSON object that contains a top level property called `html` (or the response must be in the explicitly given path). In other words, `/guest/demo/hello.html` is equivalent to projecting the `html` property explicitly, as in `/guest/demo/hello.html/html`. The fully qualified name of the action must include its package name, which is `default` if the action is not in a named package.


## Protected parameters

Action parameters may also be protected and treated as immutable. To finalize parameters, and to make an action web accessible, two [annotations](annotations.md) must be attached to the action: `final` and `web-export` either of which must be set to `true` to have affect. Revisiting the action deployment earlier, we add the annotations as follows:

```bash
$ wsk action create /guest/demo/hello hello.js \
      --parameter name Jane \
      --annotation final true \
      --annotation web-export true
```

The result of these changes is that the `name` is bound to `Jane` and may not be overridden by query or body parameters because of the final annotation. This secures the action against query or body parameters that try to change this value whether by accident or intentionally. 

## Disabling web actions

To disable a web action from being invoked via the new API (`https://APIHOST/api/v1/web/`), it’s enough to remove the annotation or set it to `false`.

```bash
$ wsk action update /guest/demo/hello hello.js \
      --annotation web-export false
```      

## Error Handling

When an OpenWhisk action fails, there are two different failure modes. The first is known as an _application error_ and is analogous to a caught exception: the action returns a JSON object containing a top level `error` property. The second is a _developer error_ which occurs when the action fails catastrophically and does not produce a response (this is similar to an uncaught exception). For web actions, the controller handles application errors as follows:

1. Any specified path projection is ignored and the controller projects the `error` property instead.
2. The controller applies the content handling implied by the action extension to the value of the `error` property.

Developers should be aware of how web actions might be used and generate error responses accordingly. For example, a web action that is used with the `.http` extension
should return an HTTP response, for example: `{error: { statusCode: 400 }`. Failing to do so will in a mismatch between the implied content-type from the extension and the action content-type in the error response. Special consideration must be given to web actions that are sequences, so that components that make up a sequence can generate adequate errors when necessary.

## Vanity URL

Web actions may be accessed via an alternate URL which treats the OpenWhisk namespace as a subdomain to the API host. This is suitable for developing web actions that use cookies or local storage so that data is not inadvertently made visible to other web actions in other namespaces. The namespaces must match the regular expression `[\w@-]+` for the edge router to rewrite the subdomain to the corresponding URI. For a conforming namespace, the URL `https://guest.openwhisk.host/public/index.html` becomes a alias for `https://openwhisk.host/api/v1/web/guest/public/index.html`.

For added convenience, and to provide the equivalent of an `index.html`, the edge router will also proxy `https://guest.openwhisk.host` to `https://openwhisk.host/api/v1/web/guest/public/index.html` where `/guest/public/index.html` (i.e., action is called `index` in a package called `public`) is a web action that responds with HTML content. If the action does not exist, the API host will respond with 404 Not Found.

For a local deployment, you will need to provide name resolution for the vanity URL to work. The easiest solution is to add an entry in `/etc/host` for `<namespace>.openwhisk.host`. Here is an example:
```bash
> grep guest /etc/hosts 192.168.99.100  guest.openwhisk.host
```
