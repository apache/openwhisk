# Common use cases

The execution model that is offered by OpenWhisk supports a variety of use cases. The following sections include typical examples. For a more detailed discussion of Serverless architecture, example uses cases, pros and cons discussion and implementation best practices, please read excellent [Mike Roberts article on Martin Fowler's blog](https://martinfowler.com/articles/serverless.html).

[Microservices](#Microservices)
[Web apps](#Web apps)
[IoT](#IoT)
[API backend](#API backend)
[Mobile back end](#Mobile back end)
[Data processing](#Data processing)
[Cognitive](#Cognitive)
[Event processing with Kafka and Message Hub](#Event processing with Kafka and Message Hub) 
[Stream processing](#Stream processing)
[Batch processing](#Batch processing)

## Microservices

Despite their benefit, microservice-based solutions remain difficult to build using mainstream cloud technologies, often requiring control of a complex toolchain, and separate build and operations pipelines. Small and agile teams, spending too much time dealing with infrastructural and operational complexities (fault-tolerance, load balancing, auto-scaling, and logging), especially want a way to develop streamlined, value-adding code with programming languages they already know and love and that are best suited to solve particular problems.

The modular and inherently scalable nature of OpenWhisk makes it ideal for implementing granular pieces of logic in actions. OpenWhisk actions are independent of each other and can be implemented using variety of different languages supported by OpenWhisk and access various backend systems. Each action can be independently deployed and managed, is scaled independently of other actions. Interconnectivity between actions is provided by OpenWhisk in the form of rules, sequences and naming conventions. This bodes well for microservices based applications.

[Logistics Wizard](https://www.ibm.com/blogs/bluemix/2017/02/microservices-multi-compute-approach-using-cloud-foundry-openwhisk/) is an enterprise-grade sample application which leverages OpenWhisk and CloudFoundry to build 12-factor style application. It is a smart supply chain management solution that aims to simulate an environment running an ERP system. It augments this ERP system with applications to improve the visibility and agility of supply chain managers.


## Web apps

Even though OpenWhisk was originally designed for event based programming, it offers several benefits for user-facing applications. For example, when you combine it with a small Node.js stub, you can use it to serve applications that are relatively easy to debug. And because OpenWhisk applications are a lot less computationally intensive than running a server process on a PaaS platform, they are considerably cheaper, as well. 

Full web application can be built and run with OpenWhisk. Combining serverless APIs with static file hosting for site resources, e.g. HTML, JavaScript and CSS, means we can build entire serverless web applications. The simplicity of operating a hosted OpenWhisk environment (or rather not having to operate anything at all since it is hosted on Bluemix) is a great benefit compared to standing up and operating a Node.js Express or other traditional server runtime.

One of the things that helps is the option of OpenWhisk CLI *wsk* tool called "--annotation web-export true", which makes the code accessible from a web browser.

Here are few examples on how to use OpenWhisk to build a web app:
- [Web Actions: Serverless Web Apps with OpenWhisk](https://medium.com/openwhisk/web-actions-serverless-web-apps-with-openwhisk-f21db459f9ba).
- [Build a user-facing OpenWhisk application with Bluemix and Node.js](https://www.ibm.com/developerworks/cloud/library/cl-openwhisk-node-bluemix-user-facing-app/index.html)
- [Serverless HTTP handlers with OpenWhisk](https://medium.com/openwhisk/serverless-http-handlers-with-openwhisk-90a986cc7cdd)

## IoT

It is certainly possible to implement IoT applications using traditional server architectures, however in many cases the combination of different services and data bridges requires high performance and flexible pipelines, spanning from IoT devices up to cloud storage and an analytics platform. Often pre-configured bridges lack the programmability required to implement and fine-tune a particular solution architecture. Given the huge variety of possible pipelines and the lack of standardization around data fusion in general and in IoT in particular, there are many cases where the pipeline requires custom data transformation (for format conversion, filtering, augmentation, etc). OpenWhisk is an excellent tool to implement such a transformation, in a ‘serverless’ manner, where the custom logic is hosted on a fully managed and elastic cloud platform.

Internet of Things scenarios are often inherently sensor-driven. For example, an action in OpenWhisk might be triggered if there is a need to react to a sensor that is exceeding a particular temperature. IoT interactions are usually stateless with potential for very high level of load in case of major events (natural disasters, significant weather events, traffic jams, etc.) This creates a need for an elastic system where normal workload might be small, but needs to scale very quickly with predictable response time and ability to handle extremely large number of events with no prior warning to the system. It is very hard to build a system to meet these requirements using traditional server architectures as they tend to either be underpowered and unable to handle peak in traffic or be overprovisioned and extremely expensive.

Here is a sample IoT application that uses OpenWhisk, NodeRed, Cognitive and other services: [Serverless transformation of IoT data-in-motion with OpenWhisk](https://medium.com/openwhisk/serverless-transformation-of-iot-data-in-motion-with-openwhisk-272e36117d6c#.akt3ocjdt).

![IoT solution architecture example](images/IoT_solution_architecture_example.png)

## API backend

Serverless computing platforms give developers a rapid way to build APIs without servers. OpenWhisk supports automatic generation of REST API for actions and it is very easy to connect your API Management tool of choice (such as [IBM API Connect](https://www-03.ibm.com/software/products/en/api-connect) or other) to these REST APIs provided by OpenWhisk. Similar to other use cases, all considerations for scalability, and other Qualities of Services (QoS) apply. 

Here is an example and a discussion of [using Serverless as an API backend](https://martinfowler.com/articles/serverless.html#ACoupleOfExamples).

## Mobile back end

Many mobile applications require server-side logic. Given that mobile developers usually don’t have experience in managing server-side logic and would rather focus on the app that is running on the device, using OpenWhisk as the server-side back end is a good solution. In addition, the built-in support for Swift allows developers to reuse their existing iOS programming skills. Mobile applications often have unpredictable load patterns and hosted OpenWhisk solution, such as IBM Bluemix can scale to meet practically any demand in workload without the need to provision resources ahead of time.

[Skylink](https://github.com/IBM-Bluemix/skylink) is a sample application that lets you connect a drone aircraft via iPad to the IBM Cloud with near realtime image analysis leveraging OpenWhisk, IBM Cloudant, IBM Watson, and Alchemy Vision.

[BluePic](https://github.com/IBM-Swift/BluePic) is a photo and image sharing application that allows you to take photos and share them with other BluePic users. This application demonstrates how to leverage, in a mobile iOS 10 application, a Kitura-based server application written in Swift, uses OpenWhisk, Cloudant, Object Storage for image data. AlchemyAPI is also used in the OpenWhisk sequence to analyze the image and extract text tags based on the content of the image and then to send a push notification to the user.

## Data processing

With the amount of data now available, application development requires the ability to process new data, and potentially react to it. This requirement includes processing both structured database records as well as unstructured documents, images, or videos. OpenWhisk can be configured via system provided or custom feeds to react to changes in data and automatically execute actions on the incoming feeds of data. Actions can be programmed to process changes, transform data formats, send and receive messages, invoke other actions, update various data stores, including SQL based relational databases, in-memory data grids, NoSQL database, files, messaging brokers and variety of other systems. OpenWhisk rules and sequences provide flexibility to make changes in processing pipeline without programming - simply via configuration changes. This makes OpenWhisk based system highly agile and easily adaptable to changing requirements.

[OpenChecks](https://github.com/krook/openchecks) project is a proof of concept that shows how OpenWhisk can be used for processing the deposit of checks to a bank account using optical character recognition. It is currently built on the public Bluemix OpenWhisk service and relies on Cloudant and SoftLayer Object Storage. On premises, it could use CouchDB and OpenStack Swift. Other storage services could include FileNet or Cleversafe. Tesseract provides the OCR library.

## Cognitive

Cognitive technologies can be effectively combined with OpenWhisk to create powerful applications. For example, IBM Alchemy API and Watson Visual Recognition can be used with OpenWhisk to automatically extract useful information from videos without having to actually watch them. 

Here is a sample application [Dark vision](https://github.com/IBM-Bluemix/openwhisk-darkvisionapp) that does just that. In this application the user uploads a video or image using the Dark Vision web application, which stores it in a Cloudant DB. Once the video is uploaded, OpenWhisk detects the new video by listening to Cloudant changes (trigger). OpenWhisk then triggers the video extractor action. During its execution, the extractor produces frames (images) and stores them in Cloudant. The frames are then processed using Watson Visual Recognition and the results are stored in the same Cloudant DB. The results can be viewed using Dark Vision web application OR an iOS application. Object Storage can be used in addition to Cloudant. When doing so, video and image metadata are stored in Cloudant and the media files are stored in Object Storage.

Another good use for OpenWhisk is to implement Bot function combined with cognitive services. 

Here is an [example iOS Swift application](https://github.com/gconan/BluemixMobileServicesDemoApp) that shows OpenWhisk, IBM Mobile Analytics, Watson to analyze tone and post to a Slack channel.

## Event processing with Kafka and Message Hub 

OpenWhisk is very well suited to be used in combination with Kafka, IBM Message Hub service (Kafka based) and other messaging systems. The event driven nature of those systems requires event driven runtime to process messages and apply business logic to those messages, which is exactly what OpenWhisk provides with its feeds, triggers, actions, etc. Kafka and Message Hub are often used for very high and unpredictable volumes of workload and require that consumers of those messages need to be scalable on a moment's notice, which is again a sweet spot for OpenWhisk. OpenWhisk has built-in capability to consume messages as well as publish messages provided in the [openwhisk-package-kafka](https://github.com/openwhisk/openwhisk-package-kafka) package.

Here is an [example application that implements event processing scenario](https://github.com/IBM/openwhisk-data-processing-message-hub) with OpenWhisk, Message Hub and Kafka.

<!-- need content + Samples
## Stream processing


## Batch processing

 -->


More examples with source code and instructions can be found on [OpenWhisk samples page](./samples.md).
