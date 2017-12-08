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

package whisk.core.invoker

import java.time.Instant

import akka.actor.{ActorRefFactory, ActorSystem}
import akka.stream.scaladsl.Sink
import akka.stream.{ActorAttributes, ActorMaterializer, Supervision}
import org.apache.kafka.common.errors.RecordTooLargeException
import spray.json.DefaultJsonProtocol._
import spray.json._
import whisk.common.{Logging, LoggingMarkers, TransactionId}
import whisk.core.WhiskConfig
import whisk.core.connector.{ActivationMessage, CompletionMessage, MessageProducer, MessagingProvider}
import whisk.core.containerpool._
import whisk.core.containerpool.logging.LogStoreProvider
import whisk.core.database._
import whisk.core.entity._
import whisk.core.entity.size._
import whisk.http.Messages
import whisk.spi.SpiLoader

import scala.concurrent.Future
import scala.util.{Failure, Success}

class InvokerReactive(config: WhiskConfig, instance: InstanceId, producer: MessageProducer)(
  implicit actorSystem: ActorSystem,
  logging: Logging) {

  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec = actorSystem.dispatcher
  implicit val cfg = config

  private val logsProvider = SpiLoader.get[LogStoreProvider].logStore(actorSystem)
  logging.info(this, s"LogStoreProvider: ${logsProvider.getClass}")

  /**
   * Factory used by the ContainerProxy to physically create a new container.
   *
   * Create and initialize the container factory before kicking off any other
   * task or actor because further operation does not make sense if something
   * goes wrong here. Initialization will throw an exception upon failure.
   */
  val containerFactory =
    SpiLoader
      .get[ContainerFactoryProvider]
      .getContainerFactory(
        actorSystem,
        logging,
        config,
        instance,
        Map(
          "--cap-drop" -> Set("NET_RAW", "NET_ADMIN"),
          "--ulimit" -> Set("nofile=1024:1024"),
          "--pids-limit" -> Set("1024"),
          "--dns" -> config.invokerContainerDns.toSet) ++ logsProvider.containerParameters)
  containerFactory.init()
  sys.addShutdownHook(containerFactory.cleanup())

  /** Initialize needed databases */
  private val entityStore = WhiskEntityStore.datastore(config)
  private val activationStore = WhiskActivationStore.datastore(config)

  /** Initialize message consumers */
  val topic = s"invoker${instance.toInt}"
  val maximumContainers = config.invokerNumCore.toInt * config.invokerCoreShare.toInt
  val msgProvider = SpiLoader.get[MessagingProvider]

  /** Sends an active-ack. */
  val ack = (tid: TransactionId,
             activationResult: WhiskActivation,
             blockingInvoke: Boolean,
             controllerInstance: InstanceId) => {
    implicit val transid = tid

    def send(res: Either[ActivationId, WhiskActivation], recovery: Boolean = false) = {
      val msg = CompletionMessage(transid, res, instance)

      producer.send(s"completed${controllerInstance.toInt}", msg).andThen {
        case Success(_) =>
          logging.info(
            this,
            s"posted ${if (recovery) "recovery" else "completion"} of activation ${activationResult.activationId}")
      }
    }

    send(Right(if (blockingInvoke) activationResult else activationResult.withoutLogsOrResult)).recoverWith {
      case t if t.getCause.isInstanceOf[RecordTooLargeException] =>
        send(Left(activationResult.activationId), recovery = true)
    }
  }

  /** Stores an activation in the database. */
  val store = (tid: TransactionId, activation: WhiskActivation) => {
    implicit val transid = tid
    logging.info(this, "recording the activation result to the data store")
    WhiskActivation.put(activationStore, activation)(tid, notifier = None).andThen {
      case Success(id) => logging.info(this, s"recorded activation")
      case Failure(t)  => logging.error(this, s"failed to record activation")
    }
  }

  /** Creates a ContainerProxy Actor when being called. */
  val childFactory = (f: ActorRefFactory) =>
    f.actorOf(ContainerProxy.props(containerFactory.createContainer, ack, store, logsProvider.collectLogs, instance))

  val prewarmKind = "nodejs:6"
  val prewarmExec = ExecManifest.runtimesManifest
    .resolveDefaultRuntime(prewarmKind)
    .map { manifest =>
      new CodeExecAsString(manifest, "", None)
    }
    .get

  val pool = actorSystem.actorOf(
    ContainerPool
      .props(childFactory, maximumContainers, maximumContainers, Some(PrewarmingConfig(2, prewarmExec, 256.MB))))

  msgProvider
    .getConsumer(topic, topic, maximumContainers * 2)
    .map(msg => ActivationMessage.parse(msg))
    .filter {
      case Success(_) => true
      case Failure(t) =>
        logging.error(this, s"terminal failure while processing message: $t")
        false
    }
    .collect {
      case Success(msg) => msg
    }
    .filter(_.action.version.isDefined)
    .mapAsyncUnordered(8) { msg =>
      implicit val transid = msg.transid

      val start = transid.started(this, LoggingMarkers.INVOKER_ACTIVATION)
      val namespace = msg.action.path
      val name = msg.action.name
      val actionid = FullyQualifiedEntityName(namespace, name).toDocId.asDocInfo(msg.revision)
      val subject = msg.user.subject

      logging.info(this, s"${actionid.id} $subject ${msg.activationId}")

      // caching is enabled since actions have revision id and an updated
      // action will not hit in the cache due to change in the revision id;
      // if the doc revision is missing, then bypass cache
      if (actionid.rev == DocRevision.empty) {
        logging.warn(this, s"revision was not provided for ${actionid.id}")
      }

      WhiskAction
        .get(entityStore, actionid.id, actionid.rev, fromCache = actionid.rev != DocRevision.empty)
        .flatMap { action =>
          action.toExecutableWhiskAction match {
            case Some(executable) =>
              Future.successful(Run(executable, msg))
            case None =>
              logging.error(this, s"non-executable action reached the invoker ${action.fullyQualifiedName(false)}")
              Future.failed(new IllegalStateException("non-executable action reached the invoker"))
          }
        }
        .recoverWith {
          case t =>
            // If the action cannot be found, the user has concurrently deleted it,
            // making this an application error. All other errors are considered system
            // errors and should cause the invoker to be considered unhealthy.
            val response = t match {
              case _: NoDocumentException => ActivationResponse.applicationError(Messages.actionRemovedWhileInvoking)
              case _                      => ActivationResponse.whiskError(Messages.actionMismatchWhileInvoking)
            }
            val now = Instant.now
            val causedBy = if (msg.causedBySequence) Parameters("causedBy", "sequence".toJson) else Parameters()
            val activation = WhiskActivation(
              activationId = msg.activationId,
              namespace = msg.user.namespace.toPath,
              subject = msg.user.subject,
              cause = msg.cause,
              name = msg.action.name,
              version = msg.action.version.getOrElse(SemVer()),
              start = now,
              end = now,
              duration = Some(0),
              response = response,
              annotations = {
                Parameters("path", msg.action.toString.toJson) ++ causedBy
              })

            //activationFeed ! MessageFeed.Processed
            ack(msg.transid, activation, msg.blocking, msg.rootControllerIndex)
            store(msg.transid, activation)
            Future.failed(new IllegalStateException("concurrent action deletion"))
        }
    }
    // Per docs, Resume will just drop failed message
    .withAttributes(ActorAttributes.supervisionStrategy(_ => Supervision.Resume))
    .runWith(Sink.actorRefWithAck(pool, FlowControl.Initialize, FlowControl.Processed, FlowControl.Ignore))
}
