# API Gateway

API Gateway are out from experimental phase.

[Web Actions](webactions.md) allows you to invoke an action with HTTP methods other than POST and without the action's authorization API key.

As a result of user feedback, Web Actions are the programming model chosen to build OpenWhisk actions capable of handling HTTP events.

The API Gateway can be configured to proxy your Web Actions providing them with API Gateway features such as rate limiting, oauth token validation, API keys, and more.

For more information on API Gateway feature you can read the [api management documentation](https://github.com/openwhisk/openwhisk-apigateway/blob/master/doc/management_interface.md)

**Note:** The APIs you created using the `wsk api-experimental` will continue to work for a short period, however you should begin migrating your APIs to web actions and reconfigure your existing apis using the new CLI command `wsk api`.

## OpenWhisk CLI configuration

Follow the instructions in [Configure CLI](./README.md#setting-up-the-openwhisk-cli) on how to set the authentication key for your specific namespace.

## Expose an OpenWhisk web action

1. Create a JavaScript file with the following content. For this example, the file name is 'hello.js'.
  ```javascript
  function main({name:name='Serverless API'}) {
      return {payload: `Hello world ${name}`};
  }
  ```
  
2. Create a web action from the following JavaScript function. For this example, the action is called 'hello'. Make sure to add the flag `--web true`
  
  ```
  wsk action update hello hello.js --web true
  ```
  ```
  ok: updated action hello
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
  The action `hello` got invoked, returning back a JSON string including the parameter `name` sent via query parameter. You can pass parameters to the action via simple query parameters, or via request body.
  
5. Full controll over the the http response
  
  Notice that the full result of the action is returned in the body of the response, and the content is assume to be `application/json` this is because the API was created with `--response-type json` using `json` for the response-type allows you to easily get started with existing actions, but once you have something working you want to have full control over the http response like `statusCode`, `headers` and return different content types in the `body`.
  
  You can choose to change the code of the action to comply with the return of web actions with http extension or include the action in a sequence passing it's result to a new action that transform the result to be properly formatted for an http response. You can read more about response types and web actions extensions in the [Web Actions](webactions.md) documentation.

  Change the code for the `hello.js` returning the JSON properties `body`, `statusCode` and `headers`
  ```javascript
  function main({name:name='Serverless API'}) {
      return {
        body: new Buffer(JSON.stringify({payload:`Hello world ${name}`})).toString('base64'), 
        statusCode:200, 
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
  Now you are in full control of your APIs, can control the content like returning html, or set the status code for things like Not Found (404), or Unauthorized (401), or even Internal Error (500).

### Exposing multiple web actions

Let's say you want to expose a set of actions for a book club for your friends.
You have a series of actions to implement your backend for the book club:

| action | http method | description |
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

### Exporting configuration
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

- **Note**: The `wsk api-experimental` CLI command will be available for a short period of time to allow you to migrate and delete your existing APIs.
