/*
 * Copyright 2015-2016 IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.core.containerpool.docker.test

import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.BeforeAndAfterEach
import org.scalatest.FlatSpec
import org.scalatest.Matchers

import common.StreamLogging
import whisk.common.LogMarker
import whisk.common.LoggingMarkers.INVOKER_DOCKER_CMD
import whisk.common.TransactionId
import whisk.core.containerpool.docker.ContainerId
import whisk.core.containerpool.docker.ContainerIp
import whisk.core.containerpool.docker.DockerClient

@RunWith(classOf[JUnitRunner])
class DockerClientTests extends FlatSpec with Matchers with StreamLogging with BeforeAndAfterEach {

    override def beforeEach = stream.reset()

    implicit val transid = TransactionId.testing
    val id = ContainerId("Id")

    def await[A](f: Future[A], timeout: FiniteDuration = 500.milliseconds) = Await.result(f, timeout)

    val dockerCommand = "docker"

    behavior of "DockerClient"

    it should "return a list of containers and pass down the correct arguments when using 'ps'" in {
        val containers = Seq("1", "2", "3")
        val dc = new TestDockerClient(Future.successful(containers.mkString("\n")))
        val filters = Seq("name" -> "wsk", "label" -> "docker")
        val expectedDockerCmd = "ps"
        val expectedParams = Seq("--quiet", "--no-trunc", "--all") ++ filters.map { case (k, v) => Seq("--filter", s"${k}=${v}") }.flatten

        await(dc.ps(filters, all = true)) shouldBe containers.map(ContainerId.apply)

        val firstLine = logLines.head
        firstLine should include(s"${dockerCommand} ${expectedDockerCmd}")
        expectedParams.foreach { parm => firstLine should include(parm) }

        dc.verifyExecProcInvocation(expectedDockerCmd, expectedParams, 1.minute)
    }

    it should "throw NoSuchElementException if specified network does not exist when using 'inspectIPAddress'" in {
        val dc = new TestDockerClient(Future.successful("<no value>"))

        a[NoSuchElementException] should be thrownBy await(dc.inspectIPAddress(id, "foo network"))
    }

    it should "write proper log markers on a successful command and invoke proper commands" in {
        // a dummy string works here as we do not assert any output
        // from the methods below
        val stdout = "stdout"
        val network = "userland"
        val image = "image"
        val dc = new TestDockerClient(Future.successful(stdout))

        /** Awaits the command and checks for proper logging as well as command invocation. */
        def runAndVerify(expectedDockerCmd: String, expectedTimeout: Duration = 1.minute) = {
            val dc = new TestDockerClient(Future.successful(stdout))
            val (f, expectedParams) = expectedDockerCmd match {
                case "pause"   => (dc.pause(id), Seq(id.asString))
                case "unpause" => (dc.unpause(id), Seq(id.asString))
                case "rm"      => (dc.rm(id), Seq("-f", id.asString))
                case "ps"      => (dc.ps(), Seq.empty[String])
                case "inspect" =>
                    val inspectArgs = Seq("--format", s"{{.NetworkSettings.Networks.${network}.IPAddress}}", id.asString)
                    (dc.inspectIPAddress(id, network), inspectArgs)
                case "run" =>
                    val runArgs = Seq("--memory", "256m", "--cpushares", "1024")
                    (dc.run(image, runArgs), Seq("-d") ++ runArgs ++ Seq(image))
                case "pull" => (dc.pull(image), Seq(image))
                case _      => (Future.failed(new RuntimeException()), Seq.empty[String])
            }
            val result = await(f)

            logLines.head should include((Seq(dockerCommand, expectedDockerCmd) ++ expectedParams).mkString(" "))

            val start = LogMarker.parse(logLines.head)
            start.token shouldBe INVOKER_DOCKER_CMD(expectedDockerCmd)

            val end = LogMarker.parse(logLines.last)
            end.token shouldBe INVOKER_DOCKER_CMD(expectedDockerCmd).asFinish

            dc.verifyExecProcInvocation(expectedDockerCmd, expectedParams, expectedTimeout)

            stream.reset()
            result
        }

        runAndVerify("pause")
        runAndVerify("unpause")
        runAndVerify("rm")
        runAndVerify("ps")
        runAndVerify("inspect") shouldBe ContainerIp(stdout)
        runAndVerify("run") shouldBe ContainerId(stdout)
        runAndVerify("pull", expectedTimeout = 5.minutes)
    }

    it should "write proper log markers on a failing command" in {
        val dc = new TestDockerClient(Future.failed(new RuntimeException()))

        /** Awaits the command, asserts the exception and checks for proper logging. */
        def runAndVerify(f: Future[_], cmd: String) = {
            a[RuntimeException] should be thrownBy await(f)

            val start = LogMarker.parse(logLines.head)
            start.token shouldBe INVOKER_DOCKER_CMD(cmd)

            val end = LogMarker.parse(logLines.last)
            end.token shouldBe INVOKER_DOCKER_CMD(cmd).asError

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

    class TestDockerClient(execResult: Future[String])(implicit executionContext: ExecutionContext) extends DockerClient()(executionContext)(logging) {
        val execProcInvocations = mutable.Buffer.empty[(Seq[String], Duration)]

        override val dockerCmd = Seq(dockerCommand)
        override def executeProcess(args: Seq[String], timeout: Duration)(implicit ec: ExecutionContext): Future[String] = {
            execProcInvocations += ((args, timeout))
            execResult
        }

        /** Verify proper Docker command invocation including parameters and timeout */
        def verifyExecProcInvocation(expectedDockerCmd: String, expectedParams: Seq[String] = Seq.empty[String], expectedTimeout: Duration = 1.minute) = {
            execProcInvocations should have size 1
            val (actualArgs, actualTimeout) = execProcInvocations(0)

            actualArgs.size shouldBe >=(2 + expectedParams.size)
            actualArgs(0) shouldBe dockerCommand
            actualArgs(1) shouldBe expectedDockerCmd
            expectedParams.foreach { parm => actualArgs should contain(parm) }
            actualTimeout shouldBe expectedTimeout
        }
    }
}
