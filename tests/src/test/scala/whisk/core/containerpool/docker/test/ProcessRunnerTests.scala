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

import akka.actor.ActorSystem
import common.WskActorSystem

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
    val exitCode = 1
    val stdout = "Output"
    val stderr = "Error"

    val future = processRunner.run(Seq("/bin/sh", "-c", s"echo ${stdout}; echo ${stderr} 1>&2; exit ${exitCode}"))

    the[ProcessRunningException] thrownBy await(future) shouldBe ProcessRunningException(exitCode, stdout, stderr)
  }

  it should "terminate an external command after the specified timeout is reached" in {
    val future = processRunner.run(Seq("sleep", "1"), 100.milliseconds)
    val exception = the[ProcessRunningException] thrownBy await(future)
    exception.exitCode shouldBe 143
  }
}
