# Mesos Support

The MesosContainerFactory enables launching action containers within a Mesos cluster. It does not affect the deployment of OpenWhisk components (invoker, controller).

## Enable

To enable MesosContainerFactory, the following TypeSafe Config propertiesy must be set

| property | details | example |
| ------------------------------- |
| whisk.spi.ContainerFactoryProvider | enable the MesosContainerFactory | whisk.core.mesos.MesosContainerFactoryProvider |
| whisk.mesos.master-url | mesos master http endpoint to be accessed from the invoker for framework subscription | http://192.168.99.100:5050 |
| whisk.mesos.master-url-public | public facing mesos master http endpoint for exposing logs to cli users | http://192.168.99.100:5050 |

To set these properties for your invoker, set the `INVOKER_OPTS` environment variable e.g.
```properties
INVOKER_OPTS="-Dwhisk.spi.ContainerFactoryProvider=whisk.core.mesos.MesosContainerFactoryProvider -Dwhisk.mesos.master-url=http://192.168.99.100:5050 -Dwhisk.mesos.master-url-public=http://192.168.99.100:5050"
```

## Known Issues

* logs are not collected from action containers

  For now, the mesos public url will be included in the logs retrieved via the wsk CLI. Once log retrieval from external sources is enabled, logs from mesos containers would have to be routed to the external source, and then retrieved from that source. 
 
* no HA or failover support (single invoker per cluster)
  
  Currently the version of mesos-actor in use does not support HA or failover. Failover support is planned to be provided by:
  
  * multiple invokers running in an Akka cluster
  * the mesos framework actor is a singleton within the cluster
  * the mesos framework actor is available from the other invoker nodes
  * if the node that contains the mesos framework actor fails :
     * the actor will be recreated on a separate invoker node
     * the actor will resubscribe to mesos scheduler API with the same ID
     * the tasks that were previously launched by the actor will be reconciled
     * normal operation resumes
     
     
  



