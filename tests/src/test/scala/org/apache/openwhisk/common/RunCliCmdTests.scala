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

package org.apache.openwhisk.common

import java.io.File

import org.junit.runner.RunWith
import org.scalatest.{BeforeAndAfterEach, FlatSpec}
import org.scalatest.junit.JUnitRunner
import common.RunCliCmd
import common.TestUtils._

import scala.collection.mutable.Buffer

@RunWith(classOf[JUnitRunner])
class RunCliCmdTests extends FlatSpec with RunCliCmd with BeforeAndAfterEach {

  case class TestRunResult(code: Int) extends RunResult(code, "", "")
  val defaultRR = TestRunResult(0)

  override def baseCommand = Buffer.empty

  override def runCmd(expectedExitCode: Int,
                      dir: File,
                      env: Map[String, String],
                      fileStdin: Option[File],
                      params: Seq[String]): RunResult = {
    cmdCount += 1
    rr.getOrElse(defaultRR)
  }

  override def beforeEach() = {
    rr = None
    cmdCount = 0
  }

  var rr: Option[TestRunResult] = None // optional run result override per test
  var cmdCount = 0

  it should "retry commands that experience network errors" in {
    Seq(ANY_ERROR_EXIT, DONTCARE_EXIT, NETWORK_ERROR_EXIT).foreach { code =>
      cmdCount = 0

      rr = Some(TestRunResult(NETWORK_ERROR_EXIT))
      noException shouldBe thrownBy {
        cli(Seq.empty, expectedExitCode = code)
      }

      cmdCount shouldBe 3 + 1
    }
  }

  it should "not retry commands if retry is disabled" in {
    rr = Some(TestRunResult(NETWORK_ERROR_EXIT))
    noException shouldBe thrownBy {
      cli(Seq.empty, expectedExitCode = ANY_ERROR_EXIT, retriesOnNetworkError = 0)
    }

    cmdCount shouldBe 1
  }

  it should "not retry commands if failure is not retriable" in {
    Seq(MISUSE_EXIT, ERROR_EXIT, SUCCESS_EXIT).foreach { code =>
      cmdCount = 0

      rr = Some(TestRunResult(code))
      noException shouldBe thrownBy {
        cli(Seq.empty, expectedExitCode = DONTCARE_EXIT, retriesOnNetworkError = 3)
      }

      cmdCount shouldBe 1
    }
  }

}
