# Quickstart

This is a short, simple tutorial intended to get you started with Registrator as
quickly as possible. For full reference, see [Run Reference](run.md).

## Overview

Registrator watches for new Docker containers and inspects them to determine
what services they provide. For our purposes, a service is anything listening on
a port. Any services Registrator finds on a container, they will be added to a
service registry, such as Consul or etcd.

In this tutorial, we're going to use Registrator with Consul, and run a Redis
container that will automatically get added to Consul.

## Before Starting

We're going to need a host running Docker, which could just be a local
[boot2docker](http://boot2docker.io/) VM, and a shell with the `docker` client
pointed to that host.

We'll also need to have Consul running, which can just be running in a
container. Let's run a single instance of Consul in server bootstrap mode:
```
$ docker run -d --name=consul --net=host gliderlabs/consul-server -bootstrap
```
Consul is run differently in production, but this will get us through this tutorial.
We can now access Consul's HTTP API via the Docker machine's IP:
```
$ curl $(boot2docker ip):8500/v1/catalog/services
{"consul":[]}
```
Now we can start Registrator.

## Running Registrator

Registrator is run on every host, but since we only have one host here, we can
just run it once. The primary bit of configuration needed to start Registrator
is how to connect to its registry, or Consul in this case.

Besides option flags, the only argument Registrator takes is a registry URI,
which encodes what type of registry, how to connect to it, and any options.
```
$ docker run -d \
    --name=registrator \
    --net=host \
    --volume=/var/run/docker.sock:/tmp/docker.sock \
    gliderlabs/registrator:latest \
      consul://localhost:8500
```
There's a bit going on here in the Docker run arguments. First, we run the
container detached and name it. We also run in host network mode. This makes
sure Registrator has the hostname and IP of the actual host. It also makes it
easier to connect to Consul. We also must mount the Docker socket.

The last line is the argument to Registrator itself, which is just our
registry URI. We're using `consul` on `localhost:8500`, since this is running on
the same network interface as Consul.
```
$ docker logs registrator
```
We should see it started up and "Listening for Docker events". That's it, it's
working!

## Running Redis

Now as you start containers, if they provide any services, they'll be added
to Consul. We'll run Redis now from the standard library image:
```
$ docker run -d -P --name=redis redis
```
Notice we used `-P` to publish all ports. This is not often used except with
Registrator. Not only does it publish all exposed ports the container has, but
it assigns them to a random port on the host. Since the point of Registrator
and Consul is to provide service discovery, the port doesn't matter. Though
there can still be cases where you still want to manually specify the port.

Let's look at Consul's services endpoint again:
```
$ curl $(boot2docker ip):8500/v1/catalog/services
{"consul":[],"redis":[]}
```
Consul now has a service called redis. We can see more about the service
including what port was published by looking at the service endpoint for redis:
```
$ curl $(boot2docker ip):8500/v1/catalog/service/redis
[{"Node":"boot2docker","Address":"10.0.2.15","ServiceID":"boot2docker:redis:6379","ServiceName":"redis","ServiceTags":null,"ServiceAddress":"","ServicePort":32768}]
```
If we remove the redis container, we can see the service is removed from Consul:
```
$ docker rm -f redis
redis
$ curl $(boot2docker ip):8500/v1/catalog/service/redis
[]
```
That's it! I know this may not be interesting alone, but there's a lot you can
do once services are registered in Consul. However, that's out of the scope of
Registrator. All it does is puts container services into Consul.

## Next Steps

There are more ways to configure Registrator and ways you can run containers to
customize the services that are extracted from them. For this, take a look at
the [Run Reference](run.md) and [Service Model](services.md).
