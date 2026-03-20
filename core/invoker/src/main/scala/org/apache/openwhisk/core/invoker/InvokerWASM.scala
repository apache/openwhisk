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
import java.util.concurrent.TimeUnit

import org.apache.pekko.actor.{ActorRef, ActorSystem, Props}
import org.apache.pekko.event.Logging.InfoLevel
import org.apache.openwhisk.common._
import org.apache.openwhisk.common.tracing.WhiskTracerProvider
import org.apache.openwhisk.core.ack.{MessagingActiveAck, UserEventSender}
import org.apache.openwhisk.core.connector._
import org.apache.openwhisk.core.containerpool._
import org.apache.openwhisk.core.containerpool.v2.{NotSupportedPoolState, TotalContainerPoolState}
import org.apache.openwhisk.core.database._
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.invoker.Invoker.InvokerEnabled
import org.apache.openwhisk.core.{ConfigKeys, WhiskConfig}
import org.apache.openwhisk.http.Messages
import org.apache.openwhisk.spi.SpiLoader
import pureconfig._
import pureconfig.generic.auto._
import spray.json._
import spray.json.DefaultJsonProtocol._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object InvokerWASM extends InvokerProvider {
  override def instance(
    config: WhiskConfig,
    instance: InvokerInstanceId,
    producer: MessageProducer,
    poolConfig: ContainerPoolConfig,
    limitsConfig: IntraConcurrencyLimitConfig)(implicit actorSystem: ActorSystem, logging: Logging): InvokerCore =
    new InvokerWASM(config, instance, producer, poolConfig, limitsConfig)
}

class InvokerWASM(
  config: WhiskConfig,
  instance: InvokerInstanceId,
  producer: MessageProducer,
  poolConfig: ContainerPoolConfig = loadConfigOrThrow[ContainerPoolConfig](ConfigKeys.containerPool),
  limitsConfig: IntraConcurrencyLimitConfig = loadConfigOrThrow[IntraConcurrencyLimitConfig](
    ConfigKeys.concurrencyLimit))(implicit actorSystem: ActorSystem, logging: Logging)
    extends InvokerCore {

  implicit val ec: ExecutionContext = actorSystem.dispatcher
  implicit val cfg: WhiskConfig = config

  private val wasmtimeBinary = "wasmtime"
  private val wasmModulePathAnnotation = "wasmModulePath"
  private val wasmtimeInvokeTimeout = 60.seconds

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

  /** Stores an activation in the database. */
  private val store = (tid: TransactionId, activation: WhiskActivation, isBlocking: Boolean, context: UserContext) => {
    implicit val transid: TransactionId = tid
    activationStore.storeAfterCheck(activation, isBlocking, None, None, context)(tid, notifier = None, logging)
  }

  private def resolveWasmModulePath(executable: ExecutableWhiskAction): Option[String] =
    executable.annotations.get(wasmModulePathAnnotation).collect { case JsString(path) => path }

  private def executeWithWasmtime(msg: ActivationMessage,
                                  executable: ExecutableWhiskAction): Future[(ActivationResponse, Instant, Instant)] = {
    val inputJson = msg.content.getOrElse(JsObject.empty).compactPrint
    val modulePathOpt = resolveWasmModulePath(executable)

    modulePathOpt match {
      case None =>
        val response = ActivationResponse.whiskError(
          s"missing '$wasmModulePathAnnotation' annotation for action ${executable.fullyQualifiedName(false)}")
        val now = Instant.now
        Future.successful((response, now, now))
      case Some(modulePath) =>
        Future {
          val started = Instant.now
          val entryPoint = executable.exec.entryPoint.getOrElse("main")
          val command = Seq(wasmtimeBinary, modulePath, "--invoke", entryPoint)
          val processBuilder = new ProcessBuilder(command: _*)
          processBuilder.redirectErrorStream(true)
          val process = processBuilder.start()

          val writer = new java.io.OutputStreamWriter(process.getOutputStream, StandardCharsets.UTF_8)
          try {
            writer.write(inputJson)
            writer.flush()
          } finally {
            writer.close()
          }

          val output = scala.io.Source.fromInputStream(process.getInputStream, StandardCharsets.UTF_8.name()).mkString
          val finished = process.waitFor(wasmtimeInvokeTimeout.toMillis, TimeUnit.MILLISECONDS)
          val response =
            if (!finished) {
              process.destroyForcibly()
              ActivationResponse.whiskError(s"wasmtime timed out after ${wasmtimeInvokeTimeout.toSeconds} seconds")
            } else {
              val exit = process.exitValue()
              if (exit == 0) {
                val json = Try(output.parseJson).getOrElse(JsObject("result" -> JsString(output.trim)))
                json match {
                  case JsObject(fields) if fields.contains(ActivationResponse.ERROR_FIELD) =>
                    ActivationResponse.applicationError(fields(ActivationResponse.ERROR_FIELD))
                  case _ =>
                    ActivationResponse.success(Some(json))
                }
              } else {
                ActivationResponse.developerError(s"wasmtime exited with code $exit: ${output.trim}")
              }
            }

          (response, started, Instant.now)
        }
    }
  }

  private def buildActivation(msg: ActivationMessage,
                              executable: ExecutableWhiskAction,
                              response: ActivationResponse,
                              start: Instant,
                              end: Instant): WhiskActivation = {
    val causedBy = if (msg.causedBySequence) {
      Some(Parameters(WhiskActivation.causedByAnnotation, JsString(Exec.SEQUENCE)))
    } else None

    val waitTime = Parameters(
      WhiskActivation.waitTimeAnnotation,
      Duration.fromNanos(java.time.Duration.between(msg.transid.meta.start, start).toNanos).toMillis.toJson)

    val binding =
      msg.action.binding.map(f => Parameters(WhiskActivation.bindingAnnotation, JsString(f.asString)))

    WhiskActivation(
      activationId = msg.activationId,
      namespace = msg.user.namespace.name.toPath,
      subject = msg.user.subject,
      cause = msg.cause,
      name = executable.name,
      version = executable.version,
      start = start,
      end = end,
      duration = Some(java.time.Duration.between(start, end).toMillis),
      response = response,
      annotations = {
        Parameters(WhiskActivation.limitsAnnotation, executable.limits.toJson) ++
          Parameters(WhiskActivation.pathAnnotation, JsString(executable.fullyQualifiedName(false).asString)) ++
          Parameters(WhiskActivation.kindAnnotation, JsString(executable.exec.kind)) ++
          Parameters(WhiskActivation.timeoutAnnotation, JsBoolean(false)) ++
          causedBy ++ binding ++ Some(waitTime)
      })
  }

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
        // action that exceed the limit cannot be executed.
        action.limits.checkLimits(msg.user)
        action.toExecutableWhiskAction match {
          case Some(executable) =>
            executeWithWasmtime(msg, executable).flatMap {
              case (response, start, end) =>
                val activation = buildActivation(msg, executable, response, start, end)
                activationFeed ! MessageFeed.Processed
                ack(
                  msg.transid,
                  activation,
                  msg.blocking,
                  msg.rootControllerIndex,
                  msg.user.namespace.uuid,
                  CombinedCompletionAndResultMessage(transid, activation, instance))
                store(msg.transid, activation, msg.blocking, UserContext(msg.user)).map(_ => ())
            }
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
            case e: ActionLimitsException =>
              ActivationResponse.applicationError(e.getMessage) // return generated failed message
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

  private def getHealthScheduler: ActorRef =
    Scheduler.scheduleWaitAtMost(1.seconds)(() => pingController(isEnabled = true))

  private def pingController(isEnabled: Boolean) = {
    healthProducer.send(s"${Invoker.topicPrefix}health", PingMessage(instance, isEnabled = Some(isEnabled))).andThen {
      case Failure(t) => logging.error(this, s"failed to ping the controller: $t")
    }
  }

  private var healthScheduler: Option[ActorRef] = Some(getHealthScheduler)

  override def enable(): String = {
    if (healthScheduler.isEmpty) {
      healthScheduler = Some(getHealthScheduler)
      s"${instance.toString} is now enabled."
    } else {
      s"${instance.toString} is already enabled."
    }
  }

  override def disable(): String = {
    pingController(isEnabled = false)
    if (healthScheduler.nonEmpty) {
      actorSystem.stop(healthScheduler.get)
      healthScheduler = None
      s"${instance.toString} is now disabled."
    } else {
      s"${instance.toString} is already disabled."
    }
  }

  override def isEnabled(): String = {
    InvokerEnabled(healthScheduler.nonEmpty).serialize()
  }

  override def backfillPrewarm(): String = {
    "not supported"
  }

  override def getPoolState(): Future[Either[NotSupportedPoolState, TotalContainerPoolState]] = {
    Future.successful(Left(NotSupportedPoolState()))
  }
}
