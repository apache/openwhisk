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

import akka.Done
import akka.actor.{ActorSystem, CoordinatedShutdown}
import com.typesafe.config.ConfigValueFactory
import kamon.Kamon
import org.apache.openwhisk.common.Https.HttpsConfig
import org.apache.openwhisk.common._
import org.apache.openwhisk.core.WhiskConfig._
import org.apache.openwhisk.core.connector.{MessageProducer, MessagingProvider}
import org.apache.openwhisk.core.containerpool.v2.{NotSupportedPoolState, TotalContainerPoolState}
import org.apache.openwhisk.core.containerpool.{Container, ContainerPoolConfig}
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.entity.size._
import org.apache.openwhisk.core.{ConfigKeys, WhiskConfig}
import org.apache.openwhisk.http.{BasicHttpService, BasicRasService}
import org.apache.openwhisk.spi.{Spi, SpiLoader}
import org.apache.openwhisk.utils.ExecutionContextFactory
import pureconfig._
import pureconfig.generic.auto._
import spray.json._

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Try

case class CmdLineArgs(uniqueName: Option[String] = None,
                       id: Option[Int] = None,
                       displayedName: Option[String] = None,
                       overwriteId: Option[Int] = None)

object Invoker {

  /**
   * Collect logs after the activation has finished.
   *
   * This method is called after an activation has finished. The logs gathered here are stored along the activation
   * record in the database.
   *
   * @param transid transaction the activation ran in
   * @param user the user who ran the activation
   * @param activation the activation record
   * @param container container used by the activation
   * @param action action that was activated
   * @return logs for the given activation
   */
  trait LogsCollector {
    def logsToBeCollected(action: ExecutableWhiskAction): Boolean = action.limits.logs.asMegaBytes != 0.MB

    def apply(transid: TransactionId,
              user: Identity,
              activation: WhiskActivation,
              container: Container,
              action: ExecutableWhiskAction): Future[ActivationLogs]
  }

  protected val protocol = loadConfigOrThrow[String]("whisk.invoker.protocol")

  val topicPrefix = loadConfigOrThrow[String](ConfigKeys.kafkaTopicsPrefix)

  object InvokerEnabled extends DefaultJsonProtocol {
    def parseJson(string: String) = Try(serdes.read(string.parseJson))
    implicit val serdes = jsonFormat(InvokerEnabled.apply _, "enabled")
  }

  case class InvokerEnabled(isEnabled: Boolean) {
    def serialize(): String = InvokerEnabled.serdes.write(this).compactPrint
  }

  /**
   * An object which records the environment variables required for this component to run.
   */
  def requiredProperties =
    Map(servicePort -> 8080.toString) ++
      ExecManifest.requiredProperties ++
      kafkaHosts ++
      wskApiHost

  def optionalProperties = zookeeperHosts.keys.toSet

  def initKamon(instance: Int): Unit = {
    // Replace the hostname of the invoker to the assigned id of the invoker.
    val newKamonConfig = Kamon.config
      .withValue("kamon.environment.host", ConfigValueFactory.fromAnyRef(s"invoker$instance"))
    Kamon.init(newKamonConfig)
  }

  def main(args: Array[String]): Unit = {
    ConfigMXBean.register()
    implicit val ec = ExecutionContextFactory.makeCachedThreadPoolExecutionContext()
    implicit val actorSystem: ActorSystem =
      ActorSystem(name = "invoker-actor-system", defaultExecutionContext = Some(ec))
    implicit val logger = new AkkaLogging(akka.event.Logging.getLogger(actorSystem, this))
    val poolConfig: ContainerPoolConfig = loadConfigOrThrow[ContainerPoolConfig](ConfigKeys.containerPool)
    val limitConfig: IntraConcurrencyLimitConfig =
      loadConfigOrThrow[IntraConcurrencyLimitConfig](ConfigKeys.concurrencyLimit)
    val tags: Seq[String] = Some(loadConfigOrThrow[String](ConfigKeys.invokerResourceTags))
      .map(_.trim())
      .filter(_ != "")
      .map(_.split(",").toSeq)
      .getOrElse(Seq.empty[String])
    val dedicatedNamespaces: Seq[String] = Some(loadConfigOrThrow[String](ConfigKeys.invokerDedicatedNamespaces))
      .map(_.trim())
      .filter(_ != "")
      .map(_.split(",").toSeq)
      .getOrElse(Seq.empty[String])

    logger.info(this, s"invoker tags: (${tags.mkString(", ")})")
    // Prepare Kamon shutdown
    CoordinatedShutdown(actorSystem).addTask(CoordinatedShutdown.PhaseActorSystemTerminate, "shutdownKamon") { () =>
      logger.info(this, s"Shutting down Kamon with coordinated shutdown")
      Kamon.stopModules().map(_ => Done)
    }

    // load values for the required properties from the environment
    implicit val config: WhiskConfig = new WhiskConfig(requiredProperties, optionalProperties)

    def abort(message: String) = {
      logger.error(this, message)(TransactionId.invoker)
      actorSystem.terminate()
      Await.result(actorSystem.whenTerminated, 30.seconds)
      sys.exit(1)
    }

    if (!config.isValid) {
      abort("Bad configuration, cannot start.")
    }

    val execManifest = ExecManifest.initialize(config)
    if (execManifest.isFailure) {
      logger.error(this, s"Invalid runtimes manifest: ${execManifest.failed.get}")
      abort("Bad configuration, cannot start.")
    }

    /** Returns Some(s) if the string is not empty with trimmed whitespace, None otherwise. */
    def nonEmptyString(s: String): Option[String] = {
      val trimmed = s.trim
      if (trimmed.nonEmpty) Some(trimmed) else None
    }

    // process command line arguments
    // We accept the command line grammar of:
    // Usage: invoker [options] [<proposedInvokerId>]
    //    --uniqueName <value>   a unique name to dynamically assign Kafka topics from Zookeeper
    //    --displayedName <value> a name to identify this invoker via invoker health protocol
    //    --id <value>     proposed invokerId
    //    --overwriteId <value> proposed invokerId to re-write with uniqueName in Zookeeper,
    //    DO NOT USE overwriteId unless sure invokerId does not exist for other uniqueName
    def parse(ls: List[String], c: CmdLineArgs): CmdLineArgs = {
      ls match {
        case "--uniqueName" :: uniqueName :: tail =>
          parse(tail, c.copy(uniqueName = nonEmptyString(uniqueName)))
        case "--displayedName" :: displayedName :: tail =>
          parse(tail, c.copy(displayedName = nonEmptyString(displayedName)))
        case "--id" :: id :: tail if Try(id.toInt).isSuccess =>
          parse(tail, c.copy(id = Some(id.toInt)))
        case "--overwriteId" :: overwriteId :: tail if Try(overwriteId.toInt).isSuccess =>
          parse(tail, c.copy(overwriteId = Some(overwriteId.toInt)))
        case Nil => c
        case _   => abort(s"Error processing command line arguments $ls")
      }
    }
    val cmdLineArgs = parse(args.toList, CmdLineArgs())
    logger.info(this, "Command line arguments parsed to yield " + cmdLineArgs)

    val assignedInvokerId = cmdLineArgs match {
      // --id is defined with a valid value, use this id directly.
      case CmdLineArgs(_, Some(id), _, _) =>
        logger.info(this, s"invokerReg: using proposedInvokerId $id")
        id

      // --uniqueName is defined with a valid value, id is empty, assign an id via zookeeper
      case CmdLineArgs(Some(unique), None, _, overwriteId) =>
        if (config.zookeeperHosts.startsWith(":") || config.zookeeperHosts.endsWith(":") ||
            config.zookeeperHosts.equals("")) {
          abort(s"Must provide valid zookeeper host and port to use dynamicId assignment (${config.zookeeperHosts})")
        }
        new InstanceIdAssigner(config.zookeeperHosts).setAndGetId(unique, overwriteId)

      case _ => abort(s"Either --id or --uniqueName must be configured with correct values")
    }

    initKamon(assignedInvokerId)

    val topicBaseName = "invoker"
    val topicName = topicPrefix + topicBaseName + assignedInvokerId

    val maxMessageBytes = Some(ActivationEntityLimit.MAX_ACTIVATION_LIMIT)
    val invokerInstance =
      InvokerInstanceId(
        assignedInvokerId,
        cmdLineArgs.uniqueName,
        cmdLineArgs.displayedName,
        poolConfig.userMemory,
        None,
        tags,
        dedicatedNamespaces)

    val msgProvider = SpiLoader.get[MessagingProvider]
    if (msgProvider
          .ensureTopic(config, topic = topicName, topicConfig = topicBaseName, maxMessageBytes = maxMessageBytes)
          .isFailure) {
      abort(s"failure during msgProvider.ensureTopic for topic $topicName")
    }

    val producer = msgProvider.getProducer(config, Some(ActivationEntityLimit.MAX_ACTIVATION_LIMIT))
    val invoker = try {
      SpiLoader.get[InvokerProvider].instance(config, invokerInstance, producer, poolConfig, limitConfig)
    } catch {
      case e: Exception => abort(s"Failed to initialize reactive invoker: ${e.getMessage}")
    }

    val port = config.servicePort.toInt
    val httpsConfig =
      if (Invoker.protocol == "https") Some(loadConfigOrThrow[HttpsConfig]("whisk.invoker.https")) else None

    val invokerServer = SpiLoader.get[InvokerServerProvider].instance(invoker)
    BasicHttpService.startHttpService(invokerServer.route, port, httpsConfig)(actorSystem)
  }
}

/**
 * An Spi for providing invoker implementation.
 */
trait InvokerProvider extends Spi {
  def instance(
    config: WhiskConfig,
    instance: InvokerInstanceId,
    producer: MessageProducer,
    poolConfig: ContainerPoolConfig,
    limitsConfig: IntraConcurrencyLimitConfig)(implicit actorSystem: ActorSystem, logging: Logging): InvokerCore
}

// this trait can be used to add common implementation
trait InvokerCore {
  def enable(): String
  def disable(): String
  def isEnabled(): String
  def backfillPrewarm(): String
  def getPoolState(): Future[Either[NotSupportedPoolState, TotalContainerPoolState]]
}

/**
 * An Spi for providing RestAPI implementation for invoker.
 * The given invoker may require corresponding RestAPI implementation.
 */
trait InvokerServerProvider extends Spi {
  def instance(
    invoker: InvokerCore)(implicit ec: ExecutionContext, actorSystem: ActorSystem, logger: Logging): BasicRasService
}
