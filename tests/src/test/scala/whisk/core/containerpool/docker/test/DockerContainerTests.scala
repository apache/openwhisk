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

package whisk.core.containerpool.docker.test

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.Instant

import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

import org.junit.runner.RunWith
import org.scalamock.scalatest.MockFactory
import org.scalatest.BeforeAndAfterEach
import org.scalatest.FlatSpec
import org.scalatest.Inspectors._
import org.scalatest.junit.JUnitRunner
import org.scalatest.Matchers

import common.StreamLogging
import spray.json._
import whisk.common.LoggingMarkers._
import whisk.common.LogMarker
import whisk.common.TransactionId
import whisk.core.containerpool._
import whisk.core.containerpool.docker._
import whisk.core.entity.ActivationResponse
import whisk.core.entity.ActivationResponse.ContainerResponse
import whisk.core.entity.ActivationResponse.Timeout
import whisk.core.entity.size._
import whisk.http.Messages

/**
 * Unit tests for ContainerPool schedule
 */
@RunWith(classOf[JUnitRunner])
class DockerContainerTests extends FlatSpec with Matchers with MockFactory with StreamLogging with BeforeAndAfterEach {

  override def beforeEach() = {
    stream.reset()
  }

  /** Awaits the given future, throws the exception enclosed in Failure. */
  def await[A](f: Future[A], timeout: FiniteDuration = 500.milliseconds) = Await.result[A](f, timeout)

  val containerId = ContainerId("id")

  /**
   * Constructs a testcontainer with overridden IO methods. Results of the override can be provided
   * as parameters.
   */
  def dockerContainer(id: ContainerId = containerId, ip: ContainerIp = ContainerIp("ip"))(
    ccRes: Future[RunResult] =
      Future.successful(RunResult(intervalOf(1.millisecond), Right(ContainerResponse(true, "", None)))),
    retryCount: Int = 0)(implicit docker: DockerApiWithFileAccess, runc: RuncApi): DockerContainer = {

    new DockerContainer(id, ip) {
      override protected def callContainer(path: String,
                                           body: JsObject,
                                           timeout: FiniteDuration,
                                           retry: Boolean = false): Future[RunResult] = {
        ccRes
      }
      override protected val logsRetryCount = retryCount
      override protected val logsRetryWait = 0.milliseconds
    }
  }

  /** Creates an interval starting at EPOCH with the given duration. */
  def intervalOf(duration: FiniteDuration) = Interval(Instant.EPOCH, Instant.ofEpochMilli(duration.toMillis))

  behavior of "DockerContainer"

  implicit val transid = TransactionId.testing

  /*
   * CONTAINER CREATION
   */
  it should "create a new instance" in {
    implicit val docker = new TestDockerClient
    implicit val runc = stub[RuncApi]

    val image = "image"
    val memory = 128.MB
    val cpuShares = 1
    val environment = Map("test" -> "hi")
    val network = "testwork"
    val name = "myContainer"

    val container = DockerContainer.create(
      transid = transid,
      image = image,
      memory = memory,
      cpuShares = cpuShares,
      environment = environment,
      network = network,
      name = Some(name))

    await(container)

    docker.pulls should have size 0
    docker.runs should have size 1
    docker.inspects should have size 1
    docker.rms should have size 0

    val (testImage, args) = docker.runs.head
    testImage shouldBe "image"

    // Assert fixed values are passed as well
    args should contain allOf ("--cap-drop", "NET_RAW", "NET_ADMIN")
    args should contain inOrder ("--ulimit", "nofile=1024:1024")
    args should contain inOrder ("--pids-limit", "1024") // OW PR 2119

    // Assert proper parameter translation
    args should contain inOrder ("--memory", s"${memory.toMB}m")
    args should contain inOrder ("--memory-swap", s"${memory.toMB}m")
    args should contain inOrder ("--cpu-shares", cpuShares.toString)
    args should contain inOrder ("--network", network)
    args should contain inOrder ("--name", name)

    // Assert proper environment passing
    args should contain allOf ("-e", "test=hi")
  }

  it should "pull a user provided image before creating the container" in {
    implicit val docker = new TestDockerClient
    implicit val runc = stub[RuncApi]

    val container = DockerContainer.create(transid = transid, image = "image", userProvidedImage = true)
    await(container)

    docker.pulls should have size 1
    docker.runs should have size 1
    docker.inspects should have size 1
    docker.rms should have size 0
  }

  it should "remove the container if inspect fails" in {
    implicit val docker = new TestDockerClient {
      override def inspectIPAddress(id: ContainerId,
                                    network: String)(implicit transid: TransactionId): Future[ContainerIp] = {
        inspects += ((id, network))
        Future.failed(new RuntimeException())
      }
    }
    implicit val runc = stub[RuncApi]

    val container = DockerContainer.create(transid = transid, image = "image")
    a[WhiskContainerStartupError] should be thrownBy await(container)

    docker.pulls should have size 0
    docker.runs should have size 1
    docker.inspects should have size 1
    docker.rms should have size 1
  }

  it should "provide a proper error if run fails for blackbox containers" in {
    implicit val docker = new TestDockerClient {
      override def run(image: String,
                       args: Seq[String] = Seq.empty[String])(implicit transid: TransactionId): Future[ContainerId] = {
        runs += ((image, args))
        Future.failed(new RuntimeException())
      }
    }
    implicit val runc = stub[RuncApi]

    val container = DockerContainer.create(transid = transid, image = "image", userProvidedImage = true)
    a[WhiskContainerStartupError] should be thrownBy await(container)

    docker.pulls should have size 1
    docker.runs should have size 1
    docker.inspects should have size 0
    docker.rms should have size 0
  }

  it should "provide a proper error if inspect fails for blackbox containers" in {
    implicit val docker = new TestDockerClient {
      override def inspectIPAddress(id: ContainerId,
                                    network: String)(implicit transid: TransactionId): Future[ContainerIp] = {
        inspects += ((id, network))
        Future.failed(new RuntimeException())
      }
    }
    implicit val runc = stub[RuncApi]

    val container = DockerContainer.create(transid = transid, image = "image", userProvidedImage = true)
    a[WhiskContainerStartupError] should be thrownBy await(container)

    docker.pulls should have size 1
    docker.runs should have size 1
    docker.inspects should have size 1
    docker.rms should have size 1
  }

  it should "return a specific error if pulling a user provided image failed" in {
    implicit val docker = new TestDockerClient {
      override def pull(image: String)(implicit transid: TransactionId): Future[Unit] = {
        pulls += image
        Future.failed(new RuntimeException())
      }
    }
    implicit val runc = stub[RuncApi]

    val container = DockerContainer.create(transid = transid, image = "image", userProvidedImage = true)
    a[BlackboxStartupError] should be thrownBy await(container)

    docker.pulls should have size 1
    docker.runs should have size 0
    docker.inspects should have size 0
    docker.rms should have size 0
  }

  /*
   * DOCKER COMMANDS
   */
  it should "halt and resume container via runc" in {
    implicit val docker = stub[DockerApiWithFileAccess]
    implicit val runc = stub[RuncApi]

    val id = ContainerId("id")
    val container = new DockerContainer(id, ContainerIp("ip"))

    container.suspend()
    container.resume()

    (runc.pause(_: ContainerId)(_: TransactionId)).verify(id, transid)
    (runc.resume(_: ContainerId)(_: TransactionId)).verify(id, transid)
  }

  it should "destroy a container via Docker" in {
    implicit val docker = stub[DockerApiWithFileAccess]
    implicit val runc = stub[RuncApi]

    val id = ContainerId("id")
    val container = new DockerContainer(id, ContainerIp("ip"))

    container.destroy()

    (docker.rm(_: ContainerId)(_: TransactionId)).verify(id, transid)
  }

  /*
   * INITIALIZE
   *
   * Only tests for quite simple cases. Disambiguation of errors is delegated to ActivationResponse
   * and so are the tests for those.
   */
  it should "initialize a container" in {
    implicit val docker = stub[DockerApiWithFileAccess]
    implicit val runc = stub[RuncApi]

    val initTimeout = 1.second
    val interval = intervalOf(1.millisecond)
    val container = dockerContainer() {
      Future.successful(RunResult(interval, Right(ContainerResponse(true, "", None))))
    }

    val initInterval = container.initialize(JsObject(), initTimeout)
    await(initInterval, initTimeout) shouldBe interval

    // assert the starting log is there
    val start = LogMarker.parse(logLines.head)
    start.token shouldBe INVOKER_ACTIVATION_INIT

    // assert the end log is there
    val end = LogMarker.parse(logLines.last)
    end.token shouldBe INVOKER_ACTIVATION_INIT.asFinish
    end.deltaToMarkerStart shouldBe Some(interval.duration.toMillis)
  }

  it should "properly deal with a timeout during initialization" in {
    implicit val docker = stub[DockerApiWithFileAccess]
    implicit val runc = stub[RuncApi]

    val initTimeout = 1.second
    val interval = intervalOf(initTimeout + 1.nanoseconds)

    val container = dockerContainer() {
      Future.successful(RunResult(interval, Left(Timeout())))
    }

    val init = container.initialize(JsObject(), initTimeout)

    val error = the[InitializationError] thrownBy await(init, initTimeout)
    error.interval shouldBe interval
    error.response.statusCode shouldBe ActivationResponse.ApplicationError

    // assert the finish log is there
    val end = LogMarker.parse(logLines.last)
    end.token shouldBe INVOKER_ACTIVATION_INIT.asFinish
  }

  /*
   * RUN
   *
   * Only tests for quite simple cases. Disambiguation of errors is delegated to ActivationResponse
   * and so are the tests for those.
   */
  it should "run a container" in {
    implicit val docker = stub[DockerApiWithFileAccess]
    implicit val runc = stub[RuncApi]

    val interval = intervalOf(1.millisecond)
    val result = JsObject()
    val container = dockerContainer() {
      Future.successful(RunResult(interval, Right(ContainerResponse(true, result.compactPrint, None))))
    }

    val runResult = container.run(JsObject(), JsObject(), 1.second)
    await(runResult) shouldBe (interval, ActivationResponse.success(Some(result)))

    // assert the starting log is there
    val start = LogMarker.parse(logLines.head)
    start.token shouldBe INVOKER_ACTIVATION_RUN

    // assert the end log is there
    val end = LogMarker.parse(logLines.last)
    end.token shouldBe INVOKER_ACTIVATION_RUN.asFinish
    end.deltaToMarkerStart shouldBe Some(interval.duration.toMillis)
  }

  it should "properly deal with a timeout during run" in {
    implicit val docker = stub[DockerApiWithFileAccess]
    implicit val runc = stub[RuncApi]

    val runTimeout = 1.second
    val interval = intervalOf(runTimeout + 1.nanoseconds)

    val container = dockerContainer() {
      Future.successful(RunResult(interval, Left(Timeout())))
    }

    val runResult = container.run(JsObject(), JsObject(), runTimeout)
    await(runResult) shouldBe (interval, ActivationResponse.applicationError(
      Messages.timedoutActivation(runTimeout, false)))

    // assert the finish log is there
    val end = LogMarker.parse(logLines.last)
    end.token shouldBe INVOKER_ACTIVATION_RUN.asFinish
  }

  /*
   * LOGS
   */
  def toByteBuffer(s: String): ByteBuffer = {
    val bb = ByteBuffer.wrap(s.getBytes(StandardCharsets.UTF_8))
    // Set position behind provided string to simulate read - this is what FileChannel.read() does
    // Otherwise position would be 0 indicating that buffer is empty
    bb.position(bb.capacity())
    bb
  }

  def toRawLog(log: Seq[LogLine], appendSentinel: Boolean = true): ByteBuffer = {
    val appendedLog = if (appendSentinel) {
      val lastTime = log.lastOption.map { case LogLine(time, _, _) => time }.getOrElse(Instant.EPOCH.toString)
      log :+
        LogLine(lastTime, "stderr", s"${DockerActionLogDriver.LOG_ACTIVATION_SENTINEL}\n") :+
        LogLine(lastTime, "stdout", s"${DockerActionLogDriver.LOG_ACTIVATION_SENTINEL}\n")
    } else {
      log
    }
    toByteBuffer(appendedLog.map(_.toJson.compactPrint).mkString("", "\n", "\n"))
  }

  it should "read a simple log with sentinel" in {
    val expectedLogEntry = LogLine(Instant.EPOCH.toString, "stdout", "This is a log entry.\n")
    val rawLog = toRawLog(Seq(expectedLogEntry), appendSentinel = true)
    val readResults = mutable.Queue(rawLog)

    implicit val docker = new TestDockerClient {
      override def rawContainerLogs(containerId: ContainerId, fromPos: Long): Future[ByteBuffer] = {
        rawContainerLogsInvocations += ((containerId, fromPos))
        Future.successful(readResults.dequeue())
      }
    }
    implicit val runc = stub[RuncApi]

    val container = dockerContainer(id = containerId)()
    // Read with tight limit to verify that no truncation occurs
    val processedLogs = await(container.logs(limit = expectedLogEntry.log.sizeInBytes, waitForSentinel = true))

    docker.rawContainerLogsInvocations should have size 1
    val (id, fromPos) = docker.rawContainerLogsInvocations(0)
    id shouldBe containerId
    fromPos shouldBe 0

    processedLogs should have size 1
    processedLogs shouldBe Vector(expectedLogEntry.toFormattedString)
  }

  it should "read a simple log without sentinel" in {
    val expectedLogEntry = LogLine(Instant.EPOCH.toString, "stdout", "This is a log entry.\n")
    val rawLog = toRawLog(Seq(expectedLogEntry), appendSentinel = false)
    val readResults = mutable.Queue(rawLog)

    implicit val docker = new TestDockerClient {
      override def rawContainerLogs(containerId: ContainerId, fromPos: Long): Future[ByteBuffer] = {
        rawContainerLogsInvocations += ((containerId, fromPos))
        Future.successful(readResults.dequeue())
      }
    }
    implicit val runc = stub[RuncApi]

    val container = dockerContainer(id = containerId)()
    // Read without tight limit so that the full read result is processed
    val processedLogs = await(container.logs(limit = 1.MB, waitForSentinel = false))

    docker.rawContainerLogsInvocations should have size 1
    val (id, fromPos) = docker.rawContainerLogsInvocations(0)
    id shouldBe containerId
    fromPos shouldBe 0

    processedLogs should have size 1
    processedLogs shouldBe Vector(expectedLogEntry.toFormattedString)
  }

  it should "fail log reading if error occurs during file reading" in {
    implicit val docker = new TestDockerClient {
      override def rawContainerLogs(containerId: ContainerId, fromPos: Long): Future[ByteBuffer] = {
        rawContainerLogsInvocations += ((containerId, fromPos))
        Future.failed(new IOException)
      }
    }
    implicit val runc = stub[RuncApi]

    val container = dockerContainer()()
    an[IOException] should be thrownBy await(container.logs(limit = 1.MB, waitForSentinel = true))

    docker.rawContainerLogsInvocations should have size 1
    val (id, fromPos) = docker.rawContainerLogsInvocations(0)
    id shouldBe containerId
    fromPos shouldBe 0

    exactly(1, logLines) should include(s"Failed to obtain logs of ${containerId.asString}")
  }

  it should "read two consecutive logs with sentinel" in {
    val firstLogEntry = LogLine(Instant.EPOCH.toString, "stdout", "This is the first log.\n")
    val secondLogEntry = LogLine(Instant.EPOCH.plusSeconds(1L).toString, "stderr", "This is the second log.\n")
    val firstRawLog = toRawLog(Seq(firstLogEntry), appendSentinel = true)
    val secondRawLog = toRawLog(Seq(secondLogEntry), appendSentinel = true)
    val returnValues = mutable.Queue(firstRawLog, secondRawLog)

    implicit val docker = new TestDockerClient {
      override def rawContainerLogs(containerId: ContainerId, fromPos: Long): Future[ByteBuffer] = {
        rawContainerLogsInvocations += ((containerId, fromPos))
        Future.successful(returnValues.dequeue())
      }
    }
    implicit val runc = stub[RuncApi]

    val container = dockerContainer()()
    // Read without tight limit so that the full read result is processed
    val processedFirstLog = await(container.logs(limit = 1.MB, waitForSentinel = true))
    val processedSecondLog = await(container.logs(limit = 1.MB, waitForSentinel = true))

    docker.rawContainerLogsInvocations should have size 2
    val (_, fromPos1) = docker.rawContainerLogsInvocations(0)
    fromPos1 shouldBe 0
    val (_, fromPos2) = docker.rawContainerLogsInvocations(1)
    fromPos2 shouldBe firstRawLog.capacity() // second read should start behind the first line

    processedFirstLog should have size 1
    processedFirstLog shouldBe Vector(firstLogEntry.toFormattedString)
    processedSecondLog should have size 1
    processedSecondLog shouldBe Vector(secondLogEntry.toFormattedString)
  }

  it should "retry log reading if sentinel cannot be found in the first place" in {
    val retries = 15
    val expectedLog = (1 to retries).map { i =>
      LogLine(Instant.EPOCH.plusMillis(i.toLong).toString, "stdout", s"This is log entry ${i}.\n")
    }.toVector
    val returnValues = mutable.Queue.empty[ByteBuffer]
    for (i <- 0 to retries) {
      // Sentinel only added for the last return value
      returnValues += toRawLog(expectedLog.take(i).toSeq, appendSentinel = (i == retries))
    }

    implicit val docker = new TestDockerClient {
      override def rawContainerLogs(containerId: ContainerId, fromPos: Long): Future[ByteBuffer] = {
        rawContainerLogsInvocations += ((containerId, fromPos))
        Future.successful(returnValues.dequeue())
      }
    }
    implicit val runc = stub[RuncApi]

    val container = dockerContainer()(retryCount = retries)
    // Read without tight limit so that the full read result is processed
    val processedLog = await(container.logs(limit = 1.MB, waitForSentinel = true))

    docker.rawContainerLogsInvocations should have size retries + 1
    forAll(docker.rawContainerLogsInvocations) {
      case (_, fromPos) => fromPos shouldBe 0
    }

    processedLog should have size expectedLog.length
    processedLog shouldBe expectedLog.map(_.toFormattedString)

    (retries to 1).foreach { i =>
      exactly(1, logLines) should include(s"log cursor advanced but missing sentinel, trying ${i} more times")
    }
  }

  it should "provide full log if log reading retries are exhausted and no sentinel can be found" in {
    val retries = 15
    val expectedLog = (1 to retries).map { i =>
      LogLine(Instant.EPOCH.plusMillis(i.toLong).toString, "stdout", s"This is log entry ${i}.\n")
    }.toVector
    val returnValues = mutable.Queue.empty[ByteBuffer]
    for (i <- 0 to retries) {
      returnValues += toRawLog(expectedLog.take(i).toSeq, appendSentinel = false)
    }

    implicit val docker = new TestDockerClient {
      override def rawContainerLogs(containerId: ContainerId, fromPos: Long): Future[ByteBuffer] = {
        rawContainerLogsInvocations += ((containerId, fromPos))
        Future.successful(returnValues.dequeue())
      }
    }
    implicit val runc = stub[RuncApi]

    val container = dockerContainer()(retryCount = retries)
    // Read without tight limit so that the full read result is processed
    val processedLog = await(container.logs(limit = 1.MB, waitForSentinel = true))

    docker.rawContainerLogsInvocations should have size retries + 1
    forAll(docker.rawContainerLogsInvocations) {
      case (_, fromPos) => fromPos shouldBe 0
    }

    processedLog should have size expectedLog.length
    processedLog shouldBe expectedLog.map(_.toFormattedString)

    (retries to 1).foreach { i =>
      exactly(1, logLines) should include(s"log cursor advanced but missing sentinel, trying ${i} more times")
    }
  }

  it should "truncate logs and advance reading position to end of current read" in {
    val firstLogFirstEntry = LogLine(Instant.EPOCH.toString, "stdout", "This is the first line in first log.\n")
    val firstLogSecondEntry =
      LogLine(Instant.EPOCH.plusMillis(1L).toString, "stderr", "This is the second line in first log.\n")

    val secondLogFirstEntry =
      LogLine(Instant.EPOCH.plusMillis(2L).toString, "stdout", "This is the first line in second log.\n")
    val secondLogSecondEntry =
      LogLine(Instant.EPOCH.plusMillis(3L).toString, "stdout", "This is the second line in second log.\n")
    val secondLogLimit = 4

    val thirdLogFirstEntry =
      LogLine(Instant.EPOCH.plusMillis(4L).toString, "stdout", "This is the first line in third log.\n")

    val firstRawLog = toRawLog(Seq(firstLogFirstEntry, firstLogSecondEntry), appendSentinel = true)
    val secondRawLog = toRawLog(Seq(secondLogFirstEntry, secondLogSecondEntry), appendSentinel = false)
    val thirdRawLog = toRawLog(Seq(thirdLogFirstEntry), appendSentinel = true)

    val returnValues = mutable.Queue(firstRawLog, secondRawLog, thirdRawLog)

    implicit val docker = new TestDockerClient {
      override def rawContainerLogs(containerId: ContainerId, fromPos: Long): Future[ByteBuffer] = {
        rawContainerLogsInvocations += ((containerId, fromPos))
        Future.successful(returnValues.dequeue())
      }
    }
    implicit val runc = stub[RuncApi]

    val container = dockerContainer()()
    val processedFirstLog = await(container.logs(limit = firstLogFirstEntry.log.sizeInBytes, waitForSentinel = true))
    val processedSecondLog =
      await(container.logs(limit = secondLogFirstEntry.log.take(secondLogLimit).sizeInBytes, waitForSentinel = false))
    val processedThirdLog = await(container.logs(limit = 1.MB, waitForSentinel = true))

    docker.rawContainerLogsInvocations should have size 3
    val (_, fromPos1) = docker.rawContainerLogsInvocations(0)
    fromPos1 shouldBe 0
    val (_, fromPos2) = docker.rawContainerLogsInvocations(1)
    fromPos2 shouldBe firstRawLog.capacity() // second read should start behind full content of first read
    val (_, fromPos3) = docker.rawContainerLogsInvocations(2)
    fromPos3 shouldBe firstRawLog.capacity() + secondRawLog
      .capacity() // third read should start behind full content of first and second read

    processedFirstLog should have size 2
    processedFirstLog(0) shouldBe firstLogFirstEntry.toFormattedString
    processedFirstLog(1) should startWith(Messages.truncateLogs(firstLogFirstEntry.log.sizeInBytes))

    processedSecondLog should have size 2
    processedSecondLog(0) shouldBe secondLogFirstEntry
      .copy(log = secondLogFirstEntry.log.take(secondLogLimit))
      .toFormattedString
    processedSecondLog(1) should startWith(
      Messages.truncateLogs(secondLogFirstEntry.log.take(secondLogLimit).sizeInBytes))

    processedThirdLog should have size 1
    processedThirdLog(0) shouldBe thirdLogFirstEntry.toFormattedString
  }

  class TestDockerClient extends DockerApiWithFileAccess {
    var runs = mutable.Buffer.empty[(String, Seq[String])]
    var inspects = mutable.Buffer.empty[(ContainerId, String)]
    var pauses = mutable.Buffer.empty[ContainerId]
    var unpauses = mutable.Buffer.empty[ContainerId]
    var rms = mutable.Buffer.empty[ContainerId]
    var pulls = mutable.Buffer.empty[String]
    var rawContainerLogsInvocations = mutable.Buffer.empty[(ContainerId, Long)]

    def run(image: String, args: Seq[String] = Seq.empty[String])(
      implicit transid: TransactionId): Future[ContainerId] = {
      runs += ((image, args))
      Future.successful(ContainerId("testId"))
    }

    def inspectIPAddress(id: ContainerId, network: String)(implicit transid: TransactionId): Future[ContainerIp] = {
      inspects += ((id, network))
      Future.successful(ContainerIp("testIp"))
    }

    def pause(id: ContainerId)(implicit transid: TransactionId): Future[Unit] = {
      pauses += id
      Future.successful(())
    }

    def unpause(id: ContainerId)(implicit transid: TransactionId): Future[Unit] = {
      unpauses += id
      Future.successful(())
    }

    def rm(id: ContainerId)(implicit transid: TransactionId): Future[Unit] = {
      rms += id
      Future.successful(())
    }

    def ps(filters: Seq[(String, String)] = Seq(), all: Boolean = false)(
      implicit transid: TransactionId): Future[Seq[ContainerId]] = ???

    def pull(image: String)(implicit transid: TransactionId): Future[Unit] = {
      pulls += image
      Future.successful(())
    }

    def rawContainerLogs(containerId: ContainerId, fromPos: Long): Future[ByteBuffer] = {
      rawContainerLogsInvocations += ((containerId, fromPos))
      Future.successful(ByteBuffer.wrap(Array[Byte]()))
    }
  }
}
