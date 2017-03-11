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
import whisk.core.containerpool.docker.DockerClient
import whisk.core.containerpool.docker.RuncClient
import whisk.core.container.{ ContainerPool => OldContainerPool }
import whisk.core.containerpool.Run
import whisk.core.connector.MessageProducer
import akka.actor.ActorRefFactory
import whisk.core.containerpool.WhiskContainer
import scala.concurrent.Await
import scala.concurrent.duration._
import whisk.core.containerpool.docker.DockerApi
import whisk.core.containerpool.docker.RuncApi

class InvokerReactive(
    config: WhiskConfig,
    instance: Int,
    activationFeed: ActorRef,
    producer: MessageProducer)(implicit actorSystem: ActorSystem, logging: Logging)
    extends MessageHandler(s"invoker$instance") {

    implicit val ec = actorSystem.dispatcher

    private val entityStore = WhiskEntityStore.datastore(config)
    private val activationStore = WhiskActivationStore.datastore(config)

    implicit val docker: DockerApi = new DockerClient()(ec)
    implicit val runc: RuncApi = new RuncClient(ec)

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

    val containerFactory = (tid: TransactionId, name: String, exec: Exec, memory: ByteSize) => {
        val codeExec = exec.asInstanceOf[CodeExec[_]]
        val image = ExecImageName.containerImageName(config.dockerRegistry, config.dockerImagePrefix, codeExec, config.dockerImageTag)
        DockerContainer.create(
            tid,
            image = image,
            userProvidedImage = codeExec.pull,
            memory = memory,
            cpuShares = OldContainerPool.cpuShare(config),
            environment = Map("__OW_API_HOST" -> config.wskApiHost),
            network = config.invokerContainerNetwork,
            name = Some(name))
    }

    val childFactory = (f: ActorRefFactory) => f.actorOf(WhiskContainer.props(containerFactory, producer, activationStore))
    val pool = actorSystem.actorOf(whisk.core.containerpool.ContainerPool.props(
        childFactory,
        OldContainerPool.getDefaultMaxActive(config),
        activationFeed))

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
            pool ! Run(action, msg)
        }
    }

}
