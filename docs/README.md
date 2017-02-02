# Getting started with OpenWhisk

OpenWhisk is an [Apache Incubator Project](https://incubator.apache.org/projects/openwhisk.html). It is an open source implementation of a distributed, event-driven compute service. You can run it on your own hardware on-prem, or in the cloud. When running in the cloud you could use PaaS version of the OpenWhisk provided by IBM Bluemix cloud, or you can provision it yourself into Bluemix IaaS, or other IaaS clouds, such as Amazon AWS, Microsoft Azure, Google GCP, etc. 

OpenWhisk runs application logic in response to events or direct invocations from web or mobile apps over HTTP. Events can be provided from Bluemix services like Cloudant and from external sources. Developers can focus on writing application logic, and creating actions that are executed on demand. The benefits of this new paradigm are that you do not longer need to explicitly provision servers and worry about auto-scaling, high availability, updates, maintenance and pay for hours of processor time when your server is running, but not serving requests. Your code gets called whenever there is an HTTP call, database state change, or other type of event that triggers the execution of your code. You get billed by request or per millisecond of execution time, not per hour of JVM regardless whether that VM was doing useful work or not.

This programming model is perfect match for microservices, mobile, IoT and many other apps – you get inherent auto-scaling and load balancing out of the box without having to manually configure clusters, load balancers, http plugins, etc. If you happen to run on IBM Bluemix cloud, you also get zero administration benefit. All you need to do is to provide the code you want to execute and give it to your cloud vendor. The rest is “magic”. Good introduction into the serverless programming model is available on [Martin Fowler's blog](https://martinfowler.com/articles/serverless.html).

For more details about how OpenWhisk works, see [System overview](./about.md). Official project [website can be found here](http://openwhisk.org).

## Setting up the OpenWhisk CLI 

- Building OpenWhisk from a cloned repository will result in the generation of the command line interface. The
generated CLIs will be located in `openwhisk/bin/go-cli/`. There will be an executable CLI located in the mentioned
directory that will run on the operating system, and CPU architecture on which it was built. Executables for other
operating system, and CPU architectures are located in the following directories: `openwhisk/bin/go-cli/mac`,
`openwhisk/bin/go-cli/linux`, `openwhisk/bin/go-cli/windows`.

- To download the CLI from an existing deployment, you will need to download the CLI using the deployment's base URL.
A list of downloadable CLIs for various operating systems, and CPU architectures can be obtained from the following
location `{BASE URL}/cli/go/download`. The `{BASE URL}` is the OpenWhisk API hostname or IP address
(e.g., openwhisk.ng.bluemix.net).

There are two required properties to configure in order to use the CLI:

1. **API host** (name or IP address) for the OpenWhisk deployment you want to use.
2. **Authorization key** (username and password) which grants you access to the OpenWhisk API.

The API host can be acquired from the `edge.host` property in `whisk.properties` file, which is generated during
deployment of OpenWhisk. Run the following command from your `openwhisk` directory to set the API host:

```
./bin/wsk property set --apihost <openwhisk_baseurl>
```

If you know your authorization key, you can configure the CLI to use it. Otherwise, you will need to obtain an
authorization key for most CLI operations. A _guest_ account is available in local installations with an authorization
key located in [ansible/files/auth.guest](../ansible/files/auth.guest). To configure the CLI to use the guest account,
you can run the following command from your `openwhisk` directory:

```
./bin/wsk property set --auth `cat ansible/files/auth.guest`
```

To verify your CLI setup, try [creating and running an action](#openwhisk-hello-world-example).

## Using the OpenWhisk CLI

After you have configured your environment, you can begin using the OpenWhisk CLI to do the following:

* Run your code snippets, or actions, on OpenWhisk. See [Creating and invoking actions](./actions.md).
* Use triggers and rules to enable your actions to respond to events. See [Creating triggers and rules](./triggers_rules.md).
* Learn how packages bundle actions and configure external events sources. See [Using and creating packages](./packages.md).
* Explore the catalog of packages and enhance your applications with external services, such as a [Cloudant event source](./catalog.md#using-the-cloudant-package). See [Using OpenWhisk-enabled services](./catalog.md).

## Configure the CLI to use an HTTPS proxy

The CLI can be setup to use an HTTPS proxy. To setup an HTTPS proxy, an environment variable called `HTTPS_PROXY` must
 be created. The variable must be set to the address of the HTTPS proxy, and its port using the following format:
`{PROXY IP}:{PROXY PORT}`.

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
