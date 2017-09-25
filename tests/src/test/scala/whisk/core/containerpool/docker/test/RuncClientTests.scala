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

import scala.concurrent.Future

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.concurrent.Await
import org.scalatest.Matchers
import whisk.core.containerpool.docker.RuncClient
import common.StreamLogging
import whisk.core.containerpool.ContainerId
import whisk.common.TransactionId
import org.scalatest.BeforeAndAfterEach
import whisk.common.LogMarker
import whisk.common.LoggingMarkers.INVOKER_RUNC_CMD

@RunWith(classOf[JUnitRunner])
class RuncClientTests extends FlatSpec with Matchers with StreamLogging with BeforeAndAfterEach {

  override def beforeEach = stream.reset()

  implicit val transid = TransactionId.testing
  val id = ContainerId("Id")

  def await[A](f: Future[A], timeout: FiniteDuration = 500.milliseconds) = Await.result(f, timeout)

  val runcCommand = "docker-runc"

  /** Returns a RuncClient with a mocked result for 'executeProcess' */
  def runcClient(result: Future[String]) = new RuncClient(global) {
    override val runcCmd = Seq(runcCommand)
    override def executeProcess(args: String*)(implicit ec: ExecutionContext) = result
  }

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
    it should s"$cmd a container successfully and create log entries" in {
      val rc = runcClient { Future.successful("") }
      await(runcProxy(rc, cmd))
      verifyLogs(cmd)
    }

    it should s"write error markers when $cmd fails" in {
      val rc = runcClient { Future.failed(new RuntimeException()) }
      a[RuntimeException] should be thrownBy await(runcProxy(rc, cmd))
      verifyLogs(cmd, failed = true)
    }

  }
}
