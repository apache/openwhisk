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

package whisk.core.containerpool.kubernetes.test

import java.time.Instant

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Concat, Sink, Source}

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.Eventually
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner
import org.scalatest.Matchers
import org.scalatest.time.{Seconds, Span}
import common.{StreamLogging, WskActorSystem}
import okio.Buffer
import whisk.common.LogMarker
import whisk.common.LoggingMarkers.INVOKER_KUBECTL_CMD
import whisk.common.TransactionId
import whisk.core.containerpool.{ContainerAddress, ContainerId}
import whisk.core.containerpool.kubernetes.{KubernetesApi, KubernetesClient, KubernetesRestLogSourceStage, TypedLogLine}
import whisk.core.containerpool.docker.ProcessRunningException

import scala.collection.mutable
import scala.collection.immutable

@RunWith(classOf[JUnitRunner])
class KubernetesClientTests
    extends FlatSpec
    with Matchers
    with StreamLogging
    with BeforeAndAfterEach
    with Eventually
    with WskActorSystem {

  import KubernetesClientTests._

  implicit val materializer: ActorMaterializer = ActorMaterializer()

  /** Reads logs into memory and awaits them */
  def awaitLogs(source: Source[TypedLogLine, Any], timeout: FiniteDuration = 1000.milliseconds): Vector[TypedLogLine] =
    Await.result(source.runWith(Sink.seq[TypedLogLine]), timeout).toVector

  override def beforeEach = stream.reset()

  implicit override val patienceConfig = PatienceConfig(timeout = scaled(Span(5, Seconds)))

  implicit val transid = TransactionId.testing
  val id = ContainerId("55db56ee082239428b27d3728b4dd324c09068458aad9825727d5bfc1bba6d52")

  val commandTimeout = 500.milliseconds
  def await[A](f: Future[A], timeout: FiniteDuration = commandTimeout) = Await.result(f, timeout)

  val kubectlCommand = "kubectl"

  /** Returns a KubernetesClient with a mocked result for 'executeProcess' */
  def kubernetesClient(fixture: => Future[String]) = new KubernetesClient()(global) {
    override def findKubectlCmd() = kubectlCommand
    override def executeProcess(args: Seq[String], timeout: Duration)(implicit ec: ExecutionContext, as: ActorSystem) =
      fixture
  }

  behavior of "KubernetesClient"

  it should "write proper log markers on a successful command" in {
    // a dummy string works here as we do not assert any output
    // from the methods below
    val stdout = "stdout"
    val client = kubernetesClient { Future.successful(stdout) }

    /** Awaits the command and checks for proper logging. */
    def runAndVerify(f: Future[_], cmd: String, args: Seq[String]) = {
      val result = await(f)

      logLines.head should include((Seq(kubectlCommand, cmd) ++ args).mkString(" "))

      val start = LogMarker.parse(logLines.head)
      start.token shouldBe INVOKER_KUBECTL_CMD(cmd)

      val end = LogMarker.parse(logLines.last)
      end.token shouldBe INVOKER_KUBECTL_CMD(cmd).asFinish

      stream.reset()
      result
    }

    runAndVerify(client.rm(id), "delete", Seq("--now", "pod", id.asString))

    val image = "image"
    val name = "name"
    val expected = Seq(name, s"--image=$image")
    runAndVerify(client.run(name, image), "run", expected) shouldBe ContainerId(name)
  }

  it should "write proper log markers on a failing command" in {
    val client = kubernetesClient { Future.failed(new RuntimeException()) }

    /** Awaits the command, asserts the exception and checks for proper logging. */
    def runAndVerify(f: Future[_], cmd: String) = {
      a[RuntimeException] should be thrownBy await(f)

      val start = LogMarker.parse(logLines.head)
      start.token shouldBe INVOKER_KUBECTL_CMD(cmd)

      val end = LogMarker.parse(logLines.last)
      end.token shouldBe INVOKER_KUBECTL_CMD(cmd).asError

      stream.reset()
    }

    runAndVerify(client.rm(id), "delete")
    runAndVerify(client.run("name", "image"), "run")
  }

  it should "fail with ProcessRunningException when run returns with exit code !=125 or no container ID" in {
    def runAndVerify(pre: ProcessRunningException, clue: String) = {
      val client = kubernetesClient { Future.failed(pre) }
      withClue(s"${clue} - exitCode = ${pre.exitCode}, stdout = '${pre.stdout}', stderr = '${pre.stderr}': ") {
        the[ProcessRunningException] thrownBy await(client.run("name", "image")) shouldBe pre
      }
    }

    Seq[(ProcessRunningException, String)](
      (ProcessRunningException(126, id.asString, "Unknown command"), "Exit code not 125"),
      (ProcessRunningException(125, "", "Unknown flag: --foo"), "No container ID"),
      (ProcessRunningException(1, "", ""), "Exit code not 125 and no container ID")).foreach {
      case (pre, clue) => runAndVerify(pre, clue)
    }
  }

  val firstLog = """2018-02-06T00:00:18.419889342Z first activation
                   |2018-02-06T00:00:18.419929471Z XXX_THE_END_OF_A_WHISK_ACTIVATION_XXX
                   |2018-02-06T00:00:18.419988733Z XXX_THE_END_OF_A_WHISK_ACTIVATION_XXX
                   |""".stripMargin
  val secondLog = """2018-02-06T00:09:35.38267193Z second activation
                    |2018-02-06T00:09:35.382990278Z XXX_THE_END_OF_A_WHISK_ACTIVATION_XXX
                    |2018-02-06T00:09:35.383116503Z XXX_THE_END_OF_A_WHISK_ACTIVATION_XXX
                    |""".stripMargin

  def firstSource(lastTimestamp: Option[Instant] = None): Source[TypedLogLine, Any] =
    Source(
      KubernetesRestLogSourceStage
        .readLines(new Buffer().writeUtf8(firstLog), lastTimestamp, List.empty)
        .to[immutable.Seq])

  def secondSource(lastTimestamp: Option[Instant] = None): Source[TypedLogLine, Any] =
    Source(
      KubernetesRestLogSourceStage
        .readLines(new Buffer().writeUtf8(secondLog), lastTimestamp, List.empty)
        .to[immutable.Seq])

  it should "return all logs when no sinceTime passed" in {
    val client = new TestKubernetesClient {
      override def logs(id: ContainerId, sinceTime: Option[Instant], waitForSentinel: Boolean)(
        implicit transid: TransactionId): Source[TypedLogLine, Any] = {
        firstSource()
      }
    }
    val logs = awaitLogs(client.logs(id, None))
    logs should have size 3
    logs(0) shouldBe TypedLogLine("2018-02-06T00:00:18.419889342Z", "stdout", "first activation")
    logs(2) shouldBe TypedLogLine("2018-02-06T00:00:18.419988733Z", "stdout", "XXX_THE_END_OF_A_WHISK_ACTIVATION_XXX")
  }

  it should "return all logs after the one matching sinceTime" in {

    val testDate: Option[Instant] = "2018-02-06T00:00:18.419988733Z"
    val client = new TestKubernetesClient {
      override def logs(id: ContainerId, sinceTime: Option[Instant], waitForSentinel: Boolean)(
        implicit transid: TransactionId): Source[TypedLogLine, Any] = {
        Source.combine(firstSource(testDate), secondSource(testDate))(Concat(_))
      }
    }
    val logs = awaitLogs(client.logs(id, testDate))
    logs should have size 3
    logs(0) shouldBe TypedLogLine("2018-02-06T00:09:35.38267193Z", "stdout", "second activation")
    logs(2) shouldBe TypedLogLine("2018-02-06T00:09:35.383116503Z", "stdout", "XXX_THE_END_OF_A_WHISK_ACTIVATION_XXX")
  }

  it should "return all logs if none match sinceTime" in {
    val testDate: Option[Instant] = "2018-02-06T00:00:18.419988733Z"
    val client = new TestKubernetesClient {
      override def logs(id: ContainerId, sinceTime: Option[Instant], waitForSentinel: Boolean)(
        implicit transid: TransactionId): Source[TypedLogLine, Any] = {
        secondSource(testDate)
      }
    }
    val logs = awaitLogs(client.logs(id, testDate))
    logs should have size 3
    logs(0) shouldBe TypedLogLine("2018-02-06T00:09:35.38267193Z", "stdout", "second activation")
    logs(2) shouldBe TypedLogLine("2018-02-06T00:09:35.383116503Z", "stdout", "XXX_THE_END_OF_A_WHISK_ACTIVATION_XXX")
  }

}

object KubernetesClientTests {
  import scala.language.implicitConversions

  implicit def strToDate(str: String): Option[Instant] =
    KubernetesClient.parseK8STimestamp(str).toOption

  implicit def strToInstant(str: String): Instant =
    strToDate(str).get

  class TestKubernetesClient extends KubernetesApi {
    var runs = mutable.Buffer.empty[(String, String, Seq[String])]
    var inspects = mutable.Buffer.empty[ContainerId]
    var rms = mutable.Buffer.empty[ContainerId]
    var rmByLabels = mutable.Buffer.empty[(String, String)]
    var logCalls = mutable.Buffer.empty[(ContainerId, Option[Instant])]

    def run(name: String, image: String, args: Seq[String] = Seq.empty[String])(
      implicit transid: TransactionId): Future[ContainerId] = {
      runs += ((name, image, args))
      Future.successful(ContainerId("testId"))
    }

    def inspectIPAddress(id: ContainerId)(implicit transid: TransactionId): Future[ContainerAddress] = {
      inspects += id
      Future.successful(ContainerAddress("testIp"))
    }

    def rm(id: ContainerId)(implicit transid: TransactionId): Future[Unit] = {
      rms += id
      Future.successful(())
    }

    def rm(key: String, value: String)(implicit transid: TransactionId): Future[Unit] = {
      rmByLabels += ((key, value))
      Future.successful(())
    }
    def logs(id: ContainerId, sinceTime: Option[Instant], waitForSentinel: Boolean = false)(
      implicit transid: TransactionId): Source[TypedLogLine, Any] = {
      logCalls += ((id, sinceTime))
      Source(List.empty[TypedLogLine])
    }
  }
}
