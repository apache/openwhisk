# API Gateway (Experimental)

[Web Actions](webactions.md) are out from experimental phase.

Web Actions allows you to invoke an action with HTTP methods other than POST and without the action's authorization API key.

As a result of user feedback, Web Actions are the programming model chosen to build OpenWhisk actions capable of handling HTTP events.

Most of the API Gateway functionality was merged into Web Actions, Web Actions allows you to handle any HTTP request and return HTTP responses with full control from your Web Action.

A revised OpenWhisk API Gateway integration will be available soon. It will be configured to proxy your Web Actions providing them with API Gateway features such as rate limiting, oauth token validation, API keys, and more.

**Note:** The APIs you created using the `wsk api-experimental` will continue to work, however you should begin migrating your APIs to web actions.

You can follow progress on v2 of the API Gateway here:
- [OpenWhisk API Gateway repository](https://github.com/openwhisk/openwhisk-apigateway)
- [OpenWhisk API CLI api v2 Pull Request](https://github.com/openwhisk/openwhisk/pull/2031)

## OpenWhisk CLI configuration
This experimental feature only works with the new OpenWhisk authentication model in which each namespace now has a unique authentication key associated with it.
Follow the instructions in [Configure CLI](./README.md#setting-up-the-openwhisk-cli) on how to set the authentication key for your specific namespace.


## Expose an OpenWhisk action

Let's expose a simple action that is already pre-installed with OpenWhisk

```
$ wsk api-experimental create /hello /echo get /whisk.system/utils/echo
```
```
ok: created api /echo GET for action /whisk.system/utils/echo
https://21ef035.api-gw.mybluemix.net/hello/echo
```
A new URL is generated exposing the `echo` action via a __GET__ HTTP method.

Let's give it a try by sending a HTTP request to the URL.
```
$ curl https://21ef035.api-gw.mybluemix.net/hello/echo?marco=polo
```
This will invoke the `echo` action, returning back a JSON string with the parameters sent.
```
{
  "marco":"polo"
}
```

You can pass parameters to the action via simple query parameters, or via request body.

### Exposing multiple actions

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
$ wsk api-experimental create -n "Book Club" /club /books get getBooks
$ wsk api-experimental create /club /books post postBooks
$ wsk api-experimental create /club /books put putBooks
$ wsk api-experimental create /club /books delete deleteBooks
```

Notice that the first action exposed with base path `/club` gets the API label with name `Book Club` any other actions exposed under `/club` will be associated with `Book Club`

Let's list all the actions that we just exposed.

```
$ wsk api-experimental list
```
```
ok: apis
Action                   Verb          API Name        URL
getBooks                   get         Book Club       https://2ef15285-gws.api-gw.mybluemix.net/club/books
postBooks                 post         Book Club       https://2ef15285-gws.api-gw.mybluemix.net/club/books
putBooks                   put         Book Club       https://2ef15285-gws.api-gw.mybluemix.net/club/books
deleteBooks             delete         Book Club       https://2ef15285-gws.api-gw.mybluemix.net/club/books
```

Now just for fun let's add a new book `JavaScript: The Good Parts` with a HTTP __POST__
```
$ curl -X POST -d '{"name":"JavaScript: The Good Parts", "isbn":"978-0596517748"}' https://2ef15285-gws.api-gw.mybluemix.net/club/books
```
```
{
  "result": "success"
}
```

Let's get a list of books using our action `getBooks` via HTTP __GET__
```
$ curl -X GET https://2ef15285-gws.api-gw.mybluemix.net/club/books
```
```
{
  "result": [{"name":"JavaScript: The Good Parts", "isbn":"978-0596517748"}]
}
```

### Exporting configuration
Let's export API named `Book Club` into a file that we can use as a base to to re-create the APIs using a file as input. 
```
$ wsk api-experimental get "Book Club" > club-swagger.json
```

Let's test the swagger file by first deleting all exposed URLs under a common base path.
You can delete all of the exposed URLs using either the base path `/club` or API name label `"Book Club"`:
```
$ wsk api-experimental delete /club
```
```
ok: deleted API /club
```

Now let's restore the API named `Book Club` by using the file `club-swagger.json`
```
$ wsk api-experimental create --config-file club-swagger.json
```
```
ok: created api /books delete for action deleteBook
https://2ef15285-gws.api-gw.mybluemix.net/club/books
ok: created api /books get for action deleteBook
https://2ef15285-gws.api-gw.mybluemix.net/club/books
ok: created api /books post for action deleteBook
https://2ef15285-gws.api-gw.mybluemix.net/club/books
ok: created api /books put for action deleteBook
https://2ef15285-gws.api-gw.mybluemix.net/club/books
```

We can verify that the API has been re-created
```
$ wsk api-experimental list /club
```
```
ok: apis
Action                    Verb         API Name        URL
getBooks                   get         Book Club       https://2ef15285-gws.api-gw.mybluemix.net/club/books
postBooks                 post         Book Club       https://2ef15285-gws.api-gw.mybluemix.net/club/books
putBooks                   put         Book Club       https://2ef15285-gws.api-gw.mybluemix.net/club/books
deleteBooks             delete         Book Club       https://2ef15285-gws.api-gw.mybluemix.net/club/books
```

- **Note**: This feature is currently an experimental offering that enables users an early opportunity to try it out and provide feedback. The following feedback has already been received:
  - No ability to customize HTTP access control for Cross-Origin Resource Sharing (CORS); currently, the generated API response headers are configured to allow any HTTP verb or origin (i.e. *). The following headers are always retturned:
    - Access-Control-Allow-Origin: *
    - Access-Control-Allow-Headers: Authorization, Content-Type
    - Access-Control-Allow-Methods: GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS
  - Only Content Type `application/json` is supported for request and response.
  - No programatic way to control the response from the OpenWhisk action.
  - All OpenWhisk actions are expose via public access, no ability to configure a custom API key.
  - Path parameters are not supported, only query parameter and request body.
  - If the API is created without an API name, the name will be the base path and this cannot be changed
  - When re-creating APIs via input file, the API needs to be deleted first.
  - When exporting APIs this contain your OpenWhisk API key, this information is sensitive, no templating available.
