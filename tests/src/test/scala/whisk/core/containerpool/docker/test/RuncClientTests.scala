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
import org.scalatest.BeforeAndAfterEach
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner
import org.scalatest.Matchers

import common.StreamLogging
import whisk.common.LogMarker
import whisk.common.LoggingMarkers.INVOKER_RUNC_CMD
import whisk.common.TransactionId
import whisk.core.containerpool.docker.ContainerId
import whisk.core.containerpool.docker.RuncClient

@RunWith(classOf[JUnitRunner])
class RuncClientTests extends FlatSpec with Matchers with StreamLogging with BeforeAndAfterEach {

    override def beforeEach = stream.reset()

    implicit val transid = TransactionId.testing
    val id = ContainerId("Id")

    def await[A](f: Future[A], timeout: FiniteDuration = 500.milliseconds) = Await.result(f, timeout)

    val runcCommand = "docker-runc"

    /** Calls a runc method based on the name of the method. */
    def runcProxy(runc: RuncClient, method: String) = {
        method match {
            case "pause"  => runc.pause(id)
            case "resume" => runc.resume(id)
        }
    }

    /** Verifies start and end logs are written correctly. */
    def verifyLogs(cmd: String, failed: Boolean = false) = {
        logLines.head should include(s"${runcCommand} ${cmd} ${id.asString}")

        // start log maker must be found
        val start = LogMarker.parse(logLines.head)
        start.token should be(INVOKER_RUNC_CMD(cmd))

        // end log marker must be found
        val expectedEnd = if (failed) INVOKER_RUNC_CMD(cmd).asError else INVOKER_RUNC_CMD(cmd).asFinish
        val end = LogMarker.parse(logLines.last)
        end.token shouldBe expectedEnd
    }

    behavior of "RuncClient"

    Seq("pause", "resume").foreach { cmd =>
        it should s"$cmd a container successfully, create log entries and invoe proper commands" in {
            val rc = new TestRuncClient(Future.successful(""))
            await(runcProxy(rc, cmd))
            verifyLogs(cmd)
            rc.verifyExecProcInvocation(cmd)
        }

        it should s"write error markers when $cmd fails" in {
            val rc = new TestRuncClient(Future.failed(new RuntimeException()))
            a[RuntimeException] should be thrownBy await(runcProxy(rc, cmd))
            verifyLogs(cmd, failed = true)
        }

    }

    class TestRuncClient(execResult: Future[String])(implicit executionContext: ExecutionContext) extends RuncClient(executionContext)(logging) {
        val execProcInvocations = mutable.Buffer.empty[(Seq[String], Duration)]

        override val runcCmd = Seq(runcCommand)
        override def executeProcess(args: Seq[String], timeout: Duration)(implicit ec: ExecutionContext): Future[String] = {
            execProcInvocations += ((args, timeout))
            execResult
        }

        /** Verify proper Runc command invocation including parameters and timeout */
        def verifyExecProcInvocation(expectedRuncCmd: String) = {
            execProcInvocations should have size 1
            val (actualArgs, actualTimeout) = execProcInvocations(0)

            actualArgs should have size 3
            actualArgs(0) shouldBe runcCommand
            actualArgs(1) shouldBe expectedRuncCmd
            actualArgs(2) shouldBe id.asString
            actualTimeout shouldBe 1.minute
        }
    }
}
