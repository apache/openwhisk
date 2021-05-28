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

package org.apache.openwhisk.core.invoker

import java.nio.charset.StandardCharsets
import java.time.Instant

import akka.Done
import akka.actor.{ActorRefFactory, ActorSystem, CoordinatedShutdown, Props}
import akka.event.Logging.InfoLevel
import akka.http.scaladsl.server.Directives.complete
import akka.http.scaladsl.server.Route
import org.apache.openwhisk.common._
import org.apache.openwhisk.common.tracing.WhiskTracerProvider
import org.apache.openwhisk.core.ack.{MessagingActiveAck, UserEventSender}
import org.apache.openwhisk.core.connector._
import org.apache.openwhisk.core.containerpool._
import org.apache.openwhisk.core.containerpool.logging.LogStoreProvider
import org.apache.openwhisk.core.database.{UserContext, _}
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.entity.size._
import org.apache.openwhisk.core.{ConfigKeys, WhiskConfig}
import org.apache.openwhisk.http.Messages
import org.apache.openwhisk.spi.SpiLoader
import pureconfig._
import pureconfig.generic.auto._
import spray.json._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object InvokerReactive extends InvokerProvider {

  override def instance(
    config: WhiskConfig,
    instance: InvokerInstanceId,
    producer: MessageProducer,
    poolConfig: ContainerPoolConfig,
    limitsConfig: ConcurrencyLimitConfig)(implicit actorSystem: ActorSystem, logging: Logging): InvokerCore =
    new InvokerReactive(config, instance, producer, poolConfig, limitsConfig)

}

class InvokerReactive(
  config: WhiskConfig,
  instance: InvokerInstanceId,
  producer: MessageProducer,
  poolConfig: ContainerPoolConfig = loadConfigOrThrow[ContainerPoolConfig](ConfigKeys.containerPool),
  limitsConfig: ConcurrencyLimitConfig = loadConfigOrThrow[ConcurrencyLimitConfig](ConfigKeys.concurrencyLimit))(
  implicit actorSystem: ActorSystem,
  logging: Logging)
    extends InvokerCore {

  implicit val ec: ExecutionContext = actorSystem.dispatcher
  implicit val cfg: WhiskConfig = config

  private val logsProvider = SpiLoader.get[LogStoreProvider].instance(actorSystem)
  logging.info(this, s"LogStoreProvider: ${logsProvider.getClass}")

  /**
   * Factory used by the ContainerProxy to physically create a new container.
   *
   * Create and initialize the container factory before kicking off any other
   * task or actor because further operation does not make sense if something
   * goes wrong here. Initialization will throw an exception upon failure.
   */
  private val containerFactory =
    SpiLoader
      .get[ContainerFactoryProvider]
      .instance(
        actorSystem,
        logging,
        config,
        instance,
        Map(
          "--cap-drop" -> Set("NET_RAW", "NET_ADMIN"),
          "--ulimit" -> Set("nofile=1024:1024"),
          "--pids-limit" -> Set("1024")) ++ logsProvider.containerParameters)
  containerFactory.init()

  CoordinatedShutdown(actorSystem)
    .addTask(CoordinatedShutdown.PhaseBeforeActorSystemTerminate, "cleanup runtime containers") { () =>
      containerFactory.cleanup()
      Future.successful(Done)
    }

  /** Initialize needed databases */
  private val entityStore = WhiskEntityStore.datastore()
  private val activationStore =
    SpiLoader.get[ActivationStoreProvider].instance(actorSystem, logging)

  private val authStore = WhiskAuthStore.datastore()

  private val namespaceBlacklist = new NamespaceBlacklist(authStore)

  Scheduler.scheduleWaitAtMost(loadConfigOrThrow[NamespaceBlacklistConfig](ConfigKeys.blacklist).pollInterval) { () =>
    logging.debug(this, "running background job to update blacklist")
    namespaceBlacklist.refreshBlacklist()(ec, TransactionId.invoker).andThen {
      case Success(set) => logging.info(this, s"updated blacklist to ${set.size} entries")
      case Failure(t)   => logging.error(this, s"error on updating the blacklist: ${t.getMessage}")
    }
  }

  /** Initialize message consumers */
  private val topic = s"${Invoker.topicPrefix}invoker${instance.toInt}"
  private val maximumContainers = (poolConfig.userMemory / MemoryLimit.MIN_MEMORY).toInt
  private val msgProvider = SpiLoader.get[MessagingProvider]

  //number of peeked messages - increasing the concurrentPeekFactor improves concurrent usage, but adds risk for message loss in case of crash
  private val maxPeek =
    math.max(maximumContainers, (maximumContainers * limitsConfig.max * poolConfig.concurrentPeekFactor).toInt)

  private val consumer =
    msgProvider.getConsumer(config, topic, topic, maxPeek, maxPollInterval = TimeLimit.MAX_DURATION + 1.minute)

  private val activationFeed = actorSystem.actorOf(Props {
    new MessageFeed("activation", logging, consumer, maxPeek, 1.second, processActivationMessage)
  })

  private val ack = {
    val sender = if (UserEvents.enabled) Some(new UserEventSender(producer)) else None
    new MessagingActiveAck(producer, instance, sender)
  }

  private val collectLogs = new LogStoreCollector(logsProvider)

  /** Stores an activation in the database. */
  private val store = (tid: TransactionId, activation: WhiskActivation, isBlocking: Boolean, context: UserContext) => {
    implicit val transid: TransactionId = tid
    activationStore.storeAfterCheck(activation, isBlocking, None, context)(tid, notifier = None, logging)
  }

  /** Creates a ContainerProxy Actor when being called. */
  private val childFactory = (f: ActorRefFactory) =>
    f.actorOf(
      ContainerProxy
        .props(containerFactory.createContainer, ack, store, collectLogs, instance, poolConfig))

  val prewarmingConfigs: List[PrewarmingConfig] = {
    ExecManifest.runtimesManifest.stemcells.flatMap {
      case (mf, cells) =>
        cells.map { cell =>
          PrewarmingConfig(cell.initialCount, new CodeExecAsString(mf, "", None), cell.memory, cell.reactive)
        }
    }.toList
  }

  private val pool =
    actorSystem.actorOf(ContainerPool.props(childFactory, poolConfig, activationFeed, prewarmingConfigs))

  def handleActivationMessage(msg: ActivationMessage)(implicit transid: TransactionId): Future[Unit] = {
    val namespace = msg.action.path
    val name = msg.action.name
    val actionid = FullyQualifiedEntityName(namespace, name).toDocId.asDocInfo(msg.revision)
    val subject = msg.user.subject

    logging.debug(this, s"${actionid.id} $subject ${msg.activationId}")

    // caching is enabled since actions have revision id and an updated
    // action will not hit in the cache due to change in the revision id;
    // if the doc revision is missing, then bypass cache
    if (actionid.rev == DocRevision.empty) logging.warn(this, s"revision was not provided for ${actionid.id}")

    WhiskAction
      .get(entityStore, actionid.id, actionid.rev, fromCache = actionid.rev != DocRevision.empty)
      .flatMap(action => {
        action.toExecutableWhiskAction match {
          case Some(executable) =>
            pool ! Run(executable, msg)
            Future.successful(())
          case None =>
            logging.error(this, s"non-executable action reached the invoker ${action.fullyQualifiedName(false)}")
            Future.failed(new IllegalStateException("non-executable action reached the invoker"))
        }
      })
      .recoverWith {
        case DocumentRevisionMismatchException(_) =>
          // if revision is mismatched, the action may have been updated,
          // so try again with the latest code
          handleActivationMessage(msg.copy(revision = DocRevision.empty))
        case t =>
          val response = t match {
            case _: NoDocumentException =>
              ActivationResponse.applicationError(Messages.actionRemovedWhileInvoking)
            case _: DocumentTypeMismatchException | _: DocumentUnreadable =>
              ActivationResponse.whiskError(Messages.actionMismatchWhileInvoking)
            case _ =>
              ActivationResponse.whiskError(Messages.actionFetchErrorWhileInvoking)
          }
          activationFeed ! MessageFeed.Processed

          val activation = generateFallbackActivation(msg, response)
          ack(
            msg.transid,
            activation,
            msg.blocking,
            msg.rootControllerIndex,
            msg.user.namespace.uuid,
            CombinedCompletionAndResultMessage(transid, activation, instance))

          store(msg.transid, activation, msg.blocking, UserContext(msg.user))
          Future.successful(())
      }
  }

  /** Is called when an ActivationMessage is read from Kafka */
  def processActivationMessage(bytes: Array[Byte]): Future[Unit] = {
    Future(ActivationMessage.parse(new String(bytes, StandardCharsets.UTF_8)))
      .flatMap(Future.fromTry)
      .flatMap { msg =>
        // The message has been parsed correctly, thus the following code needs to *always* produce at least an
        // active-ack.

        implicit val transid: TransactionId = msg.transid

        //set trace context to continue tracing
        WhiskTracerProvider.tracer.setTraceContext(transid, msg.traceContext)

        if (!namespaceBlacklist.isBlacklisted(msg.user)) {
          val start = transid.started(this, LoggingMarkers.INVOKER_ACTIVATION, logLevel = InfoLevel)
          handleActivationMessage(msg)
        } else {
          // Iff the current namespace is blacklisted, an active-ack is only produced to keep the loadbalancer protocol
          // Due to the protective nature of the blacklist, a database entry is not written.
          activationFeed ! MessageFeed.Processed

          val activation =
            generateFallbackActivation(msg, ActivationResponse.applicationError(Messages.namespacesBlacklisted))
          ack(
            msg.transid,
            activation,
            false,
            msg.rootControllerIndex,
            msg.user.namespace.uuid,
            CombinedCompletionAndResultMessage(transid, activation, instance))

          logging.warn(this, s"namespace ${msg.user.namespace.name} was blocked in invoker.")
          Future.successful(())
        }
      }
      .recoverWith {
        case t =>
          // Iff everything above failed, we have a terminal error at hand. Either the message failed
          // to deserialize, or something threw an error where it is not expected to throw.
          activationFeed ! MessageFeed.Processed
          logging.error(this, s"terminal failure while processing message: $t")
          Future.successful(())
      }
  }

  /**
   * Generates an activation with zero runtime. Usually used for error cases.
   *
   * Set the kind annotation to `Exec.UNKNOWN` since it is not known to the invoker because the action fetch failed.
   */
  private def generateFallbackActivation(msg: ActivationMessage, response: ActivationResponse): WhiskActivation = {
    val now = Instant.now
    val causedBy = if (msg.causedBySequence) {
      Some(Parameters(WhiskActivation.causedByAnnotation, JsString(Exec.SEQUENCE)))
    } else None

    WhiskActivation(
      activationId = msg.activationId,
      namespace = msg.user.namespace.name.toPath,
      subject = msg.user.subject,
      cause = msg.cause,
      name = msg.action.name,
      version = msg.action.version.getOrElse(SemVer()),
      start = now,
      end = now,
      duration = Some(0),
      response = response,
      annotations = {
        Parameters(WhiskActivation.pathAnnotation, JsString(msg.action.copy(version = None).asString)) ++
          Parameters(WhiskActivation.kindAnnotation, JsString(Exec.UNKNOWN)) ++ causedBy
      })
  }

  private val healthProducer = msgProvider.getProducer(config)
  Scheduler.scheduleWaitAtMost(1.seconds)(() => {
    healthProducer.send(s"${Invoker.topicPrefix}health", PingMessage(instance)).andThen {
      case Failure(t) => logging.error(this, s"failed to ping the controller: $t")
    }
  })

  override def enable(): Route = {
    complete("not supported")
  }

  override def disable(): Route = {
    complete("not supported")
  }

}
