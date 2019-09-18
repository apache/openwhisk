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

import akka.actor.ActorSystem
import common.{StreamLogging, WskActorSystem}
import org.junit.runner.RunWith
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.junit.JUnitRunner
import org.apache.openwhisk.core.containerpool.logging.{
  DockerToActivationLogStoreProvider,
  LogCollectingException,
  LogLine
}
import org.apache.openwhisk.core.entity.ExecManifest.{ImageName, RuntimeManifest}
import org.apache.openwhisk.core.entity._
import java.time.Instant

import akka.stream.scaladsl.Source
import akka.util.ByteString
import spray.json._
import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.core.containerpool.{Container, ContainerAddress, ContainerId}
import org.apache.openwhisk.http.Messages

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._

@RunWith(classOf[JUnitRunner])
class DockerToActivationLogStoreTests extends FlatSpec with Matchers with WskActorSystem with StreamLogging {
  def await[T](future: Future[T]) = Await.result(future, 1.minute)

  val uuid = UUID()
  val user =
    Identity(Subject(), Namespace(EntityName("testSpace"), uuid), BasicAuthenticationAuthKey(uuid, Secret()))
  val exec = CodeExecAsString(RuntimeManifest("actionKind", ImageName("testImage")), "testCode", None)
  val action = ExecutableWhiskAction(user.namespace.name.toPath, EntityName("actionName"), exec)
  val successfulActivation =
    WhiskActivation(
      user.namespace.name.toPath,
      action.name,
      user.subject,
      ActivationId.generate(),
      Instant.EPOCH,
      Instant.EPOCH)
  val developerErrorActivation = successfulActivation.copy(response = ActivationResponse.developerError("failed"))

  def toByteString(logs: List[LogLine]) = logs.map(_.toJson.compactPrint).map(ByteString.apply)

  val tid = TransactionId.testing

  def createStore() = DockerToActivationLogStoreProvider.instance(actorSystem)

  behavior of "DockerLogStore"

  it should "read logs into a sequence and parse them into the specified format" in {
    val store = createStore()

    val logs = List(
      LogLine(Instant.now.toString, "stdout", "this is a log"),
      LogLine(Instant.now.toString, "stdout", "this is a log too"))
    val container = new TestContainer(Source(toByteString(logs)))

    await(store.collectLogs(tid, user, successfulActivation, container, action)) shouldBe ActivationLogs(
      logs.map(_.toFormattedString).toVector)
  }

  it should "read logs into a sequence and parse them into the specified format with developer error" in {
    val store = createStore()

    val logs = List(
      LogLine(Instant.now.toString, "stdout", "this is a log"),
      LogLine(Instant.now.toString, "stdout", "this is a log too"))
    val container = new TestContainer(Source(toByteString(logs)))

    val collectedLogs = await(store.collectLogs(tid, user, developerErrorActivation, container, action)).logs

    withClue("Collected logs should be empty:") {
      collectedLogs.dropRight(1) shouldBe logs.map(_.toFormattedString).toVector
    }

    withClue("Last line should end with developer error warning:") {
      val lastLogLine = collectedLogs.last
      lastLogLine should endWith(Messages.logWarningDeveloperError)
    }
  }

  it should "accept an empty log" in {
    val store = createStore()

    val logs = List.empty[LogLine]
    val container = new TestContainer(Source(toByteString(logs)))

    await(store.collectLogs(tid, user, successfulActivation, container, action)) shouldBe ActivationLogs(
      Vector.empty[String])
  }

  it should "accept an empty log with developer error" in {
    val store = createStore()

    val logs = List.empty[LogLine]
    val container = new TestContainer(Source(toByteString(logs)))

    val collectedLogs = await(store.collectLogs(tid, user, developerErrorActivation, container, action)).logs

    withClue("Collected logs should be empty:") {
      collectedLogs.dropRight(1) shouldBe Vector.empty[String]
    }

    withClue("Last line should end with developer error warning:") {
      val lastLogLine = collectedLogs.last
      lastLogLine should endWith(Messages.logWarningDeveloperError)
    }
  }

  it should "report an error if the logs contain an 'official' notice of such" in {
    val store = createStore()

    val logs = List(
      LogLine(Instant.now.toString, "stdout", "this is a log"),
      LogLine(Instant.now.toString, "stderr", Messages.logFailure))
    val container = new TestContainer(Source(toByteString(logs)))

    val ex = the[LogCollectingException] thrownBy await(
      store.collectLogs(tid, user, successfulActivation, container, action))
    ex.partialLogs shouldBe ActivationLogs(logs.map(_.toFormattedString).toVector)
  }

  it should "report an error if the logs contain an 'official' notice of such with developer error" in {
    val store = createStore()

    val logs = List(
      LogLine(Instant.now.toString, "stdout", "this is a log"),
      LogLine(Instant.now.toString, "stderr", Messages.logFailure))
    val container = new TestContainer(Source(toByteString(logs)))

    val ex = the[LogCollectingException] thrownBy await(
      store.collectLogs(tid, user, developerErrorActivation, container, action))
    val collectedLogs = ex.partialLogs.logs

    withClue("Collected logs should match provided logs:") {
      collectedLogs.dropRight(1) shouldBe logs.map(_.toFormattedString).toVector
    }

    withClue("Last line should end with developer error warning:") {
      val lastLogLine = collectedLogs.last
      lastLogLine should endWith(Messages.logWarningDeveloperError)
    }
  }

  it should "report an error if logs have been truncated" in {
    val store = createStore()

    val logs = List(
      LogLine(Instant.now.toString, "stdout", "this is a log"),
      LogLine(Instant.now.toString, "stderr", Messages.truncateLogs(action.limits.logs.asMegaBytes)))
    val container = new TestContainer(Source(toByteString(logs)))

    val ex = the[LogCollectingException] thrownBy await(
      store.collectLogs(tid, user, successfulActivation, container, action))
    ex.partialLogs shouldBe ActivationLogs(logs.map(_.toFormattedString).toVector)
  }

  class TestContainer(lines: Source[ByteString, Any],
                      val id: ContainerId = ContainerId("test"),
                      val addr: ContainerAddress = ContainerAddress("test", 1234))(implicit val ec: ExecutionContext,
                                                                                   val logging: Logging)
      extends Container {
    override def suspend()(implicit transid: TransactionId): Future[Unit] = ???
    override def resume()(implicit transid: TransactionId): Future[Unit] = ???

    def logs(limit: ByteSize, waitForSentinel: Boolean)(implicit transid: TransactionId) = lines

    override implicit protected val as: ActorSystem = actorSystem
  }
}
