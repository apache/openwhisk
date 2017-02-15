# Common use cases

The execution model that is offered by OpenWhisk supports a variety of use cases. The following sections include typical examples.

## Microservices

The modular and inherently scalable nature of OpenWhisk makes it suitable for implementing granular pieces of logic in actions. For example, OpenWhisk can be useful for removing load-intensive, potentially spiky (background) tasks from front-end code and implementing these tasks as actions. Actions are independent of each other and can be implemented using variety of different languages supported by OpenWhisk and access various backend systems. Each action can be independently deployed and managed, is scaled independently of other actions. Interconnectivity between actions is provided by OpenWhisk in the form of rules, sequences and naming conventions. This bodes well for microservices based applications.

## Mobile back end

Many mobile applications require server-side logic. Given that mobile developers usually don’t have experience in managing server-side logic and would rather focus on the app that is running on the device, using OpenWhisk as the server-side back end is a good solution. In addition, the built-in support for Swift allows developers to reuse their existing iOS programming skills. Mobile applications often have unpredictable load patterns and hosted OpenWhisk solution, such as IBM Bluemix can scale to meet practically any demand in workload without the need to provision resources ahead of time.

## Data processing

With the amount of data now available, application development requires the ability to process new data, and potentially react to it. This requirement includes processing both structured database records as well as unstructured documents, images, or videos. OpenWhisk can be configured via system provided or custom feeds to react to changes in data and automatically execute actions on the incoming feeds of data. Actions can be programmed to process changes, transform data formats, send and receive messages, invoke other actions, update various data stores, including SQL based relational databases, in-memory data grids, NoSQL database, files, messaging brokers and variety of other systems. OpenWhisk rules and sequences provide flexibility to make changes in processing pipeline without programming - simply via configuration changes. This makes OpenWhisk based system highly agile and easily adaptable to changing requirements.

## IoT

Internet of Things scenarios are often inherently sensor-driven. For example, an action in OpenWhisk might be triggered if there is a need to react to a sensor that is exceeding a particular temperature. IoT interactions are usually stateless with potential for very high level of load in case of major events (natural disasters, significant weather events, traffic jams, etc.) This creates a need for an elastic system where normal workload might be small, but needs to scale very quickly with predictable response time and ability to handle extremely large number of events with no prior warning to the system. It is very hard to build a system to meet these requirements using traditional server architectures as they tend to either be underpowerd and unable to handle peak in traffic or be overprovisioned and extremely expensive.

Here is a sample IoT application that uses OpenWhisk, NodeRed, Cognitive and other services: [Serverless transformation of IoT data-in-motion with OpenWhisk](https://medium.com/openwhisk/serverless-transformation-of-iot-data-in-motion-with-openwhisk-272e36117d6c#.akt3ocjdt).

<!-- TODO - add this content in the future:
## API backend
## Data stream processing
consider this: https://medium.com/openwhisk/serverless-transformation-of-iot-data-in-motion-with-openwhisk-272e36117d6c#.xmqz6upgb 
And also this:
https://medium.com/openwhisk/transit-flexible-pipeline-for-iot-data-with-bluemix-and-openwhisk-4824cf20f1e0#.608ze4yca 
## Cognitive
## Web apps

-->