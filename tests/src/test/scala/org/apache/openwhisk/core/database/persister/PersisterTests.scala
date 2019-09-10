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

import java.time.Instant

import akka.kafka.testkit.scaladsl.{EmbeddedKafkaLike, ScalatestKafkaSpec}
import akka.stream.ActorMaterializer
import common.{FreePortFinder, StreamLogging}
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.database.memory.MemoryArtifactStoreProvider
import org.apache.openwhisk.core.database.{ActivationStore, CacheChangeNotification, StaleParameter, UserContext}
import org.apache.openwhisk.core.entity.WhiskEntityQueries.TOP
import org.apache.openwhisk.core.entity.{
  ActivationId,
  EntityName,
  EntityPath,
  Subject,
  WhiskActivation,
  WhiskDocumentReader
}
import org.junit.runner.RunWith
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpecLike, Matchers}

import scala.concurrent.duration._

@RunWith(classOf[JUnitRunner])
class PersisterTests
    extends ScalatestKafkaSpec(FreePortFinder.freePort())
    with EmbeddedKafkaLike
    with FlatSpecLike
    with Matchers
    with ScalaFutures
    with MockFactory
    with IntegrationPatience
    with StreamLogging {

  private implicit val materializer: ActorMaterializer = ActorMaterializer()
  private val artifactStore = {
    implicit val docReader = WhiskDocumentReader
    MemoryArtifactStoreProvider.makeStore[WhiskActivation]()
  }

  private val activationStore = {
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

  private val namespace = "testNs"

  behavior of "ActivationPersister"

  it should "save activation in store upon event" in {
    val totalCount = 5
    val acts = (1 to totalCount).map(_ => newActivation())
    produceString(ActivationConsumer.topic, acts.map(_.toJson.compactPrint))

    val config = PersisterConfig(8080, "foo", s"localhost:$kafkaPort")
    Persister.start(config, activationStore)

    periodicalCheck[Int]("Check persisted activations count", 10, 10.seconds)(countActivations)(count =>
      count == totalCount)
  }

  private def newActivation(): WhiskActivation = {
    val start = 1000
    WhiskActivation(
      EntityPath(namespace),
      EntityName("testAction"),
      Subject(),
      ActivationId.generate(),
      Instant.ofEpochMilli(start),
      Instant.ofEpochMilli(start + 1000))
  }

  private def countActivations(): Int = {
    artifactStore
      .count(WhiskActivation.view.name, List(namespace), List(namespace, TOP), 0, StaleParameter.Ok)(
        TransactionId.testing)
      .futureValue
      .toInt
  }
}
