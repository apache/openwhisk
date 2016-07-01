
# Getting started with OpenWhisk

OpenWhisk is a distributed, event-driven compute service. OpenWhisk executes application logic in response to events or direct invocations from web or mobile apps over HTTP. Events can be provided from Bluemix services like Cloudant, and from external sources. Developers can focus on writing application logic, and creating actions that are executed on demand. The rate of executing actions always matches the event rate, resulting in inherent scaling and resiliency, and optimal utilization. You pay for only what you use and you don't have to manage a server. You can also get the [source code](https://github.com/openwhisk/openwhisk) and run the system yourself.

For more details about how OpenWhisk works, see [System overview](./about.md).

## Setting up the OpenWhisk CLI

You can use the OpenWhisk command line interface (CLI) to set up your namespace and authorization key. 
Go to [Configure CLI](../README.md#setup-cli) and follow instructions to install it. 
Note that you must have Python 2.7 installed on your system to use the CLI.

After OpenWhisk is set up with the CLI, you can begin using it from the command line or through REST APIs.

## Using the OpenWhisk CLI

After you have configured your environment, you can begin using the OpenWhisk CLI to do the following:

* Run your code snippets, or actions, on OpenWhisk. See [Creating and invoking actions](./actions.md).
* Use triggers and rules to enable your actions to respond to events. See [Creating triggers and rules](./triggers_rules.md).
* Learn how packages bundle actions and configure external events sources. See [Using and creating packages](./packages.md).
* Explore the catalog of packages and enhance your applications with external services, such as a [Cloudant event source](./catalog.md#using-the-cloudant-package). See [Using OpenWhisk-enabled services](./catalog.md).


## Using OpenWhisk from an iOS app

You can use OpenWhisk from your iOS mobile app or Apple Watch by using the OpenWhisk iOS SDK. For more details refer to the [iOS documentation](./mobile_sdk.md).

## Using REST APIs with OpenWhisk

After your OpenWhisk environment is enabled, you can use OpenWhisk with your web apps or mobile apps with REST API calls. For more details on the APIs for actions, activations, packages, rules, and triggers, see the [OpenWhisk API documentation](http://petstore.swagger.io/?url=https://raw.githubusercontent.com/openwhisk/openwhisk/master/core/controller/src/main/resources/whiskswagger.json).

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
    $ wsk action invoke hello --blocking --result
    ```

    This command outputs:

    ```
    {
        "payload": "Hello, World!"
    }
    ```

    ```
    $ wsk action invoke hello --blocking --result --param name Fred
    ```

    This command outputs:

    ```
    {
        "payload": "Hello, Fred!"
    }
    ```

You can also use the event-driven capabilities in OpenWhisk to invoke this action in response to events. Follow the [alarm service example](./packages.md#creating-and-using-trigger-feeds) to configure an event source to invoke the `hello` action every time a periodic event is generated.


## System details

You can find additional information about OpenWhisk in the following topics:

* [Entity names](./reference.md#openwhisk-entities)
* [Action semantics](./reference.md#action-semantics)
* [Limits](./reference.md#system-limits)
* [REST API](./reference.md#rest-api)
