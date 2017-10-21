# API Gateway

OpenWhisk actions can benefit from being managed by API management.

The API Gateway acts as a proxy to [Web Actions](webactions.md) and provides them with additional features including HTTP method routing , client id/secrets, rate limiting and CORS.
For more information on API Gateway feature you can read the [api management documentation](https://github.com/apache/incubator-openwhisk-apigateway/blob/master/doc/v2/management_interface_v2.md)


## Create APIs from OpenWhisk web actions using the CLI

### OpenWhisk CLI configuration

Follow the instructions in [Configure CLI](./README.md#setting-up-the-openwhisk-cli) on how to set the authentication key for your specific namespace.

### Create your first API using the CLI

1. Create a JavaScript file with the following content. For this example, the file name is 'hello.js'.
  ```javascript
  function main({name:name='Serverless API'}) {
      return {payload: `Hello world ${name}`};
  }
  ```

2. Create a web action from the following JavaScript function. For this example, the action is called 'hello'. Make sure to add the flag `--web true`

  ```
  wsk action create hello hello.js --web true
  ```
  ```
  ok: created action hello
  ```

3. Create an API with base path `/hello`, path `/world` and method `get` with response type `json`

  ```
  wsk api create /hello /world get hello --response-type json
  ```
  ```
  ok: created API /hello/world GET for action /_/hello
  https://${APIHOST}:9001/api/21ef035/hello/world
  ```
  A new URL is generated exposing the `hello` action via a __GET__ HTTP method.

4. Let's give it a try by sending a HTTP request to the URL.

  ```
  $ curl https://${APIHOST}:9001/api/21ef035/hello/world?name=OpenWhisk
  ```
  ```json
  {
  "payload": "Hello world OpenWhisk"
  }
  ```
  The web action `hello` was invoked, returning back a JSON object including the parameter `name` sent via query parameter. You can pass parameters to the action via simple query parameters, or via the request body. Web actions allow you to invoke an action in a public way without the OpenWhisk authorization API key.

### Full control over the HTTP response

  The `--response-type` flag controls the target URL of the web action to be proxied by the API Gateway. Using `--response-type json` as above returns the full result of the action in JSON format and automatically sets the Content-Type header to `application/json` which enables you to easily get started.

  Once you get started, you will want to have full control over the HTTP response properties like `statusCode`, `headers` and return different content types in the `body`. You can do this by using `--response-type http`, this will configure the target URL of the web action with the `http` extension.

  You can choose to change the code of the action to comply with the return of web actions with `http` extension or include the action in a sequence passing its result to a new action that transforms the result to be properly formatted for an HTTP response. You can read more about response types and web actions extensions in the [Web Actions](webactions.md) documentation.

  Change the code for the `hello.js` returning the JSON properties `body`, `statusCode` and `headers`
  ```javascript
  function main({name:name='Serverless API'}) {
      return {
        body: new Buffer(JSON.stringify({payload:`Hello world ${name}`})).toString('base64'),
        statusCode: 200,
        headers:{ 'Content-Type': 'application/json'}
      };
  }
  ```
  Notice that the body needs to be return encoded in `base64` and not a string.

  Update the action with the modified result
  ```
  wsk action update hello hello.js --web true
  ```
  Update the API with `--response-type http`
  ```
  wsk api create /hello /world get hello --response-type http
  ```
  Let's call the updated API
  ```
  curl https://${APIHOST}:9001/api/21ef035/hello/world
  ```
  ```json
  {
  "payload": "Hello world Serverless API"
  }
  ```
  Now you are in full control of your APIs, can control the content like returning HTML, or set the status code for things like Not Found (404), or Unauthorized (401), or even Internal Error (500).

### Exposing multiple web actions

Let's say you want to expose a set of actions for a book club for your friends.
You have a series of actions to implement your backend for the book club:

| action | HTTP method | description |
| ----------- | ----------- | ------------ |
| getBooks    | GET | get book details  |
| postBooks   | POST | adds a book |
| putBooks    | PUT | updates book details |
| deleteBooks | DELETE | deletes a book |

Let's create an API for the book club, named `Book Club`, with `/club` as its HTTP URL base path and `books` as its resource.
```
wsk api create -n "Book Club" /club /books get getBooks --response-type http
wsk api create /club /books post postBooks              --response-type http
wsk api create /club /books put putBooks                --response-type http
wsk api create /club /books delete deleteBooks          --response-type http
```

Notice that the first action exposed with base path `/club` gets the API label with name `Book Club` any other actions exposed under `/club` will be associated with `Book Club`

Let's list all the actions that we just exposed.

```
wsk api list -f
```
```
ok: APIs
Action: getBooks
  API Name: Book Club
  Base path: /club
  Path: /books
  Verb: get
  URL: https://${APIHOST}:9001/api/21ef035/club/books
Action: postBooks
  API Name: Book Club
  Base path: /club
  Path: /books
  Verb: post
  URL: https://${APIHOST}:9001/api/21ef035/club/books
Action: putBooks
  API Name: Book Club
  Base path: /club
  Path: /books
  Verb: put
  URL: https://${APIHOST}:9001/api/21ef035/club/books
Action: deleteBooks
  API Name: Book Club
  Base path: /club
  Path: /books
  Verb: delete
  URL: https://${APIHOST}:9001/api/21ef035/club/books
```

Now just for fun let's add a new book `JavaScript: The Good Parts` with a HTTP __POST__
```
curl -X POST -d '{"name":"JavaScript: The Good Parts", "isbn":"978-0596517748"}' https://${APIHOST}:9001/api/21ef035/club/books
```
```
{
  "result": "success"
}
```

Let's get a list of books using our action `getBooks` via HTTP __GET__
```
curl -X GET https://${APIHOST}:9001/api/21ef035/club/books
```
```
{
  "result": [{"name":"JavaScript: The Good Parts", "isbn":"978-0596517748"}]
}
```

### Exporting the configuration
Let's export API named `Book Club` into a file that we can use as a base to to re-create the APIs using a file as input.
```
wsk api get "Book Club" > club-swagger.json
```

Let's test the swagger file by first deleting all exposed URLs under a common base path.
You can delete all of the exposed URLs using either the base path `/club` or API name label `"Book Club"`:
```
wsk api delete /club
```
```
ok: deleted API /club
```
### Changing the configuration

You can edit the configuration file to configure API Gateway extensions such as disabling or enabling CORS, for more info on the format of the configuration file refer to the API Gateway [docs](https://github.com/apache/incubator-openwhisk-apigateway/blob/master/doc/v2/management_interface_v2.md#gateway-specific-extensions).

### Importing the configuration

Now let's restore the API named `Book Club` by using the file `club-swagger.json`
```
wsk api create --config-file club-swagger.json
```
```
ok: created api /books delete for action deleteBook
https://${APIHOST}:9001/api/21ef035/club/books
ok: created api /books get for action deleteBook
https://${APIHOST}:9001/api/21ef035/club/books
ok: created api /books post for action deleteBook
https://${APIHOST}:9001/api/21ef035/club/books
ok: created api /books put for action deleteBook
https://${APIHOST}:9001/api/21ef035/club/books
```

We can verify that the API has been re-created
```
wsk api list /club
```
```
ok: apis
Action                    Verb         API Name        URL
getBooks                   get         Book Club       https://${APIHOST}:9001/api/21ef035/club/books
postBooks                 post         Book Club       https://${APIHOST}:9001/api/21ef035/club/books
putBooks                   put         Book Club       https://${APIHOST}:9001/api/21ef035/club/books
deleteBooks             delete         Book Club       https://${APIHOST}:9001/api/21ef035/club/books
```
