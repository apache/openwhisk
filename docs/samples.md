# OpenWhisk samples
Here is a list of [OpenWhisk community resources](https://github.com/apache/incubator-openwhisk-external-resources), which includes more samples, complete OpenWhisk applications, articles, tutorials, podcasts and much more.

<!-- TODO 
"Complete listing of OpenWhisk samples can be found here." <- need to insert a link to the OpenWhisk samples repo when there is one -->

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

## CLI tutorial

If you prefer learning while doing, consider using this [OpenWhisk Workshop](https://github.com/apache/incubator-openwhisk-workshop).
