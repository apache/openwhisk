# How to setup OpenWhisk with Docker Compose

An easy way to try OpenWhisk locally is to use Docker Compose.

#### Prerequisites

The following are required to build and deploy OpenWhisk with Docker Compose:

- [Docker 1.12+](https://www.docker.com/products/docker) 
- [Docker Compose](https://docs.docker.com/compose/install/)
- [Java 8](http://www.oracle.com/technetwork/java/javase/downloads/index.html)

Available Ports:

- `5984` for CouchDB
- `2181` for Zookeeper
- `9092` for Kafka
- `8400`, `8500`, `8600`, `8302` for Consul
- `8888` for OpenWhisk's Controller
- `8085` for OpenWhisk's Invoker
- `80` and `443` for the API Gateway

# Quick Start

```bash
cd ./tools/docker-compose/

make quick-start
```

This command builds OpenWhisk, the docker containers, it starts the system and it executes a simple `hello-world` function.
At the end of the execution it prints the output of the function:
```javascript
{
    "payload": "Hello, World!"
}
```

# Build

```bash
make docker
```

This command builds the docker containers for local testing and development.

> NOTE: The build may skip some components such as Swift actions in order to finish the build faster.

# Start

```bash
make run
```

This command starts OpenWhisk by calling `docker-compose up`, it initializes the database and the CLI.

# Stop

The following command stops the `docker-compose`:

```bash
make stop
```

To remove the stopped containers, clean the database files and the temporary files use:
 
 ```bash
 make destroy
 ```

# Running a hello-world function

Once OpenWhisk is up and running you can execute a `hello-world` function:

```bash
make hello-world
```

This command creates a new JS action, it invokes it, and then it deletes it. 
  The javascript action is:
```javascript
function main(params) {
    var name = params.name || "World";
    return {payload: "Hello, " + name + "!"};
}
```  
The result of the invokation should be printed on the terminal:
```
{
    "payload": "Hello, World!"
} 
```

## Logs

- OpenWhisk Controller - `~/tmp/openwhisk/controller/logs/`
- OpenWhisk Invoker - `~/tmp/openwhisk/invoker/logs/`
- `docker-compose` logs - `~/tmp/openwhisk/docker-compose.log`
