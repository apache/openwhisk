# Requirements for controller clustering

This page describes requirements related to the introduction of akka clustering in Controller. There are cases where its usage requires special treatment wrt to the underlying infrastructure/deployment model. Please carefully read it before you decide to enable it.


## Controller nodes must have static IPs/Port combination.

It guarantees that failed nodes are able to join the cluster again.
This limitation refers to the fact that Akka clustering doesn't allow to add new nodes when one of the existing members is unreachable (e.g. JVM failure). If each container receives a its ip and port dynamically upon the restart, in case of controller failure, it could come back online under a new ip/port combination which makes cluster consider it as a new member and it won't be added to the cluster (in certain cases it could join as a weeklyUp member). However, the cluster will still replicate the state across the online nodes, it will have trouble to get back to the previous state with desired number of members until the old member is explicitly "downed".

How to down the members.
1. manually (sending an HTTP or JMX request to the controller). For this case an external supervisor for the cluster is required, which will down the nodes and provide an up-to-date list of seed nodes.
2. automatically by setting the "auto-down-property" in controller that will allow the leader to down the node after a certain timeout. In order to mitigate brain split one could define a list of seed nodes which are reachable under static IPs or have static DNS entries.

Link to akka clustering documentation:
https://doc.akka.io/docs/akka/2.5.4/scala/cluster-usage.html
