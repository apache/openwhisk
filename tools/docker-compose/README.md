# How to setup OpenWhisk with Docker Compose

An easy way to try or develop OpenWhisk is to use Docker Compose.

#### Prerequisites

The following are required to build and deploy OpenWhisk with Docker Compose:

- [Docker 1.12+](https://www.docker.com/products/docker#/mac) 
- [Java 8](http://www.oracle.com/technetwork/java/javase/downloads/index.html)
- [Scala 2.11](http://scala-lang.org/download/)

# Quick Start

```bash
make quick-start
```

This command builds OpenWhisk, builds the docker containers, starts the system and executes a simple hello-world function.
At the end of the execution it prints the output of the function:
```javascript
{
    "payload": "Hello, World!"
}
```

# Build

```bash
cd /your/path/to/openwhisk/tools/docker-compose/
make docker
```

This command builds the docker containers for local development.

# Deploy

```bash
make run
```

# Stop

```bash
make stop
```

# Running a hello-world function

```bash
../../bin/wsk action invoke /whisk.system/utils/echo -p message hello --blocking --result
{
    "message": "hello"
}
```

