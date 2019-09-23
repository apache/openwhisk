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
import org.apache.openwhisk.core.connector.{
  AcknowledegmentMessage,
  CombinedCompletionAndResultMessage,
  CompletionMessage,
  ResultMessage
}
import org.apache.openwhisk.core.database.{ActivationStore, StaleParameter}
import org.apache.openwhisk.core.entity.WhiskQueries.TOP
import org.apache.openwhisk.core.entity.size._
import org.apache.openwhisk.core.entity.{
  ActivationId,
  EntityName,
  EntityPath,
  InvokerInstanceId,
  Subject,
  WhiskActivation
}
import org.junit.runner.RunWith
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.duration._

@RunWith(classOf[JUnitRunner])
class ActivationPersisterServiceTests
    extends ScalatestKafkaSpec(FreePortFinder.freePort())
    with EmbeddedKafkaLike
    with FlatSpecLike
    with Matchers
    with ScalaFutures
    with BeforeAndAfterAll
    with MockFactory
    with StreamLogging
    with PersisterServiceFixture {

  implicit override val patienceConfig: PatienceConfig = PatienceConfig(timeout = 300.seconds)
  private implicit val materializer: ActorMaterializer = ActorMaterializer()

  protected val (activationStore, artifactStore) = createMemoryActivationStore()

  override protected def createActivationConsumer(persisterConfig: PersisterConfig, activationStore: ActivationStore) =
    ActivationPersisterService.start(persisterConfig, activationStore)

  private val invokerId = InvokerInstanceId(1, userMemory = 1.MB)

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

  behavior of "ActivationPersister"

  it should "save activation in store upon event" in {
    val totalCount = 5
    val ns = "testNS"
    val rs = (1 to totalCount).map(_ => newResultMessage(ns)).toList
    produceAndAssert(rs.map(_.serialize), ns, _ == totalCount)
  }

  it should "handle duplicate events" in {
    val totalCount = 4
    val ns = "testNSConflict"
    val rm1 = newResultMessage(ns)
    val rm2 = newResultMessage(ns)
    val msgs = List(rm1, rm2, rm1, newResultMessage(ns), newResultMessage(ns))
    produceAndAssert(msgs.map(_.serialize), ns, _ == totalCount)
  }

  it should "handle mixed events" in {
    val totalCount = 5
    val ns = "testNSConflictMixed"
    val rm1 = newResultMessage(ns)
    val rm2 = newResultMessage(ns)
    val irm1 = newCombinedMessage(ns)
    val irm2 =
      CompletionMessage(TransactionId.testing, newActivation("foo"), invokerId)
    val msgs = List(rm1, rm2, newResultMessage(ns), irm1, irm2, newResultMessage(ns))
    produceAndAssert(msgs.map(_.serialize), ns, _ == totalCount)
  }

  it should "handle unknown events" in {
    val totalCount = 4
    val ns = "testNSConflictUnknown"
    val rm1 = newResultMessage(ns).serialize
    val rm2 = newResultMessage(ns).serialize
    val msgs = List(rm1, rm2, newResultMessage(ns).serialize, "{\"foo\": \"bar\"}", newResultMessage(ns).serialize)
    produceAndAssert(msgs, ns, _ == totalCount)
  }

  it should "listen to events from multiple topics" in {
    val tc1 = 3
    val ns1 = "testNSP1"
    val msgs1 = newMessages(tc1, ns1)
    produceAndAssert(msgs1, ns1, _ == tc1, "completed-1")

    val tc2 = 3
    val ns2 = "testNSP2"
    val msgs2 = newMessages(tc2, ns2)
    produceAndAssert(msgs2, ns2, _ == tc2, "completed-2")

    // Check that msg are still read from old topic
    val tc3 = 3
    val ns3 = "testNSP3"
    val msgs3 = newMessages(tc3, ns3)
    produceAndAssert(msgs3, ns3, _ == tc3, "completed-1")
  }

  private def newMessages(count: Int, namespace: String) = {
    (1 to count).map(_ => newResultMessage(namespace).serialize).toList
  }

  private def produceAndAssert(msgs: List[String],
                               ns: String,
                               predicate: Int => Boolean,
                               topic: String = topicName()): Unit = {
    produceString(topic, msgs).futureValue
    periodicalCheck[Int]("Check persisted activations count", 10, 2.seconds)(() => countActivations(ns))(predicate)
    consumer.consumerLag shouldBe 0
  }

  private def newResultMessage(namespace: String): AcknowledegmentMessage = {
    val wa: WhiskActivation = newActivation(namespace)
    ResultMessage(TransactionId.testing, wa)
  }

  private def newCombinedMessage(namespace: String): AcknowledegmentMessage = {
    CombinedCompletionAndResultMessage(TransactionId.testing, newActivation(namespace), invokerId)
  }

  private def newActivation(namespace: String) = {
    val start = 1000
    val wa = WhiskActivation(
      EntityPath(namespace),
      EntityName("testAction"),
      Subject(),
      ActivationId.generate(),
      Instant.ofEpochMilli(start),
      Instant.ofEpochMilli(start + 1000))
    wa
  }

  private def countActivations(namespace: String): Int = {
    artifactStore
      .count(WhiskActivation.view.name, List(namespace), List(namespace, TOP), 0, StaleParameter.Ok)(
        TransactionId.testing)
      .futureValue
      .toInt
  }

  private def topicName() = {
    if (persisterConfig.topicIsPattern) "completed-foo" else "activations"
  }

}
