<!--
#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
-->
# Getting started with OpenWhisk

OpenWhisk is an [Apache Software Foundation](https://www.apache.org/) (ASF) project. It is an open source implementation of a distributed, event-driven compute service. You can run it on your own hardware on-prem, or in the cloud. When running in the cloud you could use a Platform as a Service (PaaS) version of the OpenWhisk provided by IBM Cloud Functions, or you can provision it yourself into Infrastructure as a Service (IaaS) clouds, such as IBM Cloud, Amazon EC2, Microsoft Azure, Google GCP, etc.

OpenWhisk runs application logic in response to events or direct invocations from web or mobile apps over HTTP. Events can be provided from IBM Cloud services like Cloudant and from external sources. Developers can focus on writing application logic, and creating actions that are executed on demand. The benefits of this new paradigm are that you do not explicitly provision servers and worry about auto-scaling, or worry about high availability, updates, maintenance and pay for hours of processor time when your server is running but not serving requests. Your code executes whenever there is an HTTP call, database state change, or other type of event that triggers the execution of your code. You get billed by millisecond of execution time (rounded up to the nearest 100ms in case of OpenWhisk) or on some platforms per request (not supported on OpenWhisk yet), not per hour of JVM regardless whether that VM was doing useful work or not.

This programming model is a perfect match for microservices, mobile, IoT and many other apps – you get inherent auto-scaling and load balancing out of the box without having to manually configure clusters, load balancers, http plugins, etc. All you need to do is to provide the code you want to execute and give it to your cloud vendor. The rest is “magic”. A good introduction into the serverless programming model is available on [Martin Fowler's blog](https://martinfowler.com/articles/serverless.html).

## Overview
- [How OpenWhisk works](./about.md)
- [Common uses cases for Serverless applications](./use_cases.md)
- [Sample applications](./samples.md)

<!--
- [Is serverless a good fit for my application?](./goodfit.md)
-->

<!-- TODO - need to add the following items and pages in the future:
- Concurrency
- Error processing
- Security
- Scalability
- Logging
- Pricing (for IBM Cloud only)
-->

## Implementation guide
- [Setting up and using OpenWhisk CLI](./cli.md)
- [Using REST APIs with OpenWhisk](./rest_api.md)

<!-- TODO - need to add the following items and pages in the future:
- Development workflow
- IDE and toolchain
- When OpenWhisk is a good choice and when it is not
- Building applications from scratch
- Extending existing applications
- Using OpenWhisk as the integration tool
- Integrating with API Management
- Running OpenWhisk (PaaS versus RYO, private, public, dedicated)
- Best practices for rules
- Performance considerations
- Deployment of applications
- Debugging
- Monitoring of applications
- Writing applications with maximum portability
- Publishing events
- Out of the box services and triggers
- CDN integration
- Security
- Limitations
-->

## Programming model
- [System details](./reference.md)
- [Component clustering](./deploy.md)
- [Catalog of OpenWhisk provided services](./catalog.md)
- [Actions](./actions.md)
- [Triggers and Rules](./triggers_rules.md)
- [Feeds](./feeds.md)
- [Packages](./packages.md)
- [Annotations](./annotations.md)
- [Web actions](./webactions.md)
- [API Gateway](./apigateway.md)

<!-- TODO - need to add the following items and pages in the future:
- Concurrency
- Error processing
- Integration with Serverless Framework
-->

Official OpenWhisk project website [http://OpenWhisk.org](http://openwhisk.org).

<!-- ## Setting up the OpenWhisk CLI - moved to cli.md -->

<!-- ## Using REST APIs with OpenWhisk - moved to rest_api.md -->

<!-- ## OpenWhisk Hello World example - moved to samples.md -->
