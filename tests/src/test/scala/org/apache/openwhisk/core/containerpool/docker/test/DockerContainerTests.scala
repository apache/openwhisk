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

package org.apache.openwhisk.core.containerpool.docker.test

import java.io.IOException
import java.time.Instant

import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import common.TimingHelpers

import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.Future
import org.junit.runner.RunWith
import org.scalamock.scalatest.MockFactory
import org.scalatest.BeforeAndAfterEach
import org.scalatest.FlatSpec
import org.apache.openwhisk.core.containerpool.logging.{DockerToActivationLogStore, LogLine}
import org.scalatest.junit.JUnitRunner
import org.scalatest.Matchers
import common.{StreamLogging, WskActorSystem}
import spray.json._
import org.apache.openwhisk.common.LoggingMarkers._
import org.apache.openwhisk.common.LogMarker
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.containerpool._
import org.apache.openwhisk.core.containerpool.docker._
import org.apache.openwhisk.core.entity.ActivationResponse
import org.apache.openwhisk.core.entity.ActivationResponse.ContainerResponse
import org.apache.openwhisk.core.entity.ActivationResponse.Timeout
import org.apache.openwhisk.core.entity.size._
import org.apache.openwhisk.http.Messages
import DockerContainerTests._
import org.apache.openwhisk.core.entity.ExecManifest.ImageName

object DockerContainerTests {

  /** Awaits the given future, throws the exception enclosed in Failure. */
  def await[A](f: Future[A], timeout: FiniteDuration = 500.milliseconds) = Await.result[A](f, timeout)

  /** Creates an interval starting at EPOCH with the given duration. */
  def intervalOf(duration: FiniteDuration) = Interval(Instant.EPOCH, Instant.ofEpochMilli(duration.toMillis))

  def toRawLog(log: Seq[LogLine], appendSentinel: Boolean = true): ByteString = {
    val appendedLog = if (appendSentinel) {
      val lastTime = log.lastOption.map { case LogLine(time, _, _) => time }.getOrElse(Instant.EPOCH.toString)
      log :+
        LogLine(lastTime, "stderr", s"${Container.ACTIVATION_LOG_SENTINEL}\n") :+
        LogLine(lastTime, "stdout", s"${Container.ACTIVATION_LOG_SENTINEL}\n")
    } else {
      log
    }
    ByteString(appendedLog.map(_.toJson.compactPrint).mkString("", "\n", "\n"))
  }
}

/**
 * Unit tests for ContainerPool schedule
 */
@RunWith(classOf[JUnitRunner])
class DockerContainerTests
    extends FlatSpec
    with Matchers
    with MockFactory
    with StreamLogging
    with BeforeAndAfterEach
    with WskActorSystem
    with TimingHelpers {

  override def beforeEach() = {
    stream.reset()
  }

  /** Reads logs into memory and awaits them */
  def awaitLogs(source: Source[ByteString, Any], timeout: FiniteDuration = 500.milliseconds): Vector[String] =
    Await.result(source.via(DockerToActivationLogStore.toFormattedString).runWith(Sink.seq[String]), timeout).toVector

  val containerId = ContainerId("id")

  /**
   * Constructs a testcontainer with overridden IO methods. Results of the override can be provided
   * as parameters.
   */
  def dockerContainer(id: ContainerId = containerId, addr: ContainerAddress = ContainerAddress("ip"))(
    ccRes: Future[RunResult] =
      Future.successful(RunResult(intervalOf(1.millisecond), Right(ContainerResponse(true, "", None)))),
    awaitLogs: FiniteDuration = 2.seconds)(implicit docker: DockerApiWithFileAccess, runc: RuncApi): DockerContainer = {

    new DockerContainer(id, addr, true) {
      override protected def callContainer(
        path: String,
        body: JsObject,
        timeout: FiniteDuration,
        concurrent: Int,
        retry: Boolean = false,
        reschedule: Boolean = false)(implicit transid: TransactionId): Future[RunResult] = {
        ccRes
      }
      override protected val logCollectingIdleTimeout = awaitLogs
      override protected val filePollInterval = 1.millisecond
    }
  }

  behavior of "DockerContainer"

  implicit val transid = TransactionId.testing
  val parameters = Map(
    "--cap-drop" -> Set("NET_RAW", "NET_ADMIN"),
    "--ulimit" -> Set("nofile=1024:1024"),
    "--pids-limit" -> Set("1024"))

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
      image = Right(ImageName(image)),
      memory = memory,
      cpuShares = cpuShares,
      environment = environment,
      network = network,
      name = Some(name),
      dockerRunParameters = parameters)

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

    val container =
      DockerContainer.create(transid = transid, image = Left(ImageName("image")), dockerRunParameters = parameters)
    await(container)

    docker.pulls should have size 1
    docker.runs should have size 1
    docker.inspects should have size 1
    docker.rms should have size 0
  }

  it should "remove the container if inspect fails" in {
    implicit val docker = new TestDockerClient {
      override def inspectIPAddress(id: ContainerId,
                                    network: String)(implicit transid: TransactionId): Future[ContainerAddress] = {
        inspects += ((id, network))
        Future.failed(new RuntimeException())
      }
    }
    implicit val runc = stub[RuncApi]

    val container =
      DockerContainer.create(transid = transid, image = Right(ImageName("image")), dockerRunParameters = parameters)
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
        Future.failed(ProcessUnsuccessfulException(ExitStatus(1), "", ""))
      }
    }
    implicit val runc = stub[RuncApi]

    val container =
      DockerContainer.create(transid = transid, image = Left(ImageName("image")), dockerRunParameters = parameters)
    a[WhiskContainerStartupError] should be thrownBy await(container)

    docker.pulls should have size 1
    docker.runs should have size 1
    docker.inspects should have size 0
    docker.rms should have size 0
  }

  it should "remove the container if run fails with a broken container" in {
    implicit val docker = new TestDockerClient {
      override def run(image: String,
                       args: Seq[String] = Seq.empty[String])(implicit transid: TransactionId): Future[ContainerId] = {
        runs += ((image, args))
        Future.failed(BrokenDockerContainer(containerId, "Broken container"))
      }
    }
    implicit val runc = stub[RuncApi]

    val container =
      DockerContainer.create(transid = transid, image = Right(ImageName("image")), dockerRunParameters = parameters)
    a[WhiskContainerStartupError] should be thrownBy await(container)

    docker.pulls should have size 0
    docker.runs should have size 1
    docker.inspects should have size 0
    docker.rms should have size 1
  }

  it should "provide a proper error if inspect fails for blackbox containers" in {
    implicit val docker = new TestDockerClient {
      override def inspectIPAddress(id: ContainerId,
                                    network: String)(implicit transid: TransactionId): Future[ContainerAddress] = {
        inspects += ((id, network))
        Future.failed(new RuntimeException())
      }
    }
    implicit val runc = stub[RuncApi]

    val container =
      DockerContainer.create(transid = transid, image = Left(ImageName("image")), dockerRunParameters = parameters)
    a[WhiskContainerStartupError] should be thrownBy await(container)

    docker.pulls should have size 1
    docker.runs should have size 1
    docker.inspects should have size 1
    docker.rms should have size 1
  }

  it should "return a specific error if pulling a user provided image failed (given the image does not define a tag)" in {
    implicit val docker = new TestDockerClient {
      override def pull(image: String)(implicit transid: TransactionId): Future[Unit] = {
        pulls += image
        Future.failed(new RuntimeException())
      }
    }
    implicit val runc = stub[RuncApi]

    val imageName = "image"
    val container =
      DockerContainer.create(transid = transid, image = Left(ImageName(imageName)), dockerRunParameters = parameters)
    val exception = the[BlackboxStartupError] thrownBy await(container)
    exception.msg shouldBe Messages.imagePullError(imageName)

    docker.pulls should have size 1
    docker.runs should have size 0 // run is **not** called as a backup measure because no tag is defined
    docker.inspects should have size 0
    docker.rms should have size 0
  }

  it should "recover a failed image pull if the subsequent docker run succeeds" in {
    implicit val docker = new TestDockerClient {
      override def pull(image: String)(implicit transid: TransactionId): Future[Unit] = {
        pulls += image
        Future.failed(new RuntimeException())
      }
    }
    implicit val runc = stub[RuncApi]

    val container =
      DockerContainer.create(
        transid = transid,
        image = Left(ImageName("image", tag = Some("prod"))),
        dockerRunParameters = parameters)

    noException should be thrownBy await(container)

    docker.pulls should have size 1
    docker.runs should have size 1 // run is called as a backup measure in case the image is locally available
    docker.inspects should have size 1
    docker.rms should have size 0
  }

  it should "throw a pull exception if a recovering docker run fails as well" in {
    implicit val docker = new TestDockerClient {
      override def pull(image: String)(implicit transid: TransactionId): Future[Unit] = {
        pulls += image
        Future.failed(new RuntimeException())
      }
      override def run(image: String, args: Seq[String])(implicit transid: TransactionId): Future[ContainerId] = {
        runs += ((image, args))
        Future.failed(new RuntimeException())
      }
    }
    implicit val runc = stub[RuncApi]

    val imageName = ImageName("image", tag = Some("prod"))
    val container =
      DockerContainer.create(transid = transid, image = Left(imageName), dockerRunParameters = parameters)

    val exception = the[BlackboxStartupError] thrownBy await(container)
    exception.msg shouldBe Messages.imagePullError(imageName.resolveImageName())

    docker.pulls should have size 1
    docker.runs should have size 1 // run is called as a backup measure in case the image is locally available
    docker.inspects should have size 0 // inspect is never called because the run failed as well
    docker.rms should have size 0
  }

  /*
   * DOCKER COMMANDS
   */
  it should "pause and resume container via runc" in {
    implicit val docker = new TestDockerClient
    implicit val runc = new TestRuncClient

    val id = ContainerId("id")
    val container = new DockerContainer(id, ContainerAddress("ip"), true)

    val suspend = container.suspend()
    val resume = container.resume()

    await(suspend)
    await(resume)

    docker.unpauses should have size 0
    docker.pauses should have size 0

    runc.pauses should have size 1
    runc.resumes should have size 1
  }

  it should "pause and unpause container via docker" in {
    implicit val docker = new TestDockerClient
    implicit val runc = new TestRuncClient

    val id = ContainerId("id")
    val container = new DockerContainer(id, ContainerAddress("ip"), false)

    val suspend = container.suspend()
    val resume = container.resume()

    await(suspend)
    await(resume)

    docker.unpauses should have size 1
    docker.pauses should have size 1

    runc.pauses should have size 0
    runc.resumes should have size 0
  }

  it should "destroy a container via Docker" in {
    implicit val docker = stub[DockerApiWithFileAccess]
    implicit val runc = stub[RuncApi]

    val id = ContainerId("id")
    val container = new DockerContainer(id, ContainerAddress("ip"), true)

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

    val initInterval = container.initialize(JsObject.empty, initTimeout, 1)
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
      Future.successful(RunResult(interval, Left(Timeout(new Throwable()))))
    }

    val init = container.initialize(JsObject.empty, initTimeout, 1)

    val error = the[InitializationError] thrownBy await(init, initTimeout)
    error.interval shouldBe interval
    error.response.statusCode shouldBe ActivationResponse.DeveloperError

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
    val result = JsObject.empty
    val container = dockerContainer() {
      Future.successful(RunResult(interval, Right(ContainerResponse(true, result.compactPrint, None))))
    }

    val runResult = container.run(JsObject.empty, JsObject.empty, 1.second, 1)
    await(runResult) shouldBe (interval, ActivationResponse.success(Some(result), Some(2)))

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
      Future.successful(RunResult(interval, Left(Timeout(new Throwable()))))
    }

    val runResult = container.run(JsObject.empty, JsObject.empty, runTimeout, 1)
    await(runResult) shouldBe (interval, ActivationResponse.developerError(
      Messages.timedoutActivation(runTimeout, false)))

    // assert the finish log is there
    val end = LogMarker.parse(logLines.last)
    end.token shouldBe INVOKER_ACTIVATION_RUN.asFinish
  }

  /*
   * LOGS
   */
  it should "read a simple log with sentinel" in {
    val expectedLogEntry = LogLine(Instant.EPOCH.toString, "stdout", "This is a log entry.\n")
    val rawLog = toRawLog(Seq(expectedLogEntry), appendSentinel = true)

    implicit val docker = new TestDockerClient {
      override def rawContainerLogs(containerId: ContainerId,
                                    fromPos: Long,
                                    pollInterval: Option[FiniteDuration]): Source[ByteString, Any] = {
        rawContainerLogsInvocations += ((containerId, fromPos, pollInterval))
        Source.single(rawLog)
      }
    }
    implicit val runc = stub[RuncApi]

    val container = dockerContainer(id = containerId)()
    // Read with tight limit to verify that no truncation occurs
    val processedLogs = awaitLogs(container.logs(limit = rawLog.length.bytes, waitForSentinel = true))

    docker.rawContainerLogsInvocations should have size 1
    val (id, fromPos, pollInterval) = docker.rawContainerLogsInvocations(0)
    id shouldBe containerId
    fromPos shouldBe 0
    pollInterval shouldBe 'defined

    processedLogs should have size 1
    processedLogs shouldBe Vector(expectedLogEntry.toFormattedString)
  }

  it should "read a simple log without sentinel" in {
    val expectedLogEntry = LogLine(Instant.EPOCH.toString, "stdout", "This is a log entry.\n")
    val rawLog = toRawLog(Seq(expectedLogEntry), appendSentinel = false)

    implicit val docker = new TestDockerClient {
      override def rawContainerLogs(containerId: ContainerId,
                                    fromPos: Long,
                                    pollInterval: Option[FiniteDuration]): Source[ByteString, Any] = {
        rawContainerLogsInvocations += ((containerId, fromPos, pollInterval))
        Source.single(rawLog)
      }
    }
    implicit val runc = stub[RuncApi]

    val container = dockerContainer(id = containerId)()
    // Read without tight limit so that the full read result is processed
    val processedLogs = awaitLogs(container.logs(limit = 1.MB, waitForSentinel = false))

    docker.rawContainerLogsInvocations should have size 1
    val (id, fromPos, pollInterval) = docker.rawContainerLogsInvocations(0)
    id shouldBe containerId
    fromPos shouldBe 0
    pollInterval should not be 'defined

    processedLogs should have size 1
    processedLogs shouldBe Vector(expectedLogEntry.toFormattedString)
  }

  it should "fail log reading if error occurs during file reading" in {
    implicit val docker = new TestDockerClient {
      override def rawContainerLogs(containerId: ContainerId,
                                    fromPos: Long,
                                    pollInterval: Option[FiniteDuration]): Source[ByteString, Any] = {
        rawContainerLogsInvocations += ((containerId, fromPos, pollInterval))
        Source.failed(new IOException)
      }
    }
    implicit val runc = stub[RuncApi]

    val container = dockerContainer()()
    an[IOException] should be thrownBy awaitLogs(container.logs(limit = 1.MB, waitForSentinel = true))

    docker.rawContainerLogsInvocations should have size 1
    val (id, fromPos, _) = docker.rawContainerLogsInvocations(0)
    id shouldBe containerId
    fromPos shouldBe 0
  }

  it should "read two consecutive logs with sentinel" in {
    val firstLogEntry = LogLine(Instant.EPOCH.toString, "stdout", "This is the first log.\n")
    val secondLogEntry = LogLine(Instant.EPOCH.plusSeconds(1L).toString, "stderr", "This is the second log.\n")
    val firstRawLog = toRawLog(Seq(firstLogEntry), appendSentinel = true)
    val secondRawLog = toRawLog(Seq(secondLogEntry), appendSentinel = true)
    val returnValues = mutable.Queue(firstRawLog, secondRawLog)

    implicit val docker = new TestDockerClient {
      override def rawContainerLogs(containerId: ContainerId,
                                    fromPos: Long,
                                    pollInterval: Option[FiniteDuration]): Source[ByteString, Any] = {
        rawContainerLogsInvocations += ((containerId, fromPos, pollInterval))
        Source.single(returnValues.dequeue())
      }
    }
    implicit val runc = stub[RuncApi]

    val container = dockerContainer()()
    // Read without tight limit so that the full read result is processed
    val processedFirstLog = awaitLogs(container.logs(limit = 1.MB, waitForSentinel = true))
    val processedSecondLog = awaitLogs(container.logs(limit = 1.MB, waitForSentinel = true))

    docker.rawContainerLogsInvocations should have size 2
    val (_, fromPos1, _) = docker.rawContainerLogsInvocations(0)
    fromPos1 shouldBe 0
    val (_, fromPos2, _) = docker.rawContainerLogsInvocations(1)
    fromPos2 shouldBe firstRawLog.length // second read should start behind the first line

    processedFirstLog should have size 1
    processedFirstLog shouldBe Vector(firstLogEntry.toFormattedString)
    processedSecondLog should have size 1
    processedSecondLog shouldBe Vector(secondLogEntry.toFormattedString)
  }

  it should "eventually terminate even if no sentinels can be found" in {

    val expectedLog = Seq(LogLine(Instant.EPOCH.toString, "stdout", s"This is log entry.\n"))
    val rawLog = toRawLog(expectedLog, appendSentinel = false)

    implicit val docker = new TestDockerClient {
      override def rawContainerLogs(containerId: ContainerId,
                                    fromPos: Long,
                                    pollInterval: Option[FiniteDuration]): Source[ByteString, Any] = {
        rawContainerLogsInvocations += ((containerId, fromPos, pollInterval))
        // "Fakes" an infinite source with only 1 entry
        Source.tick(0.milliseconds, 10.seconds, rawLog)
      }
    }
    implicit val runc = stub[RuncApi]

    val waitForLogs = 100.milliseconds
    val container = dockerContainer()(awaitLogs = waitForLogs)
    // Read without tight limit so that the full read result is processed

    val (interval, processedLog) = durationOf(awaitLogs(container.logs(limit = 1.MB, waitForSentinel = true)))

    interval.toMillis should (be >= waitForLogs.toMillis and be < (waitForLogs * 2).toMillis)

    docker.rawContainerLogsInvocations should have size 1

    processedLog should have size expectedLog.length + 1 //error log should be appended
    processedLog.head shouldBe expectedLog.head.toFormattedString
    processedLog(1) should include(Messages.logFailure)
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

    val firstRawLog = toRawLog(Seq(firstLogFirstEntry, firstLogSecondEntry), appendSentinel = false)
    val secondRawLog = toRawLog(Seq(secondLogFirstEntry, secondLogSecondEntry), appendSentinel = false)
    val thirdRawLog = toRawLog(Seq(thirdLogFirstEntry), appendSentinel = true)

    val returnValues = mutable.Queue(firstRawLog, secondRawLog, thirdRawLog)

    implicit val docker = new TestDockerClient {
      override def rawContainerLogs(containerId: ContainerId,
                                    fromPos: Long,
                                    pollInterval: Option[FiniteDuration]): Source[ByteString, Any] = {
        rawContainerLogsInvocations += ((containerId, fromPos, pollInterval))
        Source.single(returnValues.dequeue())
      }
    }
    implicit val runc = stub[RuncApi]

    val container = dockerContainer()()
    val processedFirstLog = awaitLogs(container.logs(limit = (firstRawLog.length - 1).bytes, waitForSentinel = true))
    val processedSecondLog =
      awaitLogs(container.logs(limit = (secondRawLog.length - 1).bytes, waitForSentinel = false))
    val processedThirdLog = awaitLogs(container.logs(limit = 1.MB, waitForSentinel = true))

    docker.rawContainerLogsInvocations should have size 3
    val (_, fromPos1, _) = docker.rawContainerLogsInvocations(0)
    fromPos1 shouldBe 0
    val (_, fromPos2, _) = docker.rawContainerLogsInvocations(1)
    fromPos2 shouldBe firstRawLog.length // second read should start behind full content of first read
    val (_, fromPos3, _) = docker.rawContainerLogsInvocations(2)
    fromPos3 shouldBe firstRawLog.length + secondRawLog.length // third read should start behind full content of first and second read

    processedFirstLog should have size 2
    processedFirstLog(0) shouldBe firstLogFirstEntry.toFormattedString
    // Allowing just 1 byte less than the JSON structure causes the entire line to drop
    processedFirstLog(1) should include(Messages.truncateLogs((firstRawLog.length - 1).bytes))

    processedSecondLog should have size 2
    processedSecondLog(0) shouldBe secondLogFirstEntry.toFormattedString
    processedSecondLog(1) should include(Messages.truncateLogs((secondRawLog.length - 1).bytes))

    processedThirdLog should have size 1
    processedThirdLog(0) shouldBe thirdLogFirstEntry.toFormattedString
  }

  it should "not fail if the last log-line is incomplete" in {
    val expectedLogEntry = LogLine(Instant.EPOCH.toString, "stdout", "This is a log entry.\n")
    // "destroy" the second log entry by dropping some bytes
    val rawLog = toRawLog(Seq(expectedLogEntry, expectedLogEntry), appendSentinel = false).dropRight(10)

    implicit val docker = new TestDockerClient {
      override def rawContainerLogs(containerId: ContainerId,
                                    fromPos: Long,
                                    pollInterval: Option[FiniteDuration]): Source[ByteString, Any] = {
        rawContainerLogsInvocations += ((containerId, fromPos, pollInterval))
        Source.single(rawLog)
      }
    }
    implicit val runc = stub[RuncApi]

    val container = dockerContainer(id = containerId)()
    // Read with tight limit to verify that no truncation occurs
    val processedLogs = awaitLogs(container.logs(limit = rawLog.length.bytes, waitForSentinel = false))

    docker.rawContainerLogsInvocations should have size 1
    val (id, fromPos, _) = docker.rawContainerLogsInvocations(0)
    id shouldBe containerId
    fromPos shouldBe 0

    processedLogs should have size 2
    processedLogs(0) shouldBe expectedLogEntry.toFormattedString
    processedLogs(1) should include(Messages.logFailure)
  }

  it should "include an incomplete warning if sentinels have not been found only if we wait for sentinels" in {
    val expectedLogEntry = LogLine(Instant.EPOCH.toString, "stdout", "This is a log entry.\n")
    val rawLog = toRawLog(Seq(expectedLogEntry, expectedLogEntry), appendSentinel = false)

    implicit val docker = new TestDockerClient {
      override def rawContainerLogs(containerId: ContainerId,
                                    fromPos: Long,
                                    pollInterval: Option[FiniteDuration]): Source[ByteString, Any] = {
        rawContainerLogsInvocations += ((containerId, fromPos, pollInterval))
        Source.single(rawLog)
      }
    }
    implicit val runc = stub[RuncApi]

    val container = dockerContainer(id = containerId)()
    // Read with tight limit to verify that no truncation occurs
    val processedLogs = awaitLogs(container.logs(limit = rawLog.length.bytes, waitForSentinel = true))

    docker.rawContainerLogsInvocations should have size 1
    val (id, fromPos, _) = docker.rawContainerLogsInvocations(0)
    id shouldBe containerId
    fromPos shouldBe 0

    processedLogs should have size 3
    processedLogs(0) shouldBe expectedLogEntry.toFormattedString
    processedLogs(1) shouldBe expectedLogEntry.toFormattedString
    processedLogs(2) should include(Messages.logFailure)

    val processedLogsFalse = awaitLogs(container.logs(limit = rawLog.length.bytes, waitForSentinel = false))
    processedLogsFalse should have size 2
    processedLogsFalse(0) shouldBe expectedLogEntry.toFormattedString
    processedLogsFalse(1) shouldBe expectedLogEntry.toFormattedString
  }

  it should "strip sentinel lines if it waits or doesn't wait for them" in {
    val expectedLogEntry = LogLine(Instant.EPOCH.toString, "stdout", "This is a log entry.\n")
    val rawLog = toRawLog(Seq(expectedLogEntry), appendSentinel = true)

    implicit val docker = new TestDockerClient {
      override def rawContainerLogs(containerId: ContainerId,
                                    fromPos: Long,
                                    pollInterval: Option[FiniteDuration]): Source[ByteString, Any] = {
        rawContainerLogsInvocations += ((containerId, fromPos, pollInterval))
        Source.single(rawLog)
      }
    }
    implicit val runc = stub[RuncApi]

    val container = dockerContainer(id = containerId)()
    val processedLogs = awaitLogs(container.logs(limit = 1.MB, waitForSentinel = true))
    processedLogs should have size 1
    processedLogs(0) shouldBe expectedLogEntry.toFormattedString

    val processedLogsFalse = awaitLogs(container.logs(limit = 1.MB, waitForSentinel = false))
    processedLogsFalse should have size 1
    processedLogsFalse(0) shouldBe expectedLogEntry.toFormattedString
  }

  class TestRuncClient extends RuncApi {
    var resumes = mutable.Buffer.empty[ContainerId]
    var pauses = mutable.Buffer.empty[ContainerId]

    override def resume(id: ContainerId)(implicit transid: TransactionId): Future[Unit] = {
      resumes += id
      Future.successful(())
    }

    override def pause(id: ContainerId)(implicit transid: TransactionId): Future[Unit] = {
      pauses += id
      Future.successful(())
    }
  }

  class TestDockerClient extends DockerApiWithFileAccess {
    var runs = mutable.Buffer.empty[(String, Seq[String])]
    var inspects = mutable.Buffer.empty[(ContainerId, String)]
    var pauses = mutable.Buffer.empty[ContainerId]
    var unpauses = mutable.Buffer.empty[ContainerId]
    var rms = mutable.Buffer.empty[ContainerId]
    var pulls = mutable.Buffer.empty[String]
    var rawContainerLogsInvocations = mutable.Buffer.empty[(ContainerId, Long, Option[FiniteDuration])]

    def clientVersion: String = "mock-test-client"

    def run(image: String, args: Seq[String] = Seq.empty[String])(
      implicit transid: TransactionId): Future[ContainerId] = {
      runs += ((image, args))
      Future.successful(ContainerId("testId"))
    }

    def inspectIPAddress(id: ContainerId, network: String)(
      implicit transid: TransactionId): Future[ContainerAddress] = {
      inspects += ((id, network))
      Future.successful(ContainerAddress("testIp"))
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

    def ps(filters: Seq[(String, String)] = Seq.empty, all: Boolean = false)(
      implicit transid: TransactionId): Future[Seq[ContainerId]] = ???

    def pull(image: String)(implicit transid: TransactionId): Future[Unit] = {
      pulls += image
      Future.successful(())
    }

    override def isOomKilled(id: ContainerId)(implicit transid: TransactionId): Future[Boolean] = ???

    override def rawContainerLogs(containerId: ContainerId,
                                  fromPos: Long,
                                  pollInterval: Option[FiniteDuration]): Source[ByteString, Any] = {
      rawContainerLogsInvocations += ((containerId, fromPos, pollInterval))
      Source.single(ByteString.empty)
    }
  }
}
