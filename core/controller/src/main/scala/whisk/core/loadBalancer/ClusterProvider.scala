/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.core.loadBalancer

import akka.actor.ActorSystem
import akka.actor.Address
import akka.cluster.Cluster
import akka.management.AkkaManagement
import akka.management.cluster.bootstrap.ClusterBootstrap
import scala.collection.immutable.Seq
import whisk.core.WhiskConfig
import whisk.spi.Spi

trait ClusterProvider extends Spi {
  def joinCluster(config: WhiskConfig, actorSystem: ActorSystem): Unit
}

object StaticSeedNodesClusterProvider extends ClusterProvider {
  def joinCluster(config: WhiskConfig, actorSystem: ActorSystem): Unit =
    Cluster(actorSystem).joinSeedNodes(seedNodes(config, actorSystem))

  protected[loadBalancer] def seedNodes(config: WhiskConfig, actorSystem: ActorSystem): Seq[Address] =
    config.controllerSeedNodes
      .split(' ')
      .flatMap { rawNodes =>
        val ipWithPort = rawNodes.split(":")
        ipWithPort match {
          case Array(host, port) => Seq(Address("akka.tcp", actorSystem.name, host, port.toInt))
          case _                 => Seq.empty[Address]
        }
      }
      .toIndexedSeq
}

object AkkaClusterBootstrapProvider extends ClusterProvider {
  override def joinCluster(config: WhiskConfig, actorSystem: ActorSystem): Unit = {
    //use akka management + cluster bootstrap to init the cluster
    AkkaManagement(actorSystem).start()
    ClusterBootstrap(actorSystem).start()
  }

}
