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
package org.apache.openwhisk.core.database.cosmosdb.lambda

import akka.actor.{ActorSystem, CoordinatedShutdown}
import akka.event.slf4j.SLF4JLogging
import akka.stream.ActorMaterializer
import com.typesafe.config.Config
import org.apache.openwhisk.common.{AkkaLogging, Logging, TransactionId}
import org.apache.openwhisk.core.WhiskConfig
import org.apache.openwhisk.core.aws.LambdaStoreProvider
import org.apache.openwhisk.core.entity.ExecManifest
import org.slf4j.bridge.SLF4JBridgeHandler

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.Success

object LambdaBuilder extends SLF4JLogging {
  //CosmosDB changefeed support uses Java Logging.
  // Those needs to be routed to Slf4j
  SLF4JBridgeHandler.removeHandlersForRootLogger()
  SLF4JBridgeHandler.install()

  //TODO Replace with constant from RemoteCacheInvalidation
  val whisksCollection = "whisks"

  def start(config: Config)(implicit system: ActorSystem, materializer: ActorMaterializer): Unit = {
    implicit val globalConfig: Config = config
    implicit val logger = new AkkaLogging(akka.event.Logging.getLogger(system, this))
    implicit val ec = system.dispatcher
    initializeManifest()
    val invalidatorConfig = LambdaBuilderConfig.getLambdaBuilderConfig()(globalConfig)

    val builder = WhiskActionToLambdaBuilder(LambdaStoreProvider.makeStore(globalConfig))
    //Init change processor
    WhisksChangeProcessor.actionConsumer = builder
    WhisksChangeProcessor.config = invalidatorConfig
    WhisksChangeProcessor.executionContext = system.dispatcher

    val feedManager = new ChangeFeedManager(whisksCollection, classOf[WhisksChangeProcessor])
    registerShutdownTasks(system, feedManager, builder)
    log.info(s"Started the Lambda builder service")
  }

  private def initializeManifest()(implicit logger: Logging, actorSystem: ActorSystem): Unit = {
    val config = new WhiskConfig(ExecManifest.requiredProperties)
    val execManifest = ExecManifest.initialize(config)
    if (execManifest.isFailure) {
      logger.error(this, s"Invalid runtimes manifest: ${execManifest.failed.get}")
      abort("Bad configuration, cannot start.")
    }
  }

  private def registerShutdownTasks(system: ActorSystem,
                                    feedManager: ChangeFeedManager[_],
                                    producer: WhiskActionToLambdaBuilder): Unit = {
    CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseBeforeServiceUnbind, "closeBuilder") { () =>
      implicit val ec = system.dispatcher
      Future
        .successful {
          feedManager.close()
        }
        .flatMap { _ =>
          producer.close().andThen {
            case Success(_) =>
              log.info("Lambda builder queue successfully shutdown")
          }
        }
    }
  }

  private def abort(message: String)(implicit logger: Logging, actorSystem: ActorSystem) = {
    logger.error(this, message)(TransactionId.invoker)
    actorSystem.terminate()
    Await.result(actorSystem.whenTerminated, 30.seconds)
    sys.exit(1)
  }

}
