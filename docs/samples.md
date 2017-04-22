# OpenWhisk samples

A complete list of [OpenWhisk Tutorials and Samples can be found here](https://github.com/romankhar/openwhisk-external-resources/blob/new-samples/README.md#sample-applications). On this community page you can also find articles, presentations, podcasts, videos and much more.

## OpenWhisk Hello World example
To get started with OpenWhisk, try the following JavaScript code example.

```
/**
 * Hello world as an OpenWhisk action.
 */
function main(params) {
    var name = params.name || 'World';
    return {payload:  'Hello, ' + name + '!'};
}
```

To use this example, follow these steps:

1. Save the code to a file. For example, *hello.js*.

2. From the OpenWhisk CLI command line, create the action by entering this command:

    ```
    $ wsk action create hello hello.js
    ```

3. Then, invoke the action by entering the following commands.

    ```
    $ wsk action invoke hello --result
    ```

    This command outputs:

    ```
    {
        "payload": "Hello, World!"
    }
    ```

    ```
    $ wsk action invoke hello --result --param name Fred
    ```

    This command outputs:

    ```
    {
        "payload": "Hello, Fred!"
    }
    ```

You can also use the event-driven capabilities in OpenWhisk to invoke this action in response to events. Follow the [alarm service example](./packages.md#creating-and-using-trigger-feeds) to configure an event source to invoke the `hello` action every time a periodic event is generated.
