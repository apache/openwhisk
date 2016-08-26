
# Getting started with OpenWhisk

OpenWhisk is a distributed, event-driven compute service. 
OpenWhisk runs application logic in response to events or direct invocations from web or mobile apps over HTTP.
Events can be provided from Bluemix services like Cloudant and from external sources. Developers can focus on writing application logic, and creating actions that are executed on demand.
The rate of executing actions always matches the event rate, resulting in inherent scaling and resiliency and optimal utilization. You pay for only what you use and you don't have to manage a server.
You can also get the [source code](https://github.com/openwhisk/openwhisk) and run the system yourself.

For more details about how OpenWhisk works, see [System overview](./about.md).

## Setting up the OpenWhisk CLI

The OpenWhisk command line interface (CLI) requires Python 2.7.

- If you cloned the OpenWhisk repository, you will find the CLI in `openwhisk/bin/wsk`.

- Otherwise, download the CLI from an existing deployment.
You will need to know the base URL for the deployment you want to use and
install it using [pip](https://pip.pypa.io/).

```
sudo pip install --upgrade https://{BASE URL}/openwhisk-0.1.0.tar.gz [--trusted-host {BASE URL}]
```

The `{BASE URL}` is the OpenWhisk API hostname or IP address (e.g., openwhisk.ng.bluemix.net).
The `--trusted-host` option allows you to download the CLI from a host with a [self-signed (i.e., untrusted) certificate](../tools/vagrant/README.md#ssl-certificate-configuration-optional).

There are three properties to configure the CLI with:

1. **API host** (name or IP address) for the OpenWhisk deployment you want to use.
2. **Authorization key** (username and password) which grants you access to the OpenWhisk API.
3. **Namespace** where your OpenWhisk assets are stored.

The CLI will usually have an API host already set. You can check its value with
`wsk property get --apihost`.

If you know your authorization key and namespace, you can configure the CLI to use them. Otherwise
you will need to provide one or both for most CLI operations.

```
wsk property set [--apihost <openwhisk_baseurl>] --auth <username:password> --namespace <namespace>
```

The API host is set automatically when you build the CLI for your environment. A _guest_ account is available
in local installations with an authorization key located in [ansible/files/auth.guest](../ansible/files/auth.guest) and the namespace `guest`.
To configure the CLI to use the guest account, you can run the following command from your `openwhisk` directory:

```
./bin/wsk property set --namespace guest --auth `cat ansible/files/auth.guest`
```

To verify your CLI setup, try [creating and running an action](#openwhisk-hello-world-example).

## Using the OpenWhisk CLI

After you have configured your environment, you can begin using the OpenWhisk CLI to do the following:

* Run your code snippets, or actions, on OpenWhisk. See [Creating and invoking actions](./actions.md).
* Use triggers and rules to enable your actions to respond to events. See [Creating triggers and rules](./triggers_rules.md).
* Learn how packages bundle actions and configure external events sources. See [Using and creating packages](./packages.md).
* Explore the catalog of packages and enhance your applications with external services, such as a [Cloudant event source](./catalog.md#using-the-cloudant-package). See [Using OpenWhisk-enabled services](./catalog.md).


## Using OpenWhisk from an iOS app

You can use OpenWhisk from your iOS mobile app or Apple Watch by using the OpenWhisk iOS SDK. For more details, refer to the [iOS documentation](./mobile_sdk.md).

## Using REST APIs with OpenWhisk

After your OpenWhisk environment is enabled, you can use OpenWhisk with your web apps or mobile apps with REST API calls.
For more details about the APIs for actions, activations, packages, rules, and triggers, see the [OpenWhisk API documentation](http://petstore.swagger.io/?url=https://raw.githubusercontent.com/openwhisk/openwhisk/master/core/controller/src/main/resources/whiskswagger.json).

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
