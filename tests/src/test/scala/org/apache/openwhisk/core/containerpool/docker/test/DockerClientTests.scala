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

import akka.actor.ActorSystem
import java.util.concurrent.Semaphore

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.concurrent.Promise
import scala.util.Success
import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.Eventually
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner
import org.scalatest.Matchers
import org.scalatest.time.{Seconds, Span}
import common.{StreamLogging, WskActorSystem}
import org.apache.openwhisk.common.LogMarker
import org.apache.openwhisk.common.LoggingMarkers.INVOKER_DOCKER_CMD
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.containerpool.ContainerAddress
import org.apache.openwhisk.core.containerpool.ContainerId
import org.apache.openwhisk.core.containerpool.docker._
import org.apache.openwhisk.utils.retry

@RunWith(classOf[JUnitRunner])
class DockerClientTests
    extends FlatSpec
    with Matchers
    with StreamLogging
    with BeforeAndAfterEach
    with Eventually
    with WskActorSystem {

  override def beforeEach = stream.reset()

  implicit override val patienceConfig = PatienceConfig(timeout = scaled(Span(5, Seconds)))

  implicit val transid = TransactionId.testing
  val id = ContainerId("55db56ee082239428b27d3728b4dd324c09068458aad9825727d5bfc1bba6d52")

  val commandTimeout = 500.milliseconds
  def await[A](f: Future[A], timeout: FiniteDuration = commandTimeout) = Await.result(f, timeout)

  val dockerCommand = "docker"

  /** Returns a DockerClient with a mocked result for 'executeProcess' */
  def dockerClient(execResult: => Future[String]) = new DockerClient()(global) {
    override val dockerCmd = Seq(dockerCommand)
    override def getClientVersion() = "mock-test-client"
    override def executeProcess(args: Seq[String], timeout: Duration)(implicit ec: ExecutionContext, as: ActorSystem) =
      execResult
  }

  behavior of "DockerContainerId"

  it should "convert a proper container ID" in {
    DockerContainerId.parse(id.asString) shouldBe Success(id)
  }

  it should "reject improper container IDs with IllegalArgumentException" in {
    def verifyFailure(improperId: String) = {
      val iae = the[IllegalArgumentException] thrownBy DockerContainerId.parse(improperId).get
      iae.getMessage should include(improperId)
    }

    Seq[(String, String)](
      ("", "String empty (too short)"),
      ("1" * 63, "String too short"),
      ("1" * 65, "String too long"),
      (("1" * 63) + "x", "Improper characters"),
      ("abcxdef", "Improper characters and too short")).foreach {
      case (improperId, clue) =>
        withClue(s"${clue} - length('${improperId}') = ${improperId.length}: ") {
          verifyFailure(improperId)
        }
    }
  }

  behavior of "DockerClient"

  it should "return a list of containers and pass down the correct arguments when using 'ps'" in {
    val containers = Seq("1", "2", "3")
    val dc = dockerClient { Future.successful(containers.mkString("\n")) }

    val filters = Seq("name" -> "wsk", "label" -> "docker")
    await(dc.ps(filters, all = true)) shouldBe containers.map(ContainerId.apply)

    val firstLine = logLines.head
    firstLine should include(s"${dockerCommand} ps")
    firstLine should include("--quiet")
    firstLine should include("--no-trunc")
    firstLine should include("--all")
    filters.foreach {
      case (k, v) => firstLine should include(s"--filter $k=$v")
    }
  }

  it should "throw NoSuchElementException if specified network does not exist when using 'inspectIPAddress'" in {
    val dc = dockerClient { Future.successful("<no value>") }

    a[NoSuchElementException] should be thrownBy await(dc.inspectIPAddress(id, "foo network"))
  }

  it should "collapse multiple parallel pull calls into just one" in {
    // Delay execution of the pull command
    val pullPromise = Promise[String]()
    var commandsRun = 0
    val dc = dockerClient {
      commandsRun += 1
      pullPromise.future
    }

    val image = "testimage"

    // Pull first, command should be run
    dc.pull(image)
    commandsRun shouldBe 1

    // Pull again, command should not be run
    dc.pull(image)
    commandsRun shouldBe 1

    // Finish the pulls above
    pullPromise.success("pulled")

    retry {
      // Pulling again should execute the command again
      await(dc.pull(image))
      commandsRun shouldBe 2
    }
  }

  it should "properly clean up failed pulls" in {
    // Delay execution of the pull command
    val pullPromise = Promise[String]()
    var commandsRun = 0
    val dc = dockerClient {
      commandsRun += 1
      pullPromise.future
    }

    val image = "testimage"

    // Pull first, command should be run
    dc.pull(image)
    commandsRun shouldBe 1

    // Pull again, command should not be run
    dc.pull(image)
    commandsRun shouldBe 1

    // Finish the pulls above
    pullPromise.failure(new Throwable())

    retry {
      // Pulling again should execute the command again
      Await.ready(dc.pull(image), commandTimeout)
      commandsRun shouldBe 2
    }
  }

  it should "limit the number of concurrent docker run invocations" in {
    // Delay execution of Docker run command
    val firstRunPromise = Promise[String]()

    val firstContainerId = ContainerId("1" * 64)
    val secondContainerId = ContainerId("2" * 64)

    var runCmdCount = 0
    val dc = new DockerClient()(global) {
      override val dockerCmd = Seq(dockerCommand)
      override def getClientVersion() = "mock-test-client"
      override def executeProcess(args: Seq[String], timeout: Duration)(implicit ec: ExecutionContext,
                                                                        as: ActorSystem) = {
        runCmdCount += 1
        runCmdCount match {
          case 1 => firstRunPromise.future
          case 2 => Future.successful(secondContainerId.asString)
          case _ => Future.failed(new Throwable())
        }
      }
      // Need to override the semaphore, otherwise the tested code will still
      // create the semaphore with the original value of maxParallelRuns.
      override val maxParallelRuns = 1
      override val runSemaphore = new Semaphore( /* permits= */ maxParallelRuns, /* fair= */ true)
    }

    val image = "image"
    val args = Seq("args")

    val firstRunResult = dc.run(image, args)
    val secondRunResult = dc.run(image, args)

    // The tested code won't reach the mocked executeProcess() and thus, increase runCmdCount,
    // until at least one Future is successfully completed. For this reason, it takes
    // some time until the following matcher is successful.
    eventually { runCmdCount shouldBe 1 }

    // Complete the first Docker run command so that the second is eligible to run
    firstRunPromise.success(firstContainerId.asString)

    // Cannot assert that the first Docker run always obtains the first container because
    // the tested code uses Futures so that sequence may differ from test run to test run.
    val firstResultContainerId = await(firstRunResult)

    // Now, second command should be complete
    eventually { runCmdCount shouldBe 2 }

    val secondResultContainerId = await(secondRunResult)
    Set(firstResultContainerId, secondResultContainerId) should contain theSameElementsAs Set(
      firstContainerId,
      secondContainerId)
  }

  it should "tolerate docker run errors when limiting the number of concurrent docker run invocations" in {
    val secondContainerId = ContainerId("2" * 64)

    var runCmdCount = 0
    val dc = new DockerClient()(global) {
      override val dockerCmd = Seq(dockerCommand)
      override def getClientVersion() = "mock-test-client"
      override def executeProcess(args: Seq[String], timeout: Duration)(implicit ec: ExecutionContext,
                                                                        as: ActorSystem) = {
        runCmdCount += 1
        println(s"runCmdCount=${runCmdCount}, args.last=${args.last}")
        runCmdCount match {
          case 1 => Future.failed(ProcessUnsuccessfulException(ExitStatus(1), "", ""))
          case 2 => Future.successful(secondContainerId.asString)
          case _ => Future.failed(new Throwable())
        }
      }
      // Need to override the semaphore, otherwise the tested code will still
      // create the semaphore with the original value of maxParallelRuns.
      override val maxParallelRuns = 1
      override val runSemaphore = new Semaphore( /* permits= */ maxParallelRuns, /* fair= */ true)
    }

    val image = "image"
    val args = Seq("args")

    // Kick off the first Docker run command - it will fail.
    val firstRunResult = dc.run(image, args)

    an[Exception] should be thrownBy await(firstRunResult)
    runCmdCount shouldBe 1

    // Now kick off the second Docker run command - it is expected to succeed.
    // If this command completes without timeout, the concurrency limit properly
    // deals with errors.
    val secondRunResult = dc.run(image, args)

    await(secondRunResult) shouldBe secondContainerId
    runCmdCount shouldBe 2
  }

  it should "write proper log markers on a successful command" in {
    // a dummy string works here as we do not assert any output
    // from the methods below
    val stdout = "stdout"
    val dc = dockerClient { Future.successful(stdout) }

    /** Awaits the command and checks for proper logging. */
    def runAndVerify(f: Future[_], cmd: String, args: Seq[String] = Seq.empty[String]) = {
      val result = await(f)

      logLines.head should include((Seq(dockerCommand, cmd) ++ args).mkString(" "))

      val start = LogMarker.parse(logLines.head)
      start.token.toStringWithSubAction shouldBe INVOKER_DOCKER_CMD(cmd).toStringWithSubAction

      val end = LogMarker.parse(logLines.last)
      end.token.toStringWithSubAction shouldBe INVOKER_DOCKER_CMD(cmd).asFinish.toStringWithSubAction

      stream.reset()
      result
    }

    runAndVerify(dc.pause(id), "pause", Seq(id.asString))
    runAndVerify(dc.unpause(id), "unpause", Seq(id.asString))
    runAndVerify(dc.rm(id), "rm", Seq("-f", id.asString))
    runAndVerify(dc.ps(), "ps")

    val network = "userland"
    val inspectArgs = Seq("--format", s"{{.NetworkSettings.Networks.${network}.IPAddress}}", id.asString)
    runAndVerify(dc.inspectIPAddress(id, network), "inspect", inspectArgs) shouldBe ContainerAddress(stdout)

    val image = "image"
    val runArgs = Seq("--memory", "256m", "--cpushares", "1024")
    runAndVerify(dc.run(image, runArgs), "run", Seq("-d") ++ runArgs ++ Seq(image)) shouldBe ContainerId(stdout)
    runAndVerify(dc.pull(image), "pull", Seq(image))
  }

  it should "write proper log markers on a failing command" in {
    val dc = dockerClient { Future.failed(new RuntimeException()) }

    /** Awaits the command, asserts the exception and checks for proper logging. */
    def runAndVerify(f: Future[_], cmd: String) = {
      a[RuntimeException] should be thrownBy await(f)

      val start = LogMarker.parse(logLines.head)
      start.token.toStringWithSubAction shouldBe INVOKER_DOCKER_CMD(cmd).toStringWithSubAction

      val end = LogMarker.parse(logLines.last)
      end.token.toStringWithSubAction shouldBe INVOKER_DOCKER_CMD(cmd).asError.toStringWithSubAction

      stream.reset()
    }

    runAndVerify(dc.pause(id), "pause")
    runAndVerify(dc.unpause(id), "unpause")
    runAndVerify(dc.rm(id), "rm")
    runAndVerify(dc.ps(), "ps")
    runAndVerify(dc.inspectIPAddress(id, "network"), "inspect")
    runAndVerify(dc.run("image"), "run")
    runAndVerify(dc.pull("image"), "pull")
  }

  it should "fail with BrokenDockerContainer when run returns with exit status 125 and a container ID" in {
    val dc = dockerClient {
      Future.failed(
        ProcessUnsuccessfulException(
          exitStatus = ExitStatus(125),
          stdout = id.asString,
          stderr =
            """/usr/bin/docker: Error response from daemon: mkdir /var/run/docker.1.1/libcontainerd.1.1/55db56ee082239428b27d3728b4dd324c09068458aad9825727d5bfc1bba6d52: no space left on device."""))
    }
    val bdc = the[BrokenDockerContainer] thrownBy await(dc.run("image", Seq.empty))
    bdc.id shouldBe id
  }

  it should "fail with ProcessRunningException when run returns with exit code !=125, no container ID or timeout" in {
    def runAndVerify(pre: ProcessRunningException, clue: String) = {
      val dc = dockerClient { Future.failed(pre) }
      withClue(s"${clue} - exitStatus = ${pre.exitStatus}, stdout = '${pre.stdout}', stderr = '${pre.stderr}': ") {
        the[ProcessRunningException] thrownBy await(dc.run("image", Seq.empty)) shouldBe pre
      }
    }

    Seq[(ProcessRunningException, String)](
      (ProcessUnsuccessfulException(ExitStatus(127), id.asString, "Unknown command"), "Exit code not 125"),
      (ProcessUnsuccessfulException(ExitStatus(125), "", "Unknown flag: --foo"), "No container ID"),
      (ProcessUnsuccessfulException(ExitStatus(1), "", ""), "Exit code not 125 and no container ID"),
      (ProcessTimeoutException(1.second, ExitStatus(125), id.asString, ""), "Timeout instead of unsuccessful command"))
      .foreach {
        case (pre, clue) => runAndVerify(pre, clue)
      }
  }

  it should "fail with ProcessTimeoutException when command times out" in {
    val expectedPTE =
      ProcessTimeoutException(timeout = 10.seconds, exitStatus = ExitStatus(143), stdout = "stdout", stderr = "stderr")
    val dc = dockerClient {
      Future.failed(expectedPTE)
    }
    Seq[(Future[_], String)](
      (dc.run("image", Seq.empty), "run"),
      (dc.inspectIPAddress(id, "network"), "inspectIPAddress"),
      (dc.pause(id), "pause"),
      (dc.unpause(id), "unpause"),
      (dc.rm(id), "rm"),
      (dc.ps(), "ps"),
      (dc.pull("image"), "pull"),
      (dc.isOomKilled(id), "isOomKilled"))
      .foreach {
        case (cmd, clue) =>
          withClue(s"command '$clue' - ") {
            the[ProcessTimeoutException] thrownBy await(cmd) shouldBe expectedPTE
          }
      }
  }
}
