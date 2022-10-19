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

package org.apache.openwhisk.core.scheduler.container
import akka.actor.{Actor, ActorRef, ActorRefFactory, ActorSystem, Props}
import akka.event.Logging.InfoLevel
import org.apache.openwhisk.common.InvokerState.{Healthy, Offline, Unhealthy}
import org.apache.openwhisk.common._
import org.apache.openwhisk.core.connector.ContainerCreationError.{
  containerCreationErrorToString,
  NoAvailableInvokersError,
  NoAvailableResourceInvokersError
}
import org.apache.openwhisk.core.connector._
import org.apache.openwhisk.core.entity.size._
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.etcd.EtcdClient
import org.apache.openwhisk.core.etcd.EtcdKV.ContainerKeys.containerPrefix
import org.apache.openwhisk.core.etcd.EtcdKV.{ContainerKeys, InvokerKeys}
import org.apache.openwhisk.core.etcd.EtcdType._
import org.apache.openwhisk.core.scheduler.Scheduler
import org.apache.openwhisk.core.scheduler.container.ContainerManager.{sendState, updateInvokerMemory}
import org.apache.openwhisk.core.scheduler.message._
import org.apache.openwhisk.core.scheduler.queue.{MemoryQueueKey, QueuePool}
import org.apache.openwhisk.core.service._
import org.apache.openwhisk.core.{ConfigKeys, WarmUp, WhiskConfig}
import pureconfig.loadConfigOrThrow
import spray.json.DefaultJsonProtocol._
import pureconfig.generic.auto._

import java.nio.charset.StandardCharsets
import java.util.concurrent.ThreadLocalRandom
import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.collection.immutable
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}

case class ScheduledPair(msg: ContainerCreationMessage,
                         invokerId: Option[InvokerInstanceId],
                         err: Option[ContainerCreationError] = None)

case class BlackboxFractionConfig(managedFraction: Double, blackboxFraction: Double)

class ContainerManager(jobManagerFactory: ActorRefFactory => ActorRef,
                       provider: MessagingProvider,
                       schedulerInstanceId: SchedulerInstanceId,
                       etcdClient: EtcdClient,
                       config: WhiskConfig,
                       watcherService: ActorRef)(implicit actorSystem: ActorSystem, logging: Logging)
    extends Actor {
  private implicit val ec: ExecutionContextExecutor = context.dispatcher

  private val creationJobManager = jobManagerFactory(context)

  private val messagingProducer = provider.getProducer(config)

  private var warmedContainers = Set.empty[String]

  private val warmedInvokers = TrieMap[Int, String]()

  private val inProgressWarmedContainers = TrieMap.empty[String, String]

  private val warmKey = ContainerKeys.warmedPrefix
  private val invokerKey = InvokerKeys.prefix
  private val watcherName = s"container-manager"

  watcherService ! WatchEndpoint(warmKey, "", isPrefix = true, watcherName, Set(PutEvent, DeleteEvent))
  watcherService ! WatchEndpoint(invokerKey, "", isPrefix = true, watcherName, Set(PutEvent, DeleteEvent))

  override def receive: Receive = {
    case ContainerCreation(msgs, memory, invocationNamespace) =>
      createContainer(msgs, memory, invocationNamespace)

    case ContainerDeletion(invocationNamespace, fqn, revision, whiskActionMetaData) =>
      getInvokersWithOldContainer(invocationNamespace, fqn, revision)
        .map { invokers =>
          val msg = ContainerDeletionMessage(
            TransactionId.containerDeletion,
            invocationNamespace,
            fqn,
            revision,
            whiskActionMetaData)
          invokers.foreach(sendDeletionContainerToInvoker(messagingProducer, _, msg))
        }

    case rescheduling: ReschedulingCreationJob =>
      val msg = rescheduling.toCreationMessage(schedulerInstanceId, rescheduling.retry + 1)
      createContainer(
        List(msg),
        rescheduling.actionMetaData.limits.memory.megabytes.MB,
        rescheduling.invocationNamespace)

    case WatchEndpointInserted(watchKey, key, _, true) =>
      watchKey match {
        case `warmKey` => warmedContainers += key
        case `invokerKey` =>
          val invoker = InvokerKeys.getInstanceId(key)
          warmedInvokers.getOrElseUpdate(invoker.instance, {
            warmUpInvoker(invoker)
            invoker.toString
          })
      }

    case WatchEndpointRemoved(watchKey, key, _, true) =>
      watchKey match {
        case `warmKey` => warmedContainers -= key
        case `invokerKey` =>
          val invoker = InvokerKeys.getInstanceId(key)
          warmedInvokers.remove(invoker.instance)
      }

    case FailedCreationJob(cid, _, _, _, _, _) =>
      inProgressWarmedContainers.remove(cid.asString)

    case SuccessfulCreationJob(cid, _, _, _) =>
      inProgressWarmedContainers.remove(cid.asString)

    case GracefulShutdown =>
      watcherService ! UnwatchEndpoint(warmKey, isPrefix = true, watcherName)
      watcherService ! UnwatchEndpoint(invokerKey, isPrefix = true, watcherName)
      creationJobManager ! GracefulShutdown

    case _ =>
  }

  private def createContainer(msgs: List[ContainerCreationMessage], memory: ByteSize, invocationNamespace: String)(
    implicit logging: Logging): Unit = {
    logging.info(this, s"received ${msgs.size} creation message [${msgs.head.invocationNamespace}:${msgs.head.action}]")
    ContainerManager
      .getAvailableInvokers(etcdClient, memory, invocationNamespace)
      .recover({
        case t: Throwable =>
          logging.error(this, s"Unable to get available invokers: ${t.getMessage}.")
          List.empty[InvokerHealth]
      })
      .foreach { invokers =>
        if (invokers.isEmpty) {
          logging.error(this, "there is no available invoker to schedule.")
          msgs.foreach(ContainerManager.sendState(_, NoAvailableInvokersError, NoAvailableInvokersError))
        } else {
          val (coldCreations, warmedCreations) =
            ContainerManager.filterWarmedCreations(warmedContainers, inProgressWarmedContainers, invokers, msgs)

          // handle warmed creation
          val chosenInvokers: immutable.Seq[Option[(Int, ContainerCreationMessage)]] = warmedCreations.map {
            warmedCreation =>
              // update the in-progress map for warmed containers.
              // even if it is done in the filterWarmedCreations method, it is still necessary to apply the change to the original map.
              warmedCreation._3.foreach(inProgressWarmedContainers.update(warmedCreation._1.creationId.asString, _))

              // send creation message to the target invoker.
              warmedCreation._2 map { chosenInvoker =>
                val msg = warmedCreation._1
                creationJobManager ! RegisterCreationJob(msg)
                sendCreationContainerToInvoker(messagingProducer, chosenInvoker, msg)
                (chosenInvoker, msg)
              }
          }

          // update the resource usage of invokers to apply changes from warmed creations.
          val updatedInvokers = chosenInvokers.foldLeft(invokers) { (invokers, chosenInvoker) =>
            chosenInvoker match {
              case Some((chosenInvoker, msg)) =>
                updateInvokerMemory(chosenInvoker, msg.whiskActionMetaData.limits.memory.megabytes, invokers)
              case err =>
                // this is not supposed to happen.
                logging.error(this, s"warmed creation is scheduled but no invoker is chosen: $err")
                invokers
            }
          }

          // handle cold creations
          if (coldCreations.nonEmpty) {
            ContainerManager
              .schedule(updatedInvokers, coldCreations.map(_._1), memory)
              .map { pair =>
                pair.invokerId match {
                  // an invoker is assigned for the msg
                  case Some(instanceId) =>
                    creationJobManager ! RegisterCreationJob(pair.msg)
                    sendCreationContainerToInvoker(messagingProducer, instanceId.instance, pair.msg)

                  // if a chosen invoker does not exist, it means it failed to find a matching invoker for the msg.
                  case _ =>
                    pair.err.foreach(error => sendState(pair.msg, error, error))
                }
              }
          }
        }
      }
  }

  private def getInvokersWithOldContainer(invocationNamespace: String,
                                          fqn: FullyQualifiedEntityName,
                                          currentRevision: DocRevision): Future[List[Int]] = {
    val namespacePrefix = containerPrefix(ContainerKeys.namespacePrefix, invocationNamespace, fqn)
    val warmedPrefix = containerPrefix(ContainerKeys.warmedPrefix, invocationNamespace, fqn)

    for {
      existing <- etcdClient
        .getPrefix(namespacePrefix)
        .map { res =>
          res.getKvsList.asScala.map { kv =>
            parseExistingContainerKey(namespacePrefix, kv.getKey)
          }
        }
      warmed <- etcdClient
        .getPrefix(warmedPrefix)
        .map { res =>
          res.getKvsList.asScala.map { kv =>
            parseWarmedContainerKey(warmedPrefix, kv.getKey)
          }
        }
    } yield {
      (existing ++ warmed)
        .dropWhile(k => k.revision > currentRevision) // remain latest revision
        .groupBy(k => k.invokerId) // remove duplicated value
        .map(_._2.head.invokerId)
        .toList
    }
  }

  /**
   * existingKey format: {tag}/namespace/{invocationNamespace}/{namespace}/({pkg}/)/{name}/{revision}/invoker{id}/container/{containerId}
   */
  private def parseExistingContainerKey(prefix: String, existingKey: String): ContainerKeyMeta = {
    val keys = existingKey.replace(prefix, "").split("/")
    val revision = DocRevision(keys(0))
    val invokerId = keys(1).replace("invoker", "").toInt
    val containerId = keys(3)
    ContainerKeyMeta(revision, invokerId, containerId)
  }

  /**
   * warmedKey format: {tag}/warmed/{invocationNamespace}/{namespace}/({pkg}/)/{name}/{revision}/invoker/{id}/container/{containerId}
   */
  private def parseWarmedContainerKey(prefix: String, warmedKey: String): ContainerKeyMeta = {
    val keys = warmedKey.replace(prefix, "").split("/")
    val revision = DocRevision(keys(0))
    val invokerId = keys(2).toInt
    val containerId = keys(4)
    ContainerKeyMeta(revision, invokerId, containerId)
  }

  private def sendCreationContainerToInvoker(producer: MessageProducer,
                                             invoker: Int,
                                             msg: ContainerCreationMessage): Future[ResultMetadata] = {
    implicit val transid: TransactionId = msg.transid

    val topic = s"${Scheduler.topicPrefix}invoker$invoker"
    val start = transid.started(this, LoggingMarkers.SCHEDULER_KAFKA, s"posting to $topic")

    producer.send(topic, msg).andThen {
      case Success(status) =>
        transid.finished(
          this,
          start,
          s"posted creationId: ${msg.creationId} for ${msg.invocationNamespace}/${msg.action} to ${status.topic}[${status.partition}][${status.offset}]",
          logLevel = InfoLevel)
      case Failure(_) =>
        logging.error(this, s"Failed to create container for ${msg.action}, error: error on posting to topic $topic")
        transid.failed(this, start, s"error on posting to topic $topic")
    }
  }

  private def sendDeletionContainerToInvoker(producer: MessageProducer,
                                             invoker: Int,
                                             msg: ContainerDeletionMessage): Future[ResultMetadata] = {
    implicit val transid: TransactionId = msg.transid

    val topic = s"${Scheduler.topicPrefix}invoker$invoker"
    val start = transid.started(this, LoggingMarkers.SCHEDULER_KAFKA, s"posting to $topic")

    producer.send(topic, msg).andThen {
      case Success(status) =>
        transid.finished(
          this,
          start,
          s"posted deletion for ${msg.invocationNamespace}/${msg.action} to ${status.topic}[${status.partition}][${status.offset}]",
          logLevel = InfoLevel)
      case Failure(_) =>
        logging.error(this, s"Failed to delete container for ${msg.action}, error: error on posting to topic $topic")
        transid.failed(this, start, s"error on posting to topic $topic")
    }
  }

  private def warmUpInvoker(invoker: InvokerInstanceId): Unit = {
    logging.info(this, s"Warm up invoker $invoker")
    WarmUp.warmUpContainerCreationMessage(schedulerInstanceId).foreach {
      sendCreationContainerToInvoker(messagingProducer, invoker.instance, _)
    }
  }

  // warm up all invokers
  private def warmUp() = {
    // warm up exist invokers
    ContainerManager.getAvailableInvokers(etcdClient, MemoryLimit.MIN_MEMORY).map { invokers =>
      invokers.foreach { invoker =>
        warmedInvokers.getOrElseUpdate(invoker.id.instance, {
          warmUpInvoker(invoker.id)
          invoker.id.toString
        })
      }
    }

  }

  warmUp()
}

object ContainerManager {
  val fractionConfig: BlackboxFractionConfig =
    loadConfigOrThrow[BlackboxFractionConfig](ConfigKeys.fraction)

  private val managedFraction: Double = Math.max(0.0, Math.min(1.0, fractionConfig.managedFraction))
  private val blackboxFraction: Double = Math.max(1.0 - managedFraction, Math.min(1.0, fractionConfig.blackboxFraction))

  def props(jobManagerFactory: ActorRefFactory => ActorRef,
            provider: MessagingProvider,
            schedulerInstanceId: SchedulerInstanceId,
            etcdClient: EtcdClient,
            config: WhiskConfig,
            watcherService: ActorRef)(implicit actorSystem: ActorSystem, logging: Logging): Props =
    Props(new ContainerManager(jobManagerFactory, provider, schedulerInstanceId, etcdClient, config, watcherService))

  /**
   * The rng algorithm is responsible for the invoker distribution, and the better the distribution, the smaller the number of rescheduling.
   *
   */
  def rng(mod: Int): Int = ThreadLocalRandom.current().nextInt(mod)

  // Partition messages that can use warmed containers.
  // return: (list of messages that cannot use warmed containers, list of messages that can take advantage of warmed containers)
  protected[container] def filterWarmedCreations(warmedContainers: Set[String],
                                                 inProgressWarmedContainers: TrieMap[String, String],
                                                 invokers: List[InvokerHealth],
                                                 msgs: List[ContainerCreationMessage])(
    implicit logging: Logging): (List[(ContainerCreationMessage, Option[Int], Option[String])],
                                 List[(ContainerCreationMessage, Option[Int], Option[String])]) = {
    val warmedApplied = msgs.map { msg =>
      val warmedPrefix =
        containerPrefix(ContainerKeys.warmedPrefix, msg.invocationNamespace, msg.action, Some(msg.revision))
      val container = warmedContainers
        .filter(!inProgressWarmedContainers.values.toSeq.contains(_))
        .find { container =>
          if (container.startsWith(warmedPrefix)) {
            logging.info(this, s"Choose a warmed container $container")

            // this is required to exclude already chosen invokers
            inProgressWarmedContainers.update(msg.creationId.asString, container)
            true
          } else
            false
        }

      // chosenInvoker is supposed to have only one item
      val chosenInvoker = container
        .map(_.split("/").takeRight(3).apply(0))
        // filter warmed containers in disabled invokers
        .filter(
          invoker =>
            invokers
            // filterWarmedCreations method is supposed to receive healthy invokers only but this will make sure again only healthy invokers are used.
              .filter(invoker => invoker.status.isUsable)
              .map(_.id.instance)
              .contains(invoker.toInt))

      if (chosenInvoker.nonEmpty && container.nonEmpty) {
        (msg, Some(chosenInvoker.get.toInt), Some(container.get))
      } else
        (msg, None, None)
    }

    warmedApplied.partition { item =>
      if (item._2.nonEmpty) false
      else true
    }
  }

  protected[container] def updateInvokerMemory(invokerId: Int,
                                               requiredMemory: Long,
                                               invokers: List[InvokerHealth]): List[InvokerHealth] = {
    // it must be compared to the instance unique id
    val index = invokers.indexOf(invokers.filter(p => p.id.instance == invokerId).head)
    val invoker = invokers(index)

    // if the invoker has less than minimum memory, drop it from the list.
    if (invoker.id.userMemory.toMB - requiredMemory < MemoryLimit.MIN_MEMORY.toMB) {
      // drop the nth element
      val split = invokers.splitAt(index)
      val _ :: t1 = split._2
      split._1 ::: t1
    } else {
      invokers.updated(
        index,
        invoker.copy(id = invoker.id.copy(userMemory = invoker.id.userMemory - requiredMemory.MB)))
    }
  }

  protected[container] def updateInvokerMemory(invokerId: Option[InvokerInstanceId],
                                               requiredMemory: Long,
                                               invokers: List[InvokerHealth]): List[InvokerHealth] = {
    invokerId match {
      case Some(instanceId) =>
        updateInvokerMemory(instanceId.instance, requiredMemory, invokers)

      case None =>
        // do nothing
        invokers
    }
  }

  /**
   * Assign an invoker to a message
   *
   * Assumption
   *  - The memory of each invoker is larger than minMemory.
   *  - Messages that are not assigned an invoker are discarded.
   *
   * @param invokers Available invoker pool
   * @param msgs Messages to which the invoker will be assigned
   * @param minMemory Minimum memory for all invokers
   * @return A pair of messages and assigned invokers
   */
  def schedule(invokers: List[InvokerHealth], msgs: List[ContainerCreationMessage], minMemory: ByteSize)(
    implicit logging: Logging): List[ScheduledPair] = {
    logging.info(this, s"usable total invoker size: ${invokers.size}")
    val noTaggedInvokers = invokers.filter(_.id.tags.isEmpty)
    val managed = Math.max(1, Math.ceil(noTaggedInvokers.size.toDouble * managedFraction).toInt)
    val blackboxes = Math.max(1, Math.floor(noTaggedInvokers.size.toDouble * blackboxFraction).toInt)
    val managedInvokers = noTaggedInvokers.take(managed)
    val blackboxInvokers = noTaggedInvokers.takeRight(blackboxes)
    logging.info(
      this,
      s"${msgs.size} creation messages for ${msgs.head.invocationNamespace}/${msgs.head.action}, managedFraction:$managedFraction, blackboxFraction:$blackboxFraction, managed invoker size:$managed, blackboxes invoker size:$blackboxes")
    val list = msgs
      .foldLeft((List.empty[ScheduledPair], invokers)) { (tuple, msg: ContainerCreationMessage) =>
        val pairs = tuple._1
        val candidates = tuple._2

        val requiredResources =
          msg.whiskActionMetaData.annotations
            .getAs[Seq[String]](Annotations.InvokerResourcesAnnotationName)
            .getOrElse(Seq.empty[String])
        val resourcesStrictPolicy = msg.whiskActionMetaData.annotations
          .getAs[Boolean](Annotations.InvokerResourcesStrictPolicyAnnotationName)
          .getOrElse(true)
        val isBlackboxInvocation = msg.whiskActionMetaData.toExecutableWhiskAction.exists(_.exec.pull)
        if (requiredResources.isEmpty) {
          // only choose managed invokers or blackbox invokers
          val wantedInvokers = if (isBlackboxInvocation) {
            logging.info(this, s"[${msg.invocationNamespace}/${msg.action}] looking for blackbox invokers to schedule.")
            candidates
              .filter(
                c =>
                  blackboxInvokers
                    .map(b => b.id.instance)
                    .contains(c.id.instance) && c.id.userMemory.toMB >= msg.whiskActionMetaData.limits.memory.megabytes)
              .toSet
          } else {
            logging.info(this, s"[${msg.invocationNamespace}/${msg.action}] looking for managed invokers to schedule.")
            candidates
              .filter(
                c =>
                  managedInvokers
                    .map(m => m.id.instance)
                    .contains(c.id.instance) && c.id.userMemory.toMB >= msg.whiskActionMetaData.limits.memory.megabytes)
              .toSet
          }
          val taggedInvokers = candidates.filter(_.id.tags.nonEmpty)

          if (wantedInvokers.nonEmpty) {
            val scheduledPair = chooseInvokerFromCandidates(wantedInvokers.toList, msg)
            val updatedInvokers =
              updateInvokerMemory(scheduledPair.invokerId, msg.whiskActionMetaData.limits.memory.megabytes, invokers)
            (scheduledPair :: pairs, updatedInvokers)
          } else if (taggedInvokers.nonEmpty) { // if not found from the wanted invokers, choose tagged invokers then
            logging.info(
              this,
              s"[${msg.invocationNamespace}/${msg.action}] since there is no available non-tagged invoker, choose one among tagged invokers.")
            val scheduledPair = chooseInvokerFromCandidates(taggedInvokers, msg)
            val updatedInvokers =
              updateInvokerMemory(scheduledPair.invokerId, msg.whiskActionMetaData.limits.memory.megabytes, invokers)
            (scheduledPair :: pairs, updatedInvokers)
          } else {
            logging.error(
              this,
              s"[${msg.invocationNamespace}/${msg.action}] there is no invoker available to schedule to schedule.")
            val scheduledPair =
              ScheduledPair(msg, invokerId = None, Some(NoAvailableInvokersError))
            (scheduledPair :: pairs, invokers)
          }
        } else {
          logging.info(this, s"[${msg.invocationNamespace}/${msg.action}] looking for tagged invokers to schedule.")
          val wantedInvokers = candidates.filter(health => requiredResources.toSet.subsetOf(health.id.tags.toSet))
          if (wantedInvokers.nonEmpty) {
            val scheduledPair = chooseInvokerFromCandidates(wantedInvokers, msg)
            val updatedInvokers =
              updateInvokerMemory(scheduledPair.invokerId, msg.whiskActionMetaData.limits.memory.megabytes, invokers)
            (scheduledPair :: pairs, updatedInvokers)
          } else if (resourcesStrictPolicy) {
            logging.error(
              this,
              s"[${msg.invocationNamespace}/${msg.action}] there is no available invoker with the resource: ${requiredResources}")
            val scheduledPair =
              ScheduledPair(msg, invokerId = None, Some(NoAvailableResourceInvokersError))
            (scheduledPair :: pairs, invokers)
          } else {
            logging.info(
              this,
              s"[${msg.invocationNamespace}/${msg.action}] since there is no available invoker with the resource, choose any invokers without the resource.")
            val (noTaggedInvokers, taggedInvokers) = candidates.partition(_.id.tags.isEmpty)
            if (noTaggedInvokers.nonEmpty) { // choose no tagged invokers first
              val scheduledPair = chooseInvokerFromCandidates(noTaggedInvokers, msg)
              val updatedInvokers =
                updateInvokerMemory(scheduledPair.invokerId, msg.whiskActionMetaData.limits.memory.megabytes, invokers)
              (scheduledPair :: pairs, updatedInvokers)
            } else {
              val leftInvokers =
                taggedInvokers.filterNot(health => requiredResources.toSet.subsetOf(health.id.tags.toSet))
              if (leftInvokers.nonEmpty) {
                val scheduledPair = chooseInvokerFromCandidates(leftInvokers, msg)
                val updatedInvokers =
                  updateInvokerMemory(
                    scheduledPair.invokerId,
                    msg.whiskActionMetaData.limits.memory.megabytes,
                    invokers)
                (scheduledPair :: pairs, updatedInvokers)
              } else {
                logging.error(this, s"[${msg.invocationNamespace}/${msg.action}] no available invoker is found")
                val scheduledPair =
                  ScheduledPair(msg, invokerId = None, Some(NoAvailableInvokersError))
                (scheduledPair :: pairs, invokers)
              }
            }
          }
        }
      }
      ._1 // pairs
    list
  }

  @tailrec
  protected[container] def chooseInvokerFromCandidates(candidates: List[InvokerHealth], msg: ContainerCreationMessage)(
    implicit logging: Logging): ScheduledPair = {
    val requiredMemory = msg.whiskActionMetaData.limits.memory
    if (candidates.isEmpty) {
      ScheduledPair(msg, invokerId = None, Some(NoAvailableInvokersError))
    } else if (candidates.forall(p => p.id.userMemory.toMB < requiredMemory.megabytes)) {
      ScheduledPair(msg, invokerId = None, Some(NoAvailableResourceInvokersError))
    } else {
      val idx = rng(mod = candidates.size)
      val instance = candidates(idx)
      if (instance.id.userMemory.toMB < requiredMemory.megabytes) {
        val split = candidates.splitAt(idx)
        val _ :: t1 = split._2
        chooseInvokerFromCandidates(split._1 ::: t1, msg)
      } else {
        ScheduledPair(msg, invokerId = Some(instance.id))
      }
    }
  }

  private def sendState(msg: ContainerCreationMessage, err: ContainerCreationError, reason: String)(
    implicit logging: Logging): Unit = {
    val state = FailedCreationJob(msg.creationId, msg.invocationNamespace, msg.action, msg.revision, err, reason)
    QueuePool.get(MemoryQueueKey(state.invocationNamespace, state.action.toDocId.asDocInfo(state.revision))) match {
      case Some(memoryQueueValue) if memoryQueueValue.isLeader =>
        memoryQueueValue.queue ! state
      case _ =>
        logging.error(this, s"get a $state for a nonexistent memory queue or a follower")
    }
  }

  protected[scheduler] def getAvailableInvokers(etcd: EtcdClient, minMemory: ByteSize, invocationNamespace: String)(
    implicit executor: ExecutionContext): Future[List[InvokerHealth]] = {
    etcd
      .getPrefix(InvokerKeys.prefix)
      .map { res =>
        res.getKvsList.asScala
          .map { kv =>
            InvokerResourceMessage
              .parse(kv.getValue.toString(StandardCharsets.UTF_8))
              .map { resourceMessage =>
                val status = resourceMessage.status match {
                  case Healthy.asString   => Healthy
                  case Unhealthy.asString => Unhealthy
                  case _                  => Offline
                }

                val temporalId = InvokerKeys.getInstanceId(kv.getKey.toString(StandardCharsets.UTF_8))
                val invoker = temporalId.copy(
                  userMemory = resourceMessage.freeMemory.MB,
                  busyMemory = Some(resourceMessage.busyMemory.MB),
                  tags = resourceMessage.tags,
                  dedicatedNamespaces = resourceMessage.dedicatedNamespaces)

                InvokerHealth(invoker, status)
              }
              .getOrElse(InvokerHealth(InvokerInstanceId(kv.getKey, userMemory = 0.MB), Offline))
          }
          .filter(i => i.status.isUsable)
          .filter(_.id.userMemory >= minMemory)
          .filter { invoker =>
            invoker.id.dedicatedNamespaces.isEmpty || invoker.id.dedicatedNamespaces.contains(invocationNamespace)
          }
          .toList
      }
  }

  protected[scheduler] def getAvailableInvokers(etcd: EtcdClient, minMemory: ByteSize)(
    implicit executor: ExecutionContext): Future[List[InvokerHealth]] = {
    etcd
      .getPrefix(InvokerKeys.prefix)
      .map { res =>
        res.getKvsList.asScala
          .map { kv =>
            InvokerResourceMessage
              .parse(kv.getValue.toString(StandardCharsets.UTF_8))
              .map { resourceMessage =>
                val status = resourceMessage.status match {
                  case Healthy.asString   => Healthy
                  case Unhealthy.asString => Unhealthy
                  case _                  => Offline
                }

                val temporalId = InvokerKeys.getInstanceId(kv.getKey.toString(StandardCharsets.UTF_8))
                val invoker = temporalId.copy(
                  userMemory = resourceMessage.freeMemory.MB,
                  busyMemory = Some(resourceMessage.busyMemory.MB),
                  tags = resourceMessage.tags,
                  dedicatedNamespaces = resourceMessage.dedicatedNamespaces)
                InvokerHealth(invoker, status)
              }
              .getOrElse(InvokerHealth(InvokerInstanceId(kv.getKey, userMemory = 0.MB), Offline))
          }
          .filter(i => i.status.isUsable)
          .filter(_.id.userMemory >= minMemory)
          .toList
      }
  }

}

case class NoCapacityException(msg: String) extends Exception(msg)
