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

package org.apache.openwhisk.core.database.persister

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory
import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.core.database.memory.MemoryArtifactStoreProvider
import org.apache.openwhisk.core.database.{ActivationStore, ArtifactStore, CacheChangeNotification, UserContext}
import org.apache.openwhisk.core.entity.{DocumentReader, WhiskActivation, WhiskDocumentReader}
import org.scalamock.scalatest.MockFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import pureconfig.loadConfigOrThrow

import scala.concurrent.duration._

trait PersisterServiceFixture extends MockFactory with BeforeAndAfterAll with ScalaFutures {
  //We just need stubbing and not verification
  autoVerify = false

  implicit override val patienceConfig: PatienceConfig = PatienceConfig(timeout = 300.seconds)
  protected def kafkaPort: Int

  protected def createActivationConsumer(persisterConfig: PersisterConfig,
                                         activationStore: ActivationStore): ActivationConsumer

  protected def activationStore: ActivationStore

  protected var consumer: ActivationConsumer = _

  override def beforeAll(): Unit = {
    MemoryArtifactStoreProvider.purgeAll()
    super.beforeAll()
    consumer = createActivationConsumer(persisterConfig, activationStore)
  }

  override def afterAll(): Unit = {
    if (consumer != null) {
      consumer.shutdown().futureValue
    }
    super.afterAll()
  }

  def createMemoryActivationStore()(implicit system: ActorSystem,
                                    logging: Logging): (ActivationStore, ArtifactStore[WhiskActivation]) = {
    implicit val materializer: ActorMaterializer = ActorMaterializer()
    implicit val docReader: DocumentReader = WhiskDocumentReader
    val artifactStore = MemoryArtifactStoreProvider.makeStore[WhiskActivation]()
    (activationStore(artifactStore), artifactStore)
  }

  private def activationStore(artifactStore: ArtifactStore[WhiskActivation]) = {
    val store = mock[ActivationStore]
    (store
      .store(_: WhiskActivation, _: UserContext)(_: TransactionId, _: Option[CacheChangeNotification]))
      .expects(*, *, *, *)
      .anyNumberOfTimes()
      .onCall { (act, _, tid, _) =>
        artifactStore.put(act)(tid)
      }
    store
  }

  protected def persisterConfig: PersisterConfig = {
    val kafkaHost = s"localhost:$kafkaPort"
    val config = ConfigFactory.parseString(s"""
      |whisk {
      |  persister {
      |    kafka-hosts = "$kafkaHost"
      |  }
      |}""".stripMargin).withFallback(ConfigFactory.load())
    loadConfigOrThrow[PersisterConfig](config.getConfig(ActivationPersisterService.configRoot))
  }
}
