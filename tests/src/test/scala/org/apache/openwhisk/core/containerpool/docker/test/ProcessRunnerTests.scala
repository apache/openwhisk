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
import common.WskActorSystem

import scala.concurrent.Future
import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner

import org.apache.openwhisk.core.containerpool.docker._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.concurrent.Await
import org.scalatest.Matchers

import scala.language.reflectiveCalls // Needed to invoke run() method of structural ProcessRunner extension

@RunWith(classOf[JUnitRunner])
class ProcessRunnerTests extends FlatSpec with Matchers with WskActorSystem {

  def await[A](f: Future[A], timeout: FiniteDuration = 2.seconds) = Await.result(f, timeout)

  val processRunner = new ProcessRunner {
    def run(args: Seq[String], timeout: FiniteDuration = 100.milliseconds)(implicit ec: ExecutionContext,
                                                                           as: ActorSystem) =
      executeProcess(args, timeout)(ec, as)
  }

  behavior of "ProcessRunner"

  it should "run an external command successfully and capture its output" in {
    val stdout = "Output"
    await(processRunner.run(Seq("echo", stdout))) shouldBe stdout
  }

  it should "run an external command unsuccessfully and capture its output" in {
    val exitStatus = ExitStatus(1)
    val stdout = "Output"
    val stderr = "Error"

    val future =
      processRunner.run(Seq("/bin/sh", "-c", s"echo ${stdout}; echo ${stderr} 1>&2; exit ${exitStatus.statusValue}"))

    val exception = the[ProcessRunningException] thrownBy await(future)
    exception shouldBe ProcessUnsuccessfulException(exitStatus, stdout, stderr)
    exception.getMessage should startWith("info: command was unsuccessful")
  }

  it should "terminate an external command after the specified timeout is reached" in {
    val timeout = 100.milliseconds
    // Run "sleep" command for 1 second and make sure that stdout and stderr are dropped
    val future = processRunner.run(Seq("/bin/sh", "-c", "sleep 1 1>/dev/null 2>/dev/null"), timeout)
    val exception = the[ProcessTimeoutException] thrownBy await(future)
    exception shouldBe ProcessTimeoutException(timeout, ExitStatus(143), "", "")
    exception.getMessage should startWith(s"info: command was terminated, took longer than $timeout")
  }

  behavior of "ExitStatus"

  it should "provide a proper textual representation" in {
    Seq[(Int, String)](
      (0, "successful"),
      (1, "unsuccessful"),
      (125, "unsuccessful"),
      (126, "not executable"),
      (127, "not found"),
      (128, "terminated by signal 0"),
      (129, "terminated by signal SIGHUP"),
      (130, "terminated by signal SIGINT"),
      (131, "terminated by signal SIGQUIT"),
      (134, "terminated by signal SIGABRT"),
      (137, "terminated by signal SIGKILL"),
      (142, "terminated by signal SIGALRM"),
      (143, "terminated by signal SIGTERM"),
      (144, "terminated by signal 16")).foreach {
      case (statusValue, detailText) =>
        ExitStatus(statusValue).toString shouldBe s"$statusValue ($detailText)"
    }
  }

  it should "properly classify exit status" in {
    withClue("Exit status 0 is successful - ") { ExitStatus(0).successful shouldBe true }
    withClue("Exit status 1 is not successful - ") { ExitStatus(1).successful shouldBe false }
    withClue("Exit status 143 means terminated by SIGTERM - ") { ExitStatus(143).terminatedBySIGTERM shouldBe true }
  }
}
