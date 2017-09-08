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
import whisk.core.containerpool.docker.ProcessRunner
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.concurrent.Await
import org.scalatest.Matchers
import whisk.core.containerpool.docker.ProcessRunningException
import scala.language.reflectiveCalls // Needed to invoke run() method of structural ProcessRunner extension

@RunWith(classOf[JUnitRunner])
class ProcessRunnerTests extends FlatSpec with Matchers {

  def await[A](f: Future[A], timeout: FiniteDuration = 500.milliseconds) = Await.result(f, timeout)

  val processRunner = new ProcessRunner {
    def run(args: String*)(implicit ec: ExecutionContext) = executeProcess(args: _*)
  }

  behavior of "ProcessRunner"

  it should "run an external command successfully and capture its output" in {
    val stdout = "Output"
    await(processRunner.run("echo", stdout)) shouldBe stdout
  }

  it should "run an external command unsuccessfully and capture its output" in {
    val exitCode = 1
    val stdout = "Output"
    val stderr = "Error"

    val future = processRunner.run("/bin/sh", "-c", s"echo ${stdout}; echo ${stderr} 1>&2; exit ${exitCode}")

    the[ProcessRunningException] thrownBy await(future) shouldBe ProcessRunningException(exitCode, stdout, stderr)
  }
}
