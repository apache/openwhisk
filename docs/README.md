# OpenWhisk

OpenWhisk is an [Apache Incubator Project](https://incubator.apache.org/projects/openwhisk.html). It is an open source implementation of a distributed, event-driven compute service. You can run it on your own hardware on-prem, or in the cloud. When running in the cloud you could use a Function as as Service (FaaS) version of the OpenWhisk provided by IBM Bluemix, or you can provision it yourself into Infrastructure as a Service (IaaS) clouds, such as Bluemix, Amazon EC2, Microsoft Azure, Google GCP, etc. 

OpenWhisk runs application logic in response to events or direct invocations from web or mobile apps over HTTP. Events can be provided from Bluemix services like Cloudant and from external sources. Developers can focus on writing application logic, and creating actions that are executed on demand. The benefits of this new paradigm are that you do not explicitly provision servers and worry about auto-scaling, or worry about high availability, updates, maintenance and pay for hours of processor time when your server is running but not serving requests. Your code executes whenever there is an HTTP call, database state change, or other type of event that triggers the execution of your code. You get billed by millisecond of execution time (rounded to the nearest 100ms in case of OpenWhisk) or on some platforms per request (not supported on OpenWhisk yet), not per hour of JVM regardless whether that VM was doing useful work or not.

This programming model is a perfect match for microservices, mobile, IoT and many other apps – you get inherent auto-scaling and load balancing out of the box without having to manually configure clusters, load balancers, http plugins, etc. If you happen to run on IBM Bluemix, you also get a benefit of zero administration - meaning that all of the hardware, networking and software is maintained by IBM. All you need to do is to provide the code you want to execute and give it to your cloud vendor. The rest is “magic”. Good introduction into the serverless programming model is available on [Martin Fowler's blog](https://martinfowler.com/articles/serverless.html).

## Overview
- [How OpenWhisk works](./about.md)
- [Getting started with OpenWhisk](./getting_started.md)
- [Common uses cases for Serverless applications](./use_cases.md)
- [Should you be using Serverless?](./server_or_less.md)
- [Sample applications](./samples.md)
- [Catalog of OpenWhisk provided services](./catalog.md)
- [FAQ](http://openwhisk.org/faq)
- [Pricing and billing](https://console.ng.bluemix.net/openwhisk/learn/pricing)

## Implementation guide
- [Development workflow and DevOps](./tbd)
- [Setting up and using OpenWhisk CLI](./cli.md)
- [Using OpenWhisk from an iOS app](./mobile_sdk.md).
- [Running OpenWhisk (PaaS versus RYO, private, public, dedicated)](./tbd)
- [Debugging](./debug.md)
- [System limits](./limits.md)

## Programming model - server side
- [System overview](./reference.md)
- [Actions](./actions.md)
- [Triggers and Rules](./triggers_rules.md)
- [Feeds](./feeds.md)
- [Packages](./packages.md)
- [Integration with Serverless Framework](./serverless_framework.md)
- [Annotations](./annotations.md)
- [Web actions](./webactions.md)
- [API Gateway](./apigateway.md)
- [Using REST APIs with OpenWhisk](./rest_api.md)

Additional tutorials, videos, getting started guides, mailing list, samples and other resources can be found on Official project website at  [http://OpenWhisk.org](http://openwhisk.org).
