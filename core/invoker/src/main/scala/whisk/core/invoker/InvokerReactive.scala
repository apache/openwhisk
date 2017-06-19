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

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success

import akka.actor.ActorRef
import akka.actor.ActorRefFactory
import akka.actor.ActorSystem
import spray.json._
import spray.json.DefaultJsonProtocol._
import whisk.common.Logging
import whisk.common.LoggingMarkers
import whisk.common.TransactionId
import whisk.core.WhiskConfig
import whisk.core.connector.ActivationMessage
import whisk.core.connector.CompletionMessage
import whisk.core.connector.MessageProducer
import whisk.core.container.{ ContainerPool => OldContainerPool }
import whisk.core.container.Interval
import whisk.core.containerpool.ContainerPool
import whisk.core.containerpool.ContainerProxy
import whisk.core.containerpool.PrewarmingConfig
import whisk.core.containerpool.Run
import whisk.core.containerpool.docker.DockerClientWithFileAccess
import whisk.core.containerpool.docker.DockerContainer
import whisk.core.containerpool.docker.RuncClient
import whisk.core.database.NoDocumentException
import whisk.core.dispatcher.ActivationFeed.FailedActivation
import whisk.core.dispatcher.MessageHandler
import whisk.core.entity._
import whisk.core.entity.ExecManifest.ImageName
import whisk.core.entity.size._
import whisk.http.Messages

class InvokerReactive(
    config: WhiskConfig,
    instance: InstanceId,
    activationFeed: ActorRef,
    producer: MessageProducer)(implicit actorSystem: ActorSystem, logging: Logging)
    extends MessageHandler(s"invoker${instance.toInt}") {

    implicit val ec = actorSystem.dispatcher

    private val entityStore = WhiskEntityStore.datastore(config)
    private val activationStore = WhiskActivationStore.datastore(config)

    implicit val docker = new DockerClientWithFileAccess()(ec)
    implicit val runc = new RuncClient(ec)

    /** Cleans up all running wsk_ containers */
    def cleanup() = {
        val cleaning = docker.ps(Seq("name" -> "wsk_"))(TransactionId.invokerNanny).flatMap { containers =>
            val removals = containers.map { id =>
                runc.resume(id)(TransactionId.invokerNanny).recoverWith {
                    // Ignore resume failures and try to remove anyway
                    case _ => Future.successful(())
                }.flatMap {
                    _ => docker.rm(id)(TransactionId.invokerNanny)
                }
            }
            Future.sequence(removals)
        }

        Await.ready(cleaning, 30.seconds)
    }
    cleanup()
    sys.addShutdownHook(cleanup())

    /** Factory used by the ContainerProxy to physically create a new container. */
    val containerFactory = (tid: TransactionId, name: String, actionImage: ImageName, userProvidedImage: Boolean, memory: ByteSize) => {
        val image = if (userProvidedImage) {
            actionImage.publicImageName
        } else {
            actionImage.localImageName(config.dockerRegistry, config.dockerImagePrefix, Some(config.dockerImageTag))
        }

        DockerContainer.create(
            tid,
            image = image,
            userProvidedImage = userProvidedImage,
            memory = memory,
            cpuShares = OldContainerPool.cpuShare(config),
            environment = Map("__OW_API_HOST" -> config.wskApiHost),
            network = config.invokerContainerNetwork,
            dnsServers = config.invokerContainerDns,
            name = Some(name))
    }

    /** Sends an active-ack. */
    val ack = (tid: TransactionId, activation: WhiskActivation, controllerInstance: InstanceId) => {
        implicit val transid = tid
        producer.send(s"completed${controllerInstance.toInt}", CompletionMessage(tid, activation, s"invoker${instance.toInt}")).andThen {
            case Success(_) => logging.info(this, s"posted completion of activation ${activation.activationId}")
        }
    }

    /** Stores an activation in the database. */
    val store = (tid: TransactionId, activation: WhiskActivation) => {
        implicit val transid = tid
        logging.info(this, "recording the activation result to the data store")
        WhiskActivation.put(activationStore, activation).andThen {
            case Success(id) => logging.info(this, s"recorded activation")
            case Failure(t)  => logging.error(this, s"failed to record activation")
        }
    }

    /** Creates a ContainerProxy Actor when being called. */
    val childFactory = (f: ActorRefFactory) => f.actorOf(ContainerProxy.props(containerFactory, ack, store))

    val prewarmKind = "nodejs:6"
    val prewarmExec = ExecManifest.runtimesManifest.resolveDefaultRuntime(prewarmKind).map { manifest =>
        new CodeExecAsString(manifest, "", None)
    }.get

    val pool = actorSystem.actorOf(ContainerPool.props(
        childFactory,
        OldContainerPool.getDefaultMaxActive(config),
        OldContainerPool.getDefaultMaxActive(config),
        activationFeed,
        Some(PrewarmingConfig(2, prewarmExec, 256.MB))))

    /** Is called when an ActivationMessage is read from Kafka */
    override def onMessage(msg: ActivationMessage)(implicit transid: TransactionId): Future[Unit] = {
        require(msg != null, "message undefined")
        require(msg.action.version.isDefined, "action version undefined")

        val start = transid.started(this, LoggingMarkers.INVOKER_ACTIVATION)
        val namespace = msg.action.path
        val name = msg.action.name
        val actionid = FullyQualifiedEntityName(namespace, name).toDocId.asDocInfo(msg.revision)
        val tran = Transaction(msg)
        val subject = msg.user.subject

        logging.info(this, s"${actionid.id} $subject ${msg.activationId}")

        // caching is enabled since actions have revision id and an updated
        // action will not hit in the cache due to change in the revision id;
        // if the doc revision is missing, then bypass cache
        if (actionid.rev == DocRevision.empty) {
            logging.warn(this, s"revision was not provided for ${actionid.id}")
        }
        WhiskAction.get(entityStore, actionid.id, actionid.rev, fromCache = actionid.rev != DocRevision.empty).flatMap { action =>
            action.toExecutableWhiskAction match {
                case Some(executable) =>
                    pool ! Run(executable, msg)
                    Future.successful(())
                case None =>
                    logging.error(this, s"non-executable action reached the invoker ${action.fullyQualifiedName(false)}")
                    Future.failed(new IllegalStateException())
            }
        }.recover {
            case t =>
                // If the action cannot be found, the user has concurrently deleted it,
                // making this an application error. All other errors are considered system
                // errors and should cause the invoker to be considered unhealthy.
                val response = t match {
                    case _: NoDocumentException => ActivationResponse.applicationError(Messages.actionRemovedWhileInvoking)
                    case _                      => ActivationResponse.whiskError(Messages.actionRemovedWhileInvoking)
                }
                val interval = Interval.zero
                val causedBy = if (msg.causedBySequence) Parameters("causedBy", "sequence".toJson) else Parameters()
                val activation = WhiskActivation(
                    activationId = msg.activationId,
                    namespace = msg.activationNamespace,
                    subject = msg.user.subject,
                    cause = msg.cause,
                    name = msg.action.name,
                    version = msg.action.version.getOrElse(SemVer()),
                    start = interval.start,
                    end = interval.end,
                    duration = Some(interval.duration.toMillis),
                    response = response,
                    annotations = {
                        Parameters("path", msg.action.toString.toJson) ++ causedBy
                    })

                activationFeed ! FailedActivation(msg.transid)
                ack(msg.transid, activation, msg.rootControllerIndex)
                store(msg.transid, activation)
        }
    }

}
