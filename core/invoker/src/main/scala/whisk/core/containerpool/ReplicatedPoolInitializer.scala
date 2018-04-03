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

package whisk.core.containerpool

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.PoisonPill
import akka.actor.Props
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.CurrentClusterState
import akka.cluster.ClusterEvent.InitialStateAsEvents
import akka.cluster.ClusterEvent.MemberUp
import akka.cluster.ClusterEvent.UnreachableMember
import akka.cluster.ddata.DistributedData
import akka.cluster.ddata.ORSet
import akka.cluster.ddata.ORSetKey
import akka.cluster.ddata.Replicator
import akka.cluster.ddata.Replicator.Get
import akka.cluster.ddata.Replicator.ReadLocal
import akka.cluster.ddata.Replicator.Update
import akka.cluster.ddata.Replicator.WriteLocal
import akka.cluster.singleton.ClusterSingletonManager
import akka.cluster.singleton.ClusterSingletonManagerSettings
import java.time.Instant
import scala.collection.immutable
import spray.json.JsValue
import whisk.common.Logging
import whisk.common.TransactionId
import whisk.core.entity.ByteSize
import whisk.core.entity.CodeExecAsString
import whisk.core.entity.DocRevision
import whisk.core.entity.EntityName
import whisk.core.entity.ExecManifest
import whisk.core.entity.FullyQualifiedEntityName
import whisk.core.entity.WhiskAction
import whisk.core.entity.types.EntityStore

case object Active //sent to initiate making this ContainerPool active (when operating in active/passive mode)

sealed abstract class ReplicatedType
case class ReplicatedPrewarm(id: ContainerId, ip: ContainerAddress, kind: String, memoryLimit: ByteSize)
    extends ReplicatedType
case class ReplicatedWarm(id: ContainerId,
                          ip: ContainerAddress,
                          lastUsed: Instant,
                          invocationNamespace: String,
                          action: JsValue,
                          actionRev: String)
    extends ReplicatedType

class ReplicatedPoolInitializer(as: ActorSystem, entityStore: EntityStore)(implicit logging: Logging)
    extends ContainerPoolInitializer {

  implicit val cluster = Cluster(as)

  val replicator = DistributedData(as).replicator
  val prewarmPoolKey = ORSetKey[ReplicatedType]("containerPrewarmPoolData")
  val freePoolKey = ORSetKey[ReplicatedType]("containerFreePoolData")

  override def initPool(pool: ActorRef): Unit = {
    //do not init prewarms here
    as.actorOf(Props(new ReplicatedContainerPoolActor(replicator, prewarmPoolKey, freePoolKey, pool, entityStore)))
  }

  override def createFreePool: ContainerPoolMap =
    new ReplicatedMap[ReplicatedType](
      replicator,
      freePoolKey, {
        case _: PreWarmedData =>
          logging.debug(this, "skipping replication of PreWarmedData on freePool")
          None
        case _: NoData =>
          logging.debug(this, "skipping replication of NoData on freePool")
          None
        case w: WarmedData =>
          Some(
            ReplicatedWarm(
              w.container.id,
              w.container.addr,
              w.lastUsed,
              w.invocationNamespace.toString,
              FullyQualifiedEntityName.serdes.write(w.action.fullyQualifiedName(true)),
              w.action.rev.asString))
        case t =>
          logging.warn(this, s"could not convert ${t} to replicated freePool ")
          None
      })

  override def createPrewarmPool: ContainerPoolMap =
    new ReplicatedMap[ReplicatedType](
      replicator,
      prewarmPoolKey, {
        case p: PreWarmedData =>
          Some(ReplicatedPrewarm(p.container.id, p.container.addr, p.kind, p.memoryLimit))
        case _: NoData =>
          logging.debug(this, "skipping replication of NoData on prewarmPool")
          None
        case t =>
          logging.warn(this, s"could not convert ${t} to replicated prewarmPool ")
          None
      })
}

private class ReplicatedContainerPoolActor(replicator: ActorRef,
                                           prewarmPoolKey: ORSetKey[ReplicatedType],
                                           freePoolKey: ORSetKey[ReplicatedType],
                                           pool: ActorRef,
                                           entityStore: EntityStore)(implicit logging: Logging, cluster: Cluster)
    extends Actor { //} ContainerPool(childFactory, maxActiveContainers, maxPoolSize, feed, prewarmConfig, entityStore) {

  implicit val ec = context.dispatcher
  var active = false

  //use a ClusterSingleton to signal that this nodes ContainerPool should now become active (and resurrect pool maps)
  val replicatedPool = self
  val poolActivatorMaster = context.system.actorOf(
    ClusterSingletonManager.props(Props(new Actor {
      def receive = Actor.emptyBehavior
      override def preStart(): Unit = {
        logging.info(this, "activating pool...")
        replicatedPool ! Active

      }
    }), terminationMessage = PoisonPill, settings = ClusterSingletonManagerSettings(context.system)),
    name = "poolActivatorMaster")

  override def preStart(): Unit = {
    //do not prewarm, just subscribe to cluster
    cluster.subscribe(self, initialStateMode = InitialStateAsEvents, classOf[MemberUp], classOf[UnreachableMember])

  }
  override def postStop(): Unit = cluster.unsubscribe(self)
  override def receive = {

    case Active =>
      replicator ! Get(prewarmPoolKey, ReadLocal)
      replicator ! Get(freePoolKey, ReadLocal)
    case Replicator.GetFailure(`prewarmPoolKey`, _) =>
      logging.warn(this, "failed to locate replicated prewarms, creating new")
      pool ! Prewarm
    case Replicator.NotFound(`prewarmPoolKey`, _) =>
      logging.info(this, "no prewarms found, creating new")
      pool ! Prewarm
    case g @ Replicator.GetSuccess(`prewarmPoolKey`, _) =>
      val value = g.get(prewarmPoolKey).elements
      if (value.size == 0) {
        logging.info(this, "no existing  prewarms, creating new")
        pool ! Prewarm
      } else {
        logging
          .info(this, s"resurrecting ${value.size} prewarm containers from replicated data")

        value.foreach(r => {
          r match {
            case p: ReplicatedPrewarm =>
              val prewarmExec = ExecManifest.runtimesManifest
                .resolveDefaultRuntime(p.kind)
                .map { manifest =>
                  new CodeExecAsString(manifest, "", None)
                }
                .get
              //attachPrewarmContainer(p.id, p.ip, prewarmExec, p.memoryLimit)
              pool ! Attach(p.id, p.ip, prewarmExec, p.memoryLimit)
            case t => logging.error(this, s"cannot attach ${t} to prewarmPool ")
          }

        })
      }
    case Replicator.GetFailure(`freePoolKey`, _) =>
      logging.warn(this, "failed to locate replicated freepool, leaving empty")
    case Replicator.NotFound(`freePoolKey`, _) =>
      logging.info(this, "no freepool found, leaving empty")
    case g @ Replicator.GetSuccess(`freePoolKey`, _) =>
      val value = g.get(freePoolKey).elements
      if (value.size == 0) {
        logging.info(this, "no existing freepool, creating new")
      } else {
        logging
          .info(this, s"resurrecting ${value.size} freepool containers from replicated data")
        value.foreach(r => {
          r match {
            case f: ReplicatedWarm =>
              val invocationNamespace = EntityName(f.invocationNamespace)
              val actionid =
                FullyQualifiedEntityName.serdes.read(f.action).toDocId.asDocInfo(DocRevision(f.actionRev))
              implicit val transid = TransactionId.invokerWarmup
              WhiskAction
                .get(entityStore, actionid.id, actionid.rev, fromCache = actionid.rev != DocRevision.empty)
                .map { action =>
                  action.toExecutableWhiskAction match {
                    case Some(executable) =>
                      //attachFreeContainer(f.id, f.ip, invocationNamespace, executable)
                      pool ! AttachFree(f.id, f.ip, invocationNamespace, executable)
                    case None =>
                      logging.error(
                        this,
                        s"non-executable action reached the pool via replication ${action.fullyQualifiedName(false)}")
                  }
                }
            case t => logging.error(this, s"cannot attach ${t} to freePool ")
          }

        })
      }

    case UnreachableMember(member) =>
      logging.info(this, s"Member detected as unreachable: ${member}")
      //TODO: verify down at marathon, then remove
      Cluster.get(context.system).down(member.address)
    case c: CurrentClusterState =>
      logging.info(this, s"current cluster state ${c}")
    case MemberUp(member) =>
      logging.info(this, s"Member is up ${member}")
  }
}

/** A ContainerPoolMap that replicates adds/removes to other cluster nodes */
class ReplicatedMap[R <: ReplicatedType](
  replicator: ActorRef,
  key: ORSetKey[R],
  adapter: (ContainerData) => Option[R],
  backing: immutable.Map[ActorRef, ContainerData] = immutable.Map.empty)(implicit logging: Logging, cluster: Cluster)
    extends DefaultContainerPoolMap(backing) {
  override def +(kv: (ActorRef, ContainerData)) = {
    //first replicate
    adapter(kv._2).foreach(r => replicator ! Update(key, ORSet.empty[R], WriteLocal)(_ + r))
    //then return a new Map
    new ReplicatedMap(replicator, key, adapter, backing + kv)
  }
  override def -(k: ActorRef): ContainerPoolMap = {
    //first replicate
    backing.get(k).foreach(adapter(_).foreach(r => replicator ! Update(key, ORSet.empty[R], WriteLocal)(_ - r)))
    //then return a new Map
    new ReplicatedMap[R](replicator, key, adapter, backing - k)
  }
}
