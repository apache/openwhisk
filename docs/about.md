# System overview

The following sections provide details about OpenWhisk.

## How OpenWhisk works

OpenWhisk is an event-driven compute platform also referred to as Serverless computing or as Function as a Service (FaaS) that runs code in response to events or direct invocations. The following figure shows the high-level OpenWhisk architecture.

![OpenWhisk architecture](images/OpenWhisk.png)

Examples of events include changes to database records, IoT sensor readings that exceed a certain temperature, new code commits to a GitHub repository, or simple HTTP requests from web or mobile apps. Events from external and internal event sources are channeled through a trigger, and rules allow actions to react to these events.

Actions can be small snippets of JavaScript or Swift code, or custom binary code embedded in a Docker container. Actions in OpenWhisk are instantly deployed and executed whenever a trigger fires. The more triggers fire, the more actions get invoked. If no trigger fires, no action code is running, so there is no cost.

In addition to associating actions with triggers, it is possible to directly invoke an action by using the OpenWhisk API, CLI, or iOS SDK. A set of actions can also be chained without having to write any code. Each action in the chain is invoked in sequence with the output of one action passed as input to the next in the sequence.

With traditional long-running virtual machines or containers, it is common practice to deploy multiple VMs or containers to be resilient against outages of a single instance. However, OpenWhisk offers an alternative model with no resiliency-related cost overhead. The on-demand execution of actions provides inherent scalability and optimal utilization as the number of running actions always matches the trigger rate. Additionally, the developer now only focuses on code and does not worry about monitoring, patching, and securing the underlying server, storage, network, and operating system infrastructure.

Integrations with additional services and event providers can be added with packages. A package is a bundle of feeds and actions. A feed is a piece of code that configures an external event source to fire trigger events. For example, a trigger that is created with a Cloudant change feed will configure a service to fire the trigger every time a document is modified or added to a Cloudant database. Actions in packages represent reusable logic that a service provider can make available so that developers not only can use the service as an event source, but also can invoke APIs of that service.

An existing catalog of packages offers a quick way to enhance applications with useful capabilities, and to access external services in the ecosystem. Examples of external services that are OpenWhisk-enabled include Cloudant, The Weather Company, Slack, and GitHub.

## System details

You can find additional information about OpenWhisk in the following topics:

* [Entity names](./reference.md#openwhisk-entities)
* [Action semantics](./reference.md#action-semantics)
* [Limits](./reference.md#system-limits)
* [REST API](./reference.md#rest-api)


<!-- Common use cases moved into its own page -->
