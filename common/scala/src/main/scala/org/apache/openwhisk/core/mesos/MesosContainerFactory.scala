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

package org.apache.openwhisk.core.mesos

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.pattern.ask
import com.adobe.api.platform.runtime.mesos.Constraint
import com.adobe.api.platform.runtime.mesos.LIKE
import com.adobe.api.platform.runtime.mesos.LocalTaskStore
import com.adobe.api.platform.runtime.mesos.MesosClient
import com.adobe.api.platform.runtime.mesos.Subscribe
import com.adobe.api.platform.runtime.mesos.SubscribeComplete
import com.adobe.api.platform.runtime.mesos.Teardown
import com.adobe.api.platform.runtime.mesos.UNLIKE
import java.time.Instant

import pureconfig._
import pureconfig.generic.auto._

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.TimeoutException
import scala.concurrent.duration._
import scala.util.Try
import org.apache.openwhisk.common.Counter
import org.apache.openwhisk.common.Logging
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.core.WhiskConfig
import org.apache.openwhisk.core.containerpool._
import org.apache.openwhisk.core.entity.ByteSize
import org.apache.openwhisk.core.entity.ExecManifest
import org.apache.openwhisk.core.entity.InvokerInstanceId
import org.apache.openwhisk.core.entity.UUID

/**
 * Configuration for mesos timeouts
 */
case class MesosTimeoutConfig(failover: FiniteDuration,
                              taskLaunch: FiniteDuration,
                              taskDelete: FiniteDuration,
                              subscribe: FiniteDuration,
                              teardown: FiniteDuration)

/**
 * Configuration for mesos action container health checks
 */
case class MesosContainerHealthCheckConfig(portIndex: Int,
                                           delay: FiniteDuration,
                                           interval: FiniteDuration,
                                           timeout: FiniteDuration,
                                           gracePeriod: FiniteDuration,
                                           maxConsecutiveFailures: Int)

/**
 * Configuration for MesosClient
 */
case class MesosConfig(masterUrl: String,
                       masterPublicUrl: Option[String],
                       role: String,
                       mesosLinkLogMessage: Boolean,
                       constraints: Seq[String],
                       constraintDelimiter: String,
                       blackboxConstraints: Seq[String],
                       teardownOnExit: Boolean,
                       healthCheck: Option[MesosContainerHealthCheckConfig],
                       offerRefuseDuration: FiniteDuration,
                       heartbeatMaxFailures: Int,
                       timeouts: MesosTimeoutConfig) {}

class MesosContainerFactory(config: WhiskConfig,
                            actorSystem: ActorSystem,
                            logging: Logging,
                            parameters: Map[String, Set[String]],
                            containerArgs: ContainerArgsConfig =
                              loadConfigOrThrow[ContainerArgsConfig](ConfigKeys.containerArgs),
                            runtimesRegistryConfig: RuntimesRegistryConfig =
                              loadConfigOrThrow[RuntimesRegistryConfig](ConfigKeys.runtimesRegistry),
                            userImagesRegistryConfig: RuntimesRegistryConfig =
                              loadConfigOrThrow[RuntimesRegistryConfig](ConfigKeys.userImagesRegistry),
                            mesosConfig: MesosConfig = loadConfigOrThrow[MesosConfig](ConfigKeys.mesos),
                            clientFactory: (ActorSystem, MesosConfig) => ActorRef = MesosContainerFactory.createClient,
                            taskIdGenerator: () => String = MesosContainerFactory.taskIdGenerator _)
    extends ContainerFactory {

  implicit val as: ActorSystem = actorSystem
  implicit val ec: ExecutionContext = actorSystem.dispatcher

  /** Inits Mesos framework. */
  val mesosClientActor = clientFactory(as, mesosConfig)

  @volatile
  private var closed: Boolean = false

  subscribe()

  /** Subscribes Mesos actor to mesos event stream; retry on timeout (which should be unusual). */
  private def subscribe(): Future[Unit] = {
    logging.info(this, s"subscribing to Mesos master at ${mesosConfig.masterUrl}")
    mesosClientActor
      .ask(Subscribe)(mesosConfig.timeouts.subscribe)
      .mapTo[SubscribeComplete]
      .map(complete => logging.info(this, s"subscribe completed successfully... $complete"))
      .recoverWith {
        case e =>
          logging.error(this, s"subscribe failed... $e}")
          if (closed) Future.successful(()) else subscribe()
      }
  }

  override def createContainer(tid: TransactionId,
                               name: String,
                               actionImage: ExecManifest.ImageName,
                               userProvidedImage: Boolean,
                               memory: ByteSize,
                               cpuShares: Int)(implicit config: WhiskConfig, logging: Logging): Future[Container] = {
    implicit val transid = tid
    val image = actionImage.resolveImageName(Some(
      ContainerFactory.resolveRegistryConfig(userProvidedImage, runtimesRegistryConfig, userImagesRegistryConfig).url))
    val constraintStrings = if (userProvidedImage) {
      mesosConfig.blackboxConstraints
    } else {
      mesosConfig.constraints
    }

    MesosTask.create(
      mesosClientActor,
      mesosConfig,
      taskIdGenerator,
      tid,
      image,
      userProvidedImage = userProvidedImage,
      memory = memory,
      cpuShares = cpuShares,
      environment = Map("__OW_API_HOST" -> config.wskApiHost) ++ containerArgs.extraEnvVarMap,
      network = containerArgs.network,
      dnsServers = containerArgs.dnsServers,
      name = Some(name),
      //strip any "--" prefixes on parameters (should make this consistent everywhere else)
      parameters
        .map({ case (k, v) => if (k.startsWith("--")) (k.replaceFirst("--", ""), v) else (k, v) })
        ++ containerArgs.extraArgs,
      parseConstraints(constraintStrings))
  }

  /**
   * Validate that constraint strings are well formed, and ignore constraints with unknown operators
   * @param constraintStrings
   * @param logging
   * @return
   */
  def parseConstraints(constraintStrings: Seq[String])(implicit logging: Logging): Seq[Constraint] =
    constraintStrings.flatMap(cs => {
      val parts = cs.split(mesosConfig.constraintDelimiter)
      require(parts.length == 3, "constraint must be in the form <attribute><delimiter><operator><delimiter><value>")
      Seq(LIKE, UNLIKE).find(_.toString == parts(1)) match {
        case Some(o) => Some(Constraint(parts(0), o, parts(2)))
        case _ =>
          logging.warn(this, s"ignoring unsupported constraint operator ${parts(1)}")
          None
      }
    })

  override def init(): Unit = ()

  /** Cleanups any remaining Containers; should block until complete; should ONLY be run at shutdown. */
  override def cleanup(): Unit = {
    val complete: Future[Any] = mesosClientActor.ask(Teardown)(mesosConfig.timeouts.teardown)
    Try(Await.result(complete, mesosConfig.timeouts.teardown))
      .map(_ => logging.info(this, "Mesos framework teardown completed."))
      .recover {
        case _: TimeoutException => logging.error(this, "Mesos framework teardown took too long.")
        case t: Throwable =>
          logging.error(this, s"Mesos framework teardown failed : $t}")
      }
  }

  def close(): Unit = {
    closed = true
  }
}
object MesosContainerFactory {
  private def createClient(actorSystem: ActorSystem, mesosConfig: MesosConfig): ActorRef =
    actorSystem.actorOf(
      MesosClient
        .props(
          () => "whisk-containerfactory-" + UUID(),
          "whisk-containerfactory-framework",
          mesosConfig.masterUrl,
          mesosConfig.role,
          mesosConfig.timeouts.failover,
          taskStore = new LocalTaskStore,
          refuseSeconds = mesosConfig.offerRefuseDuration.toSeconds.toDouble,
          heartbeatMaxFailures = mesosConfig.heartbeatMaxFailures))

  val counter = new Counter()
  val startTime = Instant.now.getEpochSecond
  private def taskIdGenerator(): String = {
    s"whisk-${counter.next()}-${startTime}"
  }
}

object MesosContainerFactoryProvider extends ContainerFactoryProvider {
  override def instance(actorSystem: ActorSystem,
                        logging: Logging,
                        config: WhiskConfig,
                        instance: InvokerInstanceId,
                        parameters: Map[String, Set[String]]): ContainerFactory =
    new MesosContainerFactory(config, actorSystem, logging, parameters)
}
