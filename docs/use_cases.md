# Common use cases

The execution model that is offered by OpenWhisk supports a variety of use cases. The following sections include typical examples.

### Decomposition of applications into microservices

The modular and inherently scalable nature of OpenWhisk makes it suitable for implementing granular pieces of logic in actions. For example, OpenWhisk can be useful for removing load-intensive, potentially spiky (background) tasks from front-end code and implementing these tasks as actions.

### Mobile back end

Many mobile applications require server-side logic. Given that mobile developers usually don’t have experience in managing server-side logic and would rather focus on the app that is running on the device, using OpenWhisk as the server-side back end is a good solution. In addition, the built-in support for Swift allows developers to reuse their existing iOS programming skills.

### Data processing

With the amount of data now available, application development requires the ability to process new data, and potentially react to it. This requirement includes processing both structured database records as well as unstructured documents, images, or videos.

### IoT

Internet of Things scenarios are often inherently sensor-driven. For example, an action in OpenWhisk might be triggered if there is a need to react to a sensor that is exceeding a particular temperature.
