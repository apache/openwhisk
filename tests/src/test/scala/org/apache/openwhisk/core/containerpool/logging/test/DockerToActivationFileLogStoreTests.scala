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

package org.apache.openwhisk.core.containerpool.logging.test

import java.time.Instant

import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.testkit.TestProbe
import akka.util.ByteString
import common.{StreamLogging, WskActorSystem}
import org.junit.runner.RunWith
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner
import spray.json.DefaultJsonProtocol._
import spray.json._
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.containerpool.logging.{DockerToActivationFileLogStore, LogCollectingException, LogLine}
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.http.Messages

/**
 * Includes the tests for the DockerToActivationLogStore since the behavior towards the activation storage should
 * remain exactly the same.
 */
@RunWith(classOf[JUnitRunner])
class DockerToActivationFileLogStoreTests
    extends DockerToActivationLogStoreTests
    with Matchers
    with WskActorSystem
    with StreamLogging {

  override def createStore() = new TestLogStoreTo(Sink.ignore)

  def toLoggedEvent(line: LogLine,
                    namespace: Namespace,
                    activationId: ActivationId,
                    actionName: FullyQualifiedEntityName): String = {
    val event = line.toJson.compactPrint
    val concatenated =
      s""","activationId":"${activationId.asString}","action":"${actionName.asString}","namespace":"${namespace.name.asString}","namespaceId":"${namespace.uuid.asString}""""

    event.dropRight(1) ++ concatenated ++ "}\n"
  }

  def toLoggedActivation(activation: WhiskActivation): String = {
    JsObject(activation.toJson.fields ++ Map("namespaceId" -> user.namespace.uuid.asString.toJson)).compactPrint + "\n"
  }

  behavior of "DockerToActivationFileLogStore"

  it should "read logs returned by the container,in mem and enrich + write them to the provided sink" in {
    val logs = List(LogLine(Instant.now.toString, "stdout", "this is just a test"))

    val testSource: Source[ByteString, _] = Source(logs.map(line => ByteString(line.toJson.compactPrint)))

    val testActor = TestProbe()

    val container = new TestContainer(testSource)
    val store = new TestLogStoreTo(Flow[ByteString].map(_.utf8String).to(Sink.actorRef(testActor.ref, ())))

    val collected = store.collectLogs(TransactionId.testing, user, successfulActivation, container, action)

    await(collected) shouldBe ActivationLogs(logs.map(_.toFormattedString).toVector)
    logs.foreach { line =>
      testActor.expectMsg(
        toLoggedEvent(line, user.namespace, successfulActivation.activationId, action.fullyQualifiedName(false)))
    }

    // Last message should be the full activation
    testActor.expectMsg(toLoggedActivation(successfulActivation))
  }

  it should "read logs with log collecting error with developer error" in {
    val logs = List(
      LogLine(Instant.now.toString, "stdout", "this is a log"),
      LogLine(Instant.now.toString, "stderr", Messages.logFailure))

    val testActor = TestProbe()

    val container = new TestContainer(Source(toByteString(logs)))
    val store = new TestLogStoreTo(Flow[ByteString].map(_.utf8String).to(Sink.actorRef(testActor.ref, ())))

    val ex = the[LogCollectingException] thrownBy await(
      store.collectLogs(TransactionId.testing, user, developerErrorActivation, container, action))
    val collectedLogs = ex.partialLogs.logs

    withClue("Collected logs should match provided logs:") {
      collectedLogs.dropRight(1) shouldBe logs.map(_.toFormattedString).toVector
    }

    withClue("Last line should end with developer error warning:") {
      val lastLogLine = collectedLogs.last
      lastLogLine should endWith(Messages.logWarningDeveloperError)
    }

    withClue("Provided logs should be received by log store:") {
      logs.foreach { line =>
        testActor.expectMsg(
          toLoggedEvent(line, user.namespace, developerErrorActivation.activationId, action.fullyQualifiedName(false)))
      }
    }

    withClue("Last line received by log store should contain developer error warning:") {
      testActor.expectMsgPF() {
        case s: String =>
          val ll = s.parseJson.convertTo[LogLine]
          ll.log shouldBe Messages.logWarningDeveloperError
        case _ => fail()
      }
    }

    withClue("Last message received by log store should be the activation record:") {
      testActor
        .expectMsg(toLoggedActivation(developerErrorActivation))
    }
  }

  class TestLogStoreTo(override val writeToFile: Sink[ByteString, _])
      extends DockerToActivationFileLogStore(actorSystem)
}
