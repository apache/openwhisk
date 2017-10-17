This page documents configuration options that should be considered when deploying OpenWhisk. Some deployment options require special treatment wrt to the underlying infrastructure/deployment model. Please carefully read about the constraints before you decide to enable them.

# Controller Clustering

The system can be configured to use Akka clustering to manage the distributed state of the Contoller's load balancing algorithm.  This imposes the following constriaints on a deployment


## Controller nodes must have static IPs/Port combination.

It guarantees that failed nodes are able to join the cluster again.
This limitation refers to the fact that Akka clustering doesn't allow to add new nodes when one of the existing members is unreachable (e.g. JVM failure). If each container receives a its ip and port dynamically upon the restart, in case of controller failure, it could come back online under a new ip/port combination which makes cluster consider it as a new member and it won't be added to the cluster (in certain cases it could join as a weeklyUp member). However, the cluster will still replicate the state across the online nodes, it will have trouble to get back to the previous state with desired number of members until the old member is explicitly "downed".

How to down the members.
1. manually (sending an HTTP or JMX request to the controller). For this case an external supervisor for the cluster is required, which will down the nodes and provide an up-to-date list of seed nodes.
2. automatically by setting the "auto-down-property" in controller that will allow the leader to down the node after a certain timeout. In order to mitigate brain split one could define a list of seed nodes which are reachable under static IPs or have static DNS entries.

Link to akka clustering documentation:
https://doc.akka.io/docs/akka/2.5.4/scala/cluster-usage.html

# Invoker use of docker-runc

To improve performance, Invokers attempt to maintain warm containers for frequently executed actions. To optimize resource usage, the action containers are paused/unpaused between invocations.  The system can be configured to use either docker-runc or docker to perform the pause/unpause operations by setting the value of the environment variable INVOKER_USE_RUNC to true or false respectively. If not set, it will default to true (use docker-runc).

Using docker-runc obtains significantly better performance, but requires that the version of docker-runc within the invoker container is an exact version match to the docker-runc of the host environment.  Failure to get an exact version match will results in error messages like:
```
2017-09-29T20:15:54.551Z] [ERROR] [#sid_102] [RuncClient] code: 1, stdout: , stderr: json: cannot unmarshal object into Go value of type []string [marker:invoker_runc.pause_error:6830148:259]
```
When a docker-runc operations results in an error, the container will be killed by the invoker.  This results in missed opportunities for container reuse and poor performance.  Setting INVOKER_USE_RUNC to false can be used as a workaround until proper usage of docker-runc can be configured for the deployment.
