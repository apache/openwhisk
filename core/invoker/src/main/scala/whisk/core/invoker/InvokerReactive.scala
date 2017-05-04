/*
 * Copyright 2015-2016 IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.core.invoker

import whisk.core.WhiskConfig
import akka.actor.ActorRef
import whisk.core.dispatcher.MessageHandler
import akka.actor.ActorSystem
import whisk.common.Logging
import whisk.core.connector.ActivationMessage
import whisk.common.TransactionId
import scala.concurrent.Future
import whisk.common.LoggingMarkers
import whisk.core.entity._
import whisk.core.containerpool.docker.DockerContainer
import whisk.core.containerpool.docker.DockerClientWithFileAccess
import whisk.core.containerpool.docker.RuncClient
import whisk.core.container.{ ContainerPool => OldContainerPool }
import whisk.core.containerpool.Run
import whisk.core.connector.MessageProducer
import akka.actor.ActorRefFactory
import whisk.core.containerpool.ContainerProxy
import scala.concurrent.Await
import scala.concurrent.duration._
import whisk.core.connector.CompletionMessage
import scala.util.Success
import scala.util.Failure
import whisk.core.containerpool.PrewarmingConfig
import whisk.core.container.Interval
import spray.json._
import spray.json.DefaultJsonProtocol._

class InvokerReactive(
    config: WhiskConfig,
    instance: Int,
    activationFeed: ActorRef,
    producer: MessageProducer)(implicit actorSystem: ActorSystem, logging: Logging)
    extends MessageHandler(s"invoker$instance") {

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

    val containerFactory = (tid: TransactionId, name: String, exec: CodeExec[_], memory: ByteSize) => {
        val image = ExecImageName.containerImageName(config.dockerRegistry, config.dockerImagePrefix, exec, config.dockerImageTag)
        DockerContainer.create(
            tid,
            image = image,
            userProvidedImage = exec.pull,
            memory = memory,
            cpuShares = OldContainerPool.cpuShare(config),
            environment = Map("__OW_API_HOST" -> config.wskApiHost),
            network = config.invokerContainerNetwork,
            name = Some(name))
    }

    val ack = (tid: TransactionId, activation: WhiskActivation) => {
        implicit val transid = tid
        producer.send("completed", CompletionMessage(tid, activation)).andThen {
            case Success(_) => logging.info(this, s"posted completion of activation ${activation.activationId}")
        }
    }

    val store = (tid: TransactionId, activation: WhiskActivation) => {
        implicit val transid = tid
        logging.info(this, "recording the activation result to the data store")
        WhiskActivation.put(activationStore, activation).andThen {
            case Success(id) => logging.info(this, s"recorded activation")
            case Failure(t)  => logging.error(this, s"failed to record activation")
        }
    }

    val prewarmKind = "nodejs:6"
    val prewarmExec = ExecManifest.runtimesManifest.resolveDefaultRuntime(prewarmKind).map { manifest =>
        new CodeExecAsString(manifest, "", None)
    }.get

    val childFactory = (f: ActorRefFactory) => f.actorOf(ContainerProxy.props(containerFactory, ack, store))
    val pool = actorSystem.actorOf(whisk.core.containerpool.ContainerPool.props(
        childFactory,
        OldContainerPool.getDefaultMaxActive(config),
        activationFeed,
        Some(PrewarmingConfig(2, prewarmExec))))

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
        if (actionid.rev == DocRevision()) {
            logging.error(this, s"revision was not provided for ${actionid.id}")
        }
        WhiskAction.get(entityStore, actionid.id, actionid.rev, fromCache = actionid.rev != DocRevision()).map { action =>
            // only Exec instances that are subtypes of CodeExec reach the invoker
            assume(action.exec.isInstanceOf[CodeExec[_]])
            pool ! Run(action.toExecutableWhiskAction, msg)
        }.recover {
            case _ =>
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
                    response = ActivationResponse.applicationError("action could not be found"),
                    annotations = {
                        Parameters("path", msg.action.toString.toJson) ++ causedBy
                    })

                ack(msg.transid, activation)
                store(msg.transid, activation)
        }
    }

}
