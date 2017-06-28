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

package whisk.core.controller

import akka.actor.ActorSystem
import scaldi.Injector
import whisk.common.AkkaLogging
import whisk.common.Logging
import whisk.common.TransactionCounter
import whisk.core.WhiskConfig
import whisk.core.connector.MessagingProvider
import whisk.core.database.ArtifactStore
import whisk.core.entitlement.EntitlementProvider
import whisk.core.entity.DocId
import whisk.core.entity.DocRevision
import whisk.core.entity.ExecManifest
import whisk.core.entity.InstanceId
import whisk.core.entity.WhiskAction
import whisk.core.entity.WhiskEntity
import whisk.core.entity.WhiskEntityStore
import whisk.core.loadBalancer.LoadBalancerService
import whisk.spi.SharedModules
import whisk.spi.Spi
import whisk.spi.SpiFactoryModule
import whisk.spi.SpiProvider

import scala.util.Failure
import scala.util.Success

object SpiTest extends TransactionCounter {
  //    import scaldi.Injectable._
  def main(args: Array[String]): Unit = {
    implicit val actorSystem = ActorSystem("testapp")
    implicit val logger = new AkkaLogging(akka.event.Logging.getLogger(actorSystem, this))


    def requiredProperties = Map(WhiskConfig.servicePort -> 8080.toString) ++
      ExecManifest.requiredProperties ++
      RestApiCommons.requiredProperties ++
      LoadBalancerService.requiredProperties ++
      EntitlementProvider.requiredProperties

    val whiskConfig = new WhiskConfig(requiredProperties)//, propertiesFile = new File("./whisk.properties"))

    SharedModules.bind(actorSystem)
    SharedModules.bind(whiskConfig)
    SharedModules.bind(logger)

    val entityStore = WhiskEntityStore.datastore(whiskConfig)

    SharedModules.bind[ArtifactStore[WhiskEntity]](entityStore)
    println("entityStore:" + entityStore)

    val testSpi:TestSpi = TestSpi(actorSystem)

    println(s"store for testSpi: ${testSpi.store}")
    implicit val transactionId = transid()
    implicit val ec = actorSystem.dispatcher
    WhiskAction.get(entityStore, DocId("123-abc"), DocRevision("456"), fromCache = false) onComplete {
      case Success(action) =>
        logger.info(this, s"found document ${action}...")
      case Failure(t) =>
        logger.error(this, "failed to locate document...")
    }

    val producer1 = MessagingProvider(actorSystem)
    val producer2 = MessagingProvider(actorSystem)
    val producer3 = MessagingProvider(actorSystem)
    val producer4 = MessagingProvider(actorSystem)
    println(s"producer1: ${producer1}")
    println(s"producer2: ${producer2}")
    println(s"producer3: ${producer3}")
    println(s"producer4: ${producer4}")


  }

  override val numberOfInstances: Int = 1
  override val instance: InstanceId = new InstanceId(0)
}

trait TestSpi extends Spi {
  val name:String
  val store:ArtifactStore[WhiskEntity]
}
class TestSpiImpl(val name: String, val store: ArtifactStore[WhiskEntity]) extends TestSpi {
}

object TestSpi extends SpiProvider[TestSpi]("test.spi.impl")


class TestSpiProviderModule extends SpiFactoryModule[TestSpi]{
  def getInstance(implicit injector: Injector): TestSpi = {
    new TestSpiImpl("this is a test", inject[ArtifactStore[WhiskEntity]])
  }
}