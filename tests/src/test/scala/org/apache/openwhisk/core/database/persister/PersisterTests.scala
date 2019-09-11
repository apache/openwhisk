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
import com.typesafe.config.ConfigFactory
import common.{FreePortFinder, StreamLogging}
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.connector.{AcknowledegmentMessage, CompletionMessage, ResultMessage}
import org.apache.openwhisk.core.database.memory.MemoryArtifactStoreProvider
import org.apache.openwhisk.core.database.{ActivationStore, CacheChangeNotification, StaleParameter, UserContext}
import org.apache.openwhisk.core.entity.WhiskEntityQueries.TOP
import org.apache.openwhisk.core.entity.size._
import org.apache.openwhisk.core.entity.{
  ActivationId,
  DocumentReader,
  EntityName,
  EntityPath,
  InvokerInstanceId,
  Subject,
  WhiskActivation,
  WhiskDocumentReader
}
import org.junit.runner.RunWith
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import pureconfig.loadConfigOrThrow

import scala.concurrent.duration._

@RunWith(classOf[JUnitRunner])
class PersisterTests
    extends ScalatestKafkaSpec(FreePortFinder.freePort())
    with EmbeddedKafkaLike
    with FlatSpecLike
    with Matchers
    with ScalaFutures
    with BeforeAndAfterAll
    with MockFactory
    with StreamLogging {

  implicit override val patienceConfig: PatienceConfig = PatienceConfig(timeout = 300.seconds)
  private implicit val materializer: ActorMaterializer = ActorMaterializer()
  private val artifactStore = {
    implicit val docReader: DocumentReader = WhiskDocumentReader
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

  private var consumer: ActivationConsumer = _

  //We just need stubbing and not verification
  autoVerify = false

  override def withFixture(test: NoArgTest) = {
    var testResultHandled = false
    val outcome = try {
      val oc = super.withFixture(test)
      if (!oc.isSucceeded) {
        println(logLines.mkString("\n"))
      }
      testResultHandled = true
      oc
    } finally {
      //periodicalCheck throws Error which is not handled in general. Hence need a try/finally approach
      if (!testResultHandled) println(logLines.mkString("\n"))
      stream.reset()
    }
    outcome
  }

  protected override def beforeAll(): Unit = {
    super.beforeAll()
    consumer = Persister.start(persisterConfig, activationStore)
  }

  behavior of "ActivationPersister"

  it should "save activation in store upon event" in {
    val totalCount = 5
    val ns = "testNS"
    val rs = (1 to totalCount).map(_ => newResultMessage(ns))
    produceAndAssert(rs, ns, _ == totalCount)
  }

  it should "handle duplicate events" in {
    val totalCount = 4
    val ns = "testNSConflict"
    val rm1 = newResultMessage(ns)
    val rm2 = newResultMessage(ns)
    val msgs = List(rm1, rm2, rm1, newResultMessage(ns), newResultMessage(ns))
    produceAndAssert(msgs, ns, _ == totalCount)
  }

  it should "handle mixed events" in {
    val totalCount = 4
    val ns = "testNSConflict"
    val rm1 = newResultMessage(ns)
    val rm2 = newResultMessage(ns)
    val irm1 = ResultMessage(TransactionId.testing, Left(ActivationId.generate()))
    val irm2 =
      CompletionMessage(
        TransactionId.testing,
        ActivationId.generate(),
        isSystemError = false,
        InvokerInstanceId(1, userMemory = 1.MB))
    val msgs = List(rm1, rm2, newResultMessage(ns), irm1, irm2, newResultMessage(ns))
    produceAndAssert(msgs, ns, _ == totalCount)
  }

  //TODO Test with some random json

  private def produceAndAssert(msgs: Seq[AcknowledegmentMessage], ns: String, predicate: Int => Boolean): Unit = {
    produceString(ActivationConsumer.topic, msgs.map(_.serialize).toList).futureValue
    periodicalCheck[Int]("Check persisted activations count", 10, 2.seconds)(() => countActivations(ns))(predicate)
    consumer.consumerLag shouldBe 0
  }

  private def newResultMessage(namespace: String): AcknowledegmentMessage = {
    val start = 1000
    val wa = WhiskActivation(
      EntityPath(namespace),
      EntityName("testAction"),
      Subject(),
      ActivationId.generate(),
      Instant.ofEpochMilli(start),
      Instant.ofEpochMilli(start + 1000))
    ResultMessage(TransactionId.testing, Right(wa))
  }

  private def countActivations(namespace: String): Int = {
    artifactStore
      .count(WhiskActivation.view.name, List(namespace), List(namespace, TOP), 0, StaleParameter.Ok)(
        TransactionId.testing)
      .futureValue
      .toInt
  }

  private def persisterConfig: PersisterConfig = {
    val kafkaHost = s"localhost:$kafkaPort"
    val config = ConfigFactory.parseString(s"""whisk {
      |  persister {
      |    kafka-hosts = "$kafkaHost"
      |  }
      |}""".stripMargin).withFallback(ConfigFactory.load())
    loadConfigOrThrow[PersisterConfig](config.getConfig(Persister.configRoot))
  }
}
