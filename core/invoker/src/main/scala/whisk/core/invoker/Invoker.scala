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
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.util.Failure

import kamon.Kamon

import org.apache.curator.retry.RetryUntilElapsed
import org.apache.curator.framework.CuratorFrameworkFactory
import org.apache.curator.framework.recipes.shared.SharedCount

import akka.Done
import akka.actor.ActorSystem
import akka.actor.CoordinatedShutdown
import akka.stream.ActorMaterializer
import whisk.common.AkkaLogging
import whisk.common.Scheduler
import whisk.core.WhiskConfig
import whisk.core.WhiskConfig._
import whisk.core.connector.MessagingProvider
import whisk.core.connector.PingMessage
import whisk.core.entity.ExecManifest
import whisk.core.entity.InstanceId
import whisk.core.entity.WhiskActivationStore
import whisk.core.entity.WhiskEntityStore
import whisk.http.BasicHttpService
import whisk.spi.SpiLoader
import whisk.utils.ExecutionContextFactory
import whisk.common.TransactionId

object Invoker {

  /**
   * An object which records the environment variables required for this component to run.
   */
  def requiredProperties =
    Map(servicePort -> 8080.toString(), dockerRegistry -> null, dockerImagePrefix -> null) ++
      ExecManifest.requiredProperties ++
      WhiskEntityStore.requiredProperties ++
      WhiskActivationStore.requiredProperties ++
      kafkaHost ++
      Map(zookeeperHostName -> "", zookeeperHostPort -> "") ++
      wskApiHost ++ Map(
      dockerImageTag -> "latest",
      invokerNumCore -> "4",
      invokerCoreShare -> "2",
      invokerContainerPolicy -> "",
      invokerContainerDns -> "",
      invokerContainerNetwork -> null,
      invokerUseRunc -> "true") ++
      Map(invokerName -> "")

  def main(args: Array[String]): Unit = {
    Kamon.start()

    implicit val ec = ExecutionContextFactory.makeCachedThreadPoolExecutionContext()
    implicit val actorSystem: ActorSystem =
      ActorSystem(name = "invoker-actor-system", defaultExecutionContext = Some(ec))
    implicit val logger = new AkkaLogging(akka.event.Logging.getLogger(actorSystem, this))

    // Prepare Kamon shutdown
    CoordinatedShutdown(actorSystem).addTask(CoordinatedShutdown.PhaseActorSystemTerminate, "shutdownKamon") { () =>
      logger.info(this, s"Shutting down Kamon with coordinated shutdown")
      Kamon.shutdown()
      Future.successful(Done)
    }

    // load values for the required properties from the environment
    implicit val config = new WhiskConfig(requiredProperties)

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

    val proposedInvokerId: Option[Int] = args.headOption.map(_.toInt)
    val assignedInvokerId = proposedInvokerId
      .map { id =>
        logger.info(this, s"invokerReg: using proposedInvokerId ${id}")
        id
      }
      .getOrElse {
        if (config.zookeeperHost.startsWith(":") || config.zookeeperHost.endsWith(":")) {
          abort(s"Must provide valid zookeeper host and port to use dynamicId assignment (${config.zookeeperHost})")
        }
        val invokerName = config.invokerName
        if (invokerName.trim.isEmpty) {
          abort("Invoker name can't be empty to use dynamicId assignment.")
        }
        logger.info(this, s"invokerReg: creating zkClient to ${config.zookeeperHost}")
        val retryPolicy = new RetryUntilElapsed(5000, 500) // retry at 500ms intervals until 5 seconds have elapsed
        val zkClient = CuratorFrameworkFactory.newClient(config.zookeeperHost, retryPolicy)
        zkClient.start()
        zkClient.blockUntilConnected();
        logger.info(this, "invokerReg: connected to zookeeper")
        val myIdPath = "/invokers/idAssignment/mapping/" + invokerName
        val assignedId = Option(zkClient.checkExists().forPath(myIdPath)) match {
          case None =>
            // path doesn't exist ==> no previous mapping for this invoker
            logger.info(this, s"invokerReg: no prior assignment of id for invoker $invokerName")
            val idCounter = new SharedCount(zkClient, "/invokers/idAssignment/counter", 0)
            idCounter.start()
            def assignId(): Int = {
              val current = idCounter.getVersionedValue()
              if (idCounter.trySetCount(current, current.getValue() + 1)) {
                current.getValue()
              } else {
                assignId()
              }
            }
            val newId = assignId()
            idCounter.close()
            zkClient.create().creatingParentContainersIfNeeded().forPath(myIdPath, BigInt(newId).toByteArray)
            logger.info(this, s"invokerReg: invoker ${invokerName} was assigned invokerId ${newId}")
            newId
          case Some(_) =>
            // path already exists ==> there is a previous mapping for this invoker we should use
            val rawOldId = zkClient.getData().forPath(myIdPath)
            val oldId = BigInt(rawOldId).intValue
            logger.info(this, s"invokerReg: invoker ${invokerName} was assigned its previous invokerId ${oldId}")
            oldId
        }
        zkClient.close()
        assignedId
      }
    val invokerInstance = InstanceId(assignedInvokerId);
    val msgProvider = SpiLoader.get[MessagingProvider]
    val producer = msgProvider.getProducer(config, ec)
    val invoker = try {
      new InvokerReactive(config, invokerInstance, producer)
    } catch {
      case e: Exception => abort(s"Failed to initialize reactive invoker: ${e.getMessage}")
    }

    Scheduler.scheduleWaitAtMost(1.seconds)(() => {
      producer.send("health", PingMessage(invokerInstance)).andThen {
        case Failure(t) => logger.error(this, s"failed to ping the controller: $t")
      }
    })

    val port = config.servicePort.toInt
    BasicHttpService.startService(new InvokerServer().route, port)(actorSystem, ActorMaterializer.create(actorSystem))
  }
}
