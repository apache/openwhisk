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

import scala.concurrent.Future
import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import org.scalatest.Matchers
import org.apache.openwhisk.core.containerpool.docker.RuncClient
import common.{StreamLogging, WskActorSystem}
import org.apache.openwhisk.core.containerpool.ContainerId
import org.apache.openwhisk.common.TransactionId
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.apache.openwhisk.common.LogMarker
import org.apache.openwhisk.common.LoggingMarkers.INVOKER_RUNC_CMD

@RunWith(classOf[JUnitRunner])
class RuncClientTests
    extends FlatSpec
    with Matchers
    with StreamLogging
    with BeforeAndAfterEach
    with WskActorSystem
    with ScalaFutures
    with IntegrationPatience {

  override def beforeEach = stream.reset()

  implicit val transid = TransactionId.testing
  val id = ContainerId("Id")

  val runcCommand = "docker-runc"

  /** Returns a RuncClient with a mocked result for 'executeProcess' */
  def runcClient(result: Future[String]) = new RuncClient()(global) {
    override val runcCmd = Seq(runcCommand)
    override def executeProcess(args: Seq[String], timeout: Duration)(implicit ec: ExecutionContext, as: ActorSystem) =
      result
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
    start.token.toStringWithSubAction should be(INVOKER_RUNC_CMD(cmd).toStringWithSubAction)

    // end log marker must be found
    val expectedEnd = if (failed) INVOKER_RUNC_CMD(cmd).asError else INVOKER_RUNC_CMD(cmd).asFinish
    val end = LogMarker.parse(logLines.last)
    end.token.toStringWithSubAction shouldBe expectedEnd.toStringWithSubAction
  }

  behavior of "RuncClient"

  Seq("pause", "resume").foreach { cmd =>
    it should s"$cmd a container successfully and create log entries" in {
      val rc = runcClient { Future.successful("") }
      runcProxy(rc, cmd).futureValue
      verifyLogs(cmd)
    }

    it should s"write error markers when $cmd fails" in {
      val rc = runcClient { Future.failed(new RuntimeException()) }
      a[RuntimeException] should be thrownBy runcProxy(rc, cmd).futureValue
      verifyLogs(cmd, failed = true)
    }

  }
}
