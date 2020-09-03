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

package org.apache.openwhisk.core.containerpool.kubernetes.test

import java.io.IOException
import java.time.{Instant, ZoneId}

import akka.NotUsed
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.util.ByteString
import common.TimingHelpers

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.Future
import org.junit.runner.RunWith
import org.scalamock.scalatest.MockFactory
import org.scalatest.BeforeAndAfterEach
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner
import org.scalatest.Matchers
import common.{StreamLogging, WskActorSystem}
import spray.json._
import org.apache.openwhisk.common.LoggingMarkers._
import org.apache.openwhisk.common.LogMarker
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.containerpool._
import org.apache.openwhisk.core.containerpool.kubernetes._
import org.apache.openwhisk.core.containerpool.docker._
import org.apache.openwhisk.core.entity.{ActivationResponse, ByteSize}
import org.apache.openwhisk.core.entity.size._
import org.apache.openwhisk.core.entity.ActivationResponse.ContainerResponse
import org.apache.openwhisk.core.entity.ActivationResponse.Timeout
import org.apache.openwhisk.core.entity.size._
import org.apache.openwhisk.http.Messages
import org.apache.openwhisk.core.containerpool.docker.test.DockerContainerTests._
import org.scalatest.concurrent.ScalaFutures

import scala.collection.immutable.Queue
import scala.collection.mutable

/**
 * Unit tests for ContainerPool schedule
 */
@RunWith(classOf[JUnitRunner])
class KubernetesContainerTests
    extends FlatSpec
    with Matchers
    with MockFactory
    with StreamLogging
    with BeforeAndAfterEach
    with WskActorSystem
    with TimingHelpers
    with ScalaFutures {

  import KubernetesClientTests.TestKubernetesClient
  import KubernetesContainerTests._

  override def beforeEach() = {
    stream.reset()
  }

  implicit val materializer: ActorMaterializer = ActorMaterializer()

  def instantDT(instant: Instant): Instant = Instant.from(instant.atZone(ZoneId.of("GMT+0")))

  val Epoch = Instant.EPOCH
  val EpochDateTime = instantDT(Epoch)

  /** Transforms chunked JsObjects into formatted strings */
  val toFormattedString: Flow[ByteString, String, NotUsed] =
    Flow[ByteString].map(_.utf8String.parseJson.convertTo[TypedLogLine].toString)

  val commandTimeout = 500.milliseconds
  def await[A](f: Future[A], timeout: FiniteDuration = commandTimeout) = Await.result(f, timeout)

  /** Reads logs into memory and awaits them */
  def awaitLogs(source: Source[ByteString, Any], timeout: FiniteDuration = commandTimeout): Vector[String] =
    Await.result(source.via(toFormattedString).runWith(Sink.seq[String]), timeout).toVector

  val containerId = ContainerId("id")

  /**
   * Constructs a testcontainer with overridden IO methods. Results of the override can be provided
   * as parameters.
   */
  def kubernetesContainer(id: ContainerId = containerId, addr: ContainerAddress = ContainerAddress("ip"))(
    ccRes: Future[RunResult] =
      Future.successful(RunResult(intervalOf(1.millisecond), Right(ContainerResponse(true, "", None)))),
    awaitLogs: FiniteDuration = 2.seconds)(implicit kubernetes: KubernetesApi): KubernetesContainer = {

    new KubernetesContainer(id, addr, addr.host, "docker://" + id.asString) {
      override protected def callContainer(
        path: String,
        body: JsObject,
        timeout: FiniteDuration,
        concurrent: Int,
        retry: Boolean = false,
        reschedule: Boolean = false)(implicit transid: TransactionId): Future[RunResult] = {
        ccRes
      }
      override protected val waitForLogs = awaitLogs
    }
  }

  behavior of "KubernetesContainer"

  implicit val transid = TransactionId.testing
  val parameters = Map(
    "--cap-drop" -> Set("NET_RAW", "NET_ADMIN"),
    "--ulimit" -> Set("nofile=1024:1024"),
    "--pids-limit" -> Set("1024"))

  /*
   * CONTAINER CREATION
   */
  it should "create a new instance" in {
    implicit val kubernetes = new TestKubernetesClient

    val image = "image"
    val userProvidedImage = false
    val environment = Map("test" -> "hi")
    val labels = Map("invoker" -> "0")
    val name = "my_Container(1)"
    val container = KubernetesContainer.create(
      transid = transid,
      image = image,
      userProvidedImage = userProvidedImage,
      environment = environment,
      labels = labels,
      name = name)

    await(container)

    kubernetes.runs should have size 1
    kubernetes.rms should have size 0

    val (testName, testImage, testEnv, testLabel) = kubernetes.runs.head
    testName shouldBe "my-container1"
    testImage shouldBe image
    testEnv shouldBe environment
    testLabel shouldBe labels
  }

  it should "pull a user provided image before creating the container" in {
    implicit val kubernetes = new TestKubernetesClient

    val container =
      KubernetesContainer.create(transid = transid, name = "name", image = "image", userProvidedImage = true)
    await(container)

    kubernetes.runs should have size 1
    kubernetes.rms should have size 0
  }

  it should "provide a proper error if run fails for blackbox containers" in {
    implicit val kubernetes = new TestKubernetesClient {
      override def run(
        name: String,
        image: String,
        memory: ByteSize = 256.MB,
        env: Map[String, String] = Map.empty,
        labels: Map[String, String] = Map.empty)(implicit transid: TransactionId): Future[KubernetesContainer] = {
        Future.failed(ProcessUnsuccessfulException(ExitStatus(1), "", ""))
      }
    }

    val container =
      KubernetesContainer.create(transid = transid, name = "name", image = "image", userProvidedImage = true)
    a[WhiskContainerStartupError] should be thrownBy await(container)

    kubernetes.runs should have size 0
    kubernetes.rms should have size 1
  }

  /*
   * KUBERNETES COMMANDS
   */
  it should "destroy a container via Kubernetes" in {
    implicit val kubernetes = stub[KubernetesApi]

    val id = ContainerId("id")
    val container = new KubernetesContainer(id, ContainerAddress("ip"), "127.0.0.1", "docker://foo")

    container.destroy()

    (kubernetes.rm(_: KubernetesContainer)(_: TransactionId)).verify(container, transid)
  }

  /*
   * INITIALIZE
   *
   * Only tests for quite simple cases. Disambiguation of errors is delegated to ActivationResponse
   * and so are the tests for those.
   */
  it should "initialize a container" in {
    implicit val kubernetes = stub[KubernetesApi]

    val initTimeout = 1.second
    val interval = intervalOf(1.millisecond)
    val container = kubernetesContainer() {
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
    implicit val kubernetes = stub[KubernetesApi]

    val initTimeout = 1.second
    val interval = intervalOf(initTimeout + 1.nanoseconds)

    val container = kubernetesContainer() {
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
    implicit val kubernetes = stub[KubernetesApi]

    val interval = intervalOf(1.millisecond)
    val result = JsObject.empty
    val container = kubernetesContainer() {
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
    implicit val kubernetes = stub[KubernetesApi]

    val runTimeout = 1.second
    val interval = intervalOf(runTimeout + 1.nanoseconds)

    val container = kubernetesContainer() {
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
    val expectedLogEntry = TypedLogLine(currentTsp, "stdout", "This is a log entry.")
    val logSrc = logSource(expectedLogEntry, appendSentinel = true)

    implicit val kubernetes = new TestKubernetesClient {
      override def logs(container: KubernetesContainer, sinceTime: Option[Instant], waitForSentinel: Boolean)(
        implicit transid: TransactionId): Source[TypedLogLine, Any] = {
        logCalls += ((container.id, sinceTime))
        logSrc
      }
    }

    val container = kubernetesContainer(id = containerId)()
    // Read with tight limit to verify that no truncation occurs TODO: Need to figure out how to handle this with the Source-based kubernetes logs
    val processedLogs = awaitLogs(container.logs(limit = 4096.B, waitForSentinel = true))

    kubernetes.logCalls should have size 1
    val (id, sinceTime) = kubernetes.logCalls(0)
    id shouldBe containerId
    sinceTime shouldBe None

    processedLogs should have size 1
    processedLogs shouldBe Vector(expectedLogEntry.rawString)
  }

  it should "read a simple log without sentinel" in {
    val expectedLogEntry = TypedLogLine(currentTsp, "stdout", "This is a log entry.")
    val logSrc = logSource(expectedLogEntry, appendSentinel = false)

    implicit val kubernetes = new TestKubernetesClient {
      override def logs(container: KubernetesContainer, sinceTime: Option[Instant], waitForSentinel: Boolean)(
        implicit transid: TransactionId): Source[TypedLogLine, Any] = {
        logCalls += ((container.id, sinceTime))
        logSrc
      }
    }

    val container = kubernetesContainer(id = containerId)()
    // Read without tight limit so that the full read result is processed
    val processedLogs = awaitLogs(container.logs(limit = 1.MB, waitForSentinel = false))

    kubernetes.logCalls should have size 1
    val (id, sinceTime) = kubernetes.logCalls(0)
    id shouldBe containerId
    sinceTime shouldBe None

    processedLogs should have size 1
    processedLogs shouldBe Vector(expectedLogEntry.rawString)
  }

  it should "fail log reading if error occurs during file reading" in {
    implicit val kubernetes = new TestKubernetesClient {
      override def logs(container: KubernetesContainer, sinceTime: Option[Instant], waitForSentinel: Boolean)(
        implicit transid: TransactionId): Source[TypedLogLine, Any] = {
        logCalls += ((container.id, sinceTime))
        Source.failed(new IOException)
      }
    }

    val container = kubernetesContainer()()
    an[IOException] should be thrownBy awaitLogs(container.logs(limit = 1.MB, waitForSentinel = true))

    kubernetes.logCalls should have size 1
    val (id, sinceTime) = kubernetes.logCalls(0)
    id shouldBe containerId
    sinceTime shouldBe None
  }

  it should "read two consecutive logs with sentinel" in {
    val firstLog = TypedLogLine(Instant.EPOCH, "stdout", "This is the first log.")
    val secondLog = TypedLogLine(Instant.EPOCH.plusSeconds(1l), "stderr", "This is the second log.")
    val logSources = mutable.Queue(logSource(firstLog, true), logSource(secondLog, true))

    implicit val kubernetes = new TestKubernetesClient {
      override def logs(container: KubernetesContainer, sinceTime: Option[Instant], waitForSentinel: Boolean)(
        implicit transid: TransactionId): Source[TypedLogLine, Any] = {
        logCalls += ((container.id, sinceTime))
        logSources.dequeue()
      }
    }

    val container = kubernetesContainer()()
    // Read without tight limit so that the full read result is processed
    val processedFirstLog = awaitLogs(container.logs(limit = 1.MB, waitForSentinel = true))
    val processedSecondLog = awaitLogs(container.logs(limit = 1.MB, waitForSentinel = true))

    kubernetes.logCalls should have size 2
    val (_, sinceTime1) = kubernetes.logCalls(0)
    sinceTime1 shouldBe None
    val (_, sinceTime2) = kubernetes.logCalls(1)
    sinceTime2 shouldBe Some(EpochDateTime) // second read should start behind the first line

    processedFirstLog should have size 1
    processedFirstLog shouldBe Vector(firstLog.rawString)
    processedSecondLog should have size 1
    processedSecondLog shouldBe Vector(secondLog.rawString)

  }

  it should "eventually terminate even if no sentinels can be found" in {
    val expectedLog = TypedLogLine(currentTsp, "stdout", s"This is log entry.")
    val rawLog = toLogs(expectedLog, appendSentinel = false)

    rawLog should have size 1

    implicit val kubernetes = new TestKubernetesClient {
      override def logs(container: KubernetesContainer, sinceTime: Option[Instant], waitForSentinel: Boolean)(
        implicit transid: TransactionId): Source[TypedLogLine, Any] = {
        logCalls += ((container.id, sinceTime))
        // "Fakes" an infinite source with only 1 entry
        Source.tick(0.milliseconds, 10.seconds, rawLog.head)
      }
    }

    val waitForLogs = 100.milliseconds
    val container = kubernetesContainer()(awaitLogs = waitForLogs)
    // Read without tight limit so that the full read result is processed

    val (interval, processedLog) = durationOf(awaitLogs(container.logs(limit = 1.MB, waitForSentinel = true)))

    interval.toMillis should (be >= waitForLogs.toMillis and be < (waitForLogs * 2).toMillis)

    kubernetes.logCalls should have size 1

    /*    processedLog should have size expectedLog.length
    processedLog shouldBe expectedLog.map(_.toFormattedString)*/
  }

  it should "include an incomplete warning if sentinels have not been found only if we wait for sentinels" in {
    val expectedLogEntry =
      TypedLogLine(currentTsp, "stdout", "This is a log entry.")

    implicit val kubernetes = new TestKubernetesClient {
      override def logs(container: KubernetesContainer, sinceTime: Option[Instant], waitForSentinel: Boolean)(
        implicit transid: TransactionId): Source[TypedLogLine, Any] = {
        logCalls += ((container.id, sinceTime))
        logSource(Queue(expectedLogEntry, expectedLogEntry), appendSentinel = false)
      }
    }

    val waitForLogs = 100.milliseconds
    val container = kubernetesContainer()(awaitLogs = waitForLogs)
    // Read with tight limit to verify that no truncation occurs
    val processedLogs = awaitLogs(container.logs(limit = 4096.B, waitForSentinel = true))

    kubernetes.logCalls should have size 1
    val (id, sinceTime) = kubernetes.logCalls(0)
    id shouldBe containerId
    sinceTime shouldBe None

    processedLogs should have size 3
    processedLogs(0) shouldBe expectedLogEntry.rawString
    processedLogs(1) shouldBe expectedLogEntry.rawString
    processedLogs(2) should include(Messages.logFailure)

    val processedLogsFalse = awaitLogs(container.logs(limit = 4096.B, waitForSentinel = false))
    processedLogsFalse should have size 2
    processedLogsFalse(0) shouldBe expectedLogEntry.rawString
    processedLogsFalse(1) shouldBe expectedLogEntry.rawString
  }

  it should "strip sentinel lines if it waits or doesn't wait for them" in {
    val expectedLogEntry =
      TypedLogLine(currentTsp, "stdout", "This is a log entry.")

    implicit val kubernetes = new TestKubernetesClient {
      override def logs(container: KubernetesContainer, sinceTime: Option[Instant], waitForSentinel: Boolean)(
        implicit transid: TransactionId): Source[TypedLogLine, Any] = {
        logCalls += ((container.id, sinceTime))
        logSource(expectedLogEntry, appendSentinel = true)
      }
    }

    val container = kubernetesContainer(id = containerId)()
    val processedLogs = awaitLogs(container.logs(limit = 1.MB, waitForSentinel = true))
    processedLogs should have size 1
    processedLogs(0) shouldBe expectedLogEntry.rawString

    val processedLogsFalse = awaitLogs(container.logs(limit = 1.MB, waitForSentinel = false))
    processedLogsFalse should have size 1
    processedLogsFalse(0) shouldBe expectedLogEntry.rawString
  }

  it should "delete a pod that failed to start due to KubernetesPodApiException" in {
    implicit val kubernetes = new TestKubernetesClient {
      override def run(
        name: String,
        image: String,
        memory: ByteSize = 256.MB,
        env: Map[String, String] = Map.empty,
        labels: Map[String, String] = Map.empty)(implicit transid: TransactionId): Future[KubernetesContainer] = {
        Future.failed(KubernetesPodApiException(new Exception("faking fabric8 failure...")))
      }
    }

    val container =
      KubernetesContainer.create(transid = transid, name = "name", image = "image", userProvidedImage = true)
    container.failed.futureValue shouldBe WhiskContainerStartupError(Messages.resourceProvisionError)

    kubernetes.runs should have size 0
    kubernetes.rms should have size 1
  }

  it should "delete a pod that failed to start due to some other Exception" in {
    implicit val kubernetes = new TestKubernetesClient {
      override def run(
        name: String,
        image: String,
        memory: ByteSize = 256.MB,
        env: Map[String, String] = Map.empty,
        labels: Map[String, String] = Map.empty)(implicit transid: TransactionId): Future[KubernetesContainer] = {
        Future.failed(new Exception("faking fabric8 failure..."))
      }
    }

    val container =
      KubernetesContainer.create(transid = transid, name = "name", image = "image", userProvidedImage = true)
    container.failed.futureValue shouldBe a[WhiskContainerStartupError]

    kubernetes.runs should have size 0
    kubernetes.rms should have size 1
  }
  def currentTsp: Instant = Instant.now

}

object KubernetesContainerTests {

  def logSource(logLine: TypedLogLine, appendSentinel: Boolean): Source[TypedLogLine, Any] =
    logSource(Queue(logLine), appendSentinel)

  def logSource(logs: Queue[TypedLogLine], appendSentinel: Boolean): Source[TypedLogLine, Any] =
    Source(toLogs(logs, appendSentinel))

  def toLogs(logLine: TypedLogLine, appendSentinel: Boolean): Queue[TypedLogLine] =
    toLogs(Queue(logLine), appendSentinel)

  def toLogs(log: Queue[TypedLogLine], appendSentinel: Boolean): Queue[TypedLogLine] =
    if (appendSentinel) {
      val lastTime = log.lastOption.map { case TypedLogLine(time, _, _) => time }.getOrElse(Instant.EPOCH)
      log :+
        TypedLogLine(lastTime, "stderr", s"${Container.ACTIVATION_LOG_SENTINEL}") :+
        TypedLogLine(lastTime, "stdout", s"${Container.ACTIVATION_LOG_SENTINEL}")
    } else {
      log
    }

  implicit class TypedLogHelper(log: TypedLogLine) {
    import KubernetesClient.formatK8STimestamp

    def rawString: String = "%s %s: %s".format(formatK8STimestamp(log.time).get.trim, log.stream, log.log)
  }

}
