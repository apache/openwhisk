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

import java.io.File

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

import whisk.core.containerpool.docker.ProcessRunner
import whisk.core.containerpool.docker.ProcessRunningException
import whisk.core.containerpool.docker.ProcessTimeoutException

@RunWith(classOf[JUnitRunner])
class ProcessRunnerTests extends FlatSpec with Matchers {

    // Timeouts chosen long enough for tested commands to complete timely
    val waitTimeout = 500.milliseconds
    val cmdTimeout = 2 * waitTimeout

    def await[A](f: Future[A], timeout: FiniteDuration = waitTimeout) = Await.result(f, timeout)

    val processRunner = new ProcessRunner {
    }

    behavior of "ProcessRunner"

    it should "run an external command successfully and capture its output" in {
        val stdout = "Output"

        Seq(Duration.Inf, cmdTimeout).foreach { timeout =>
            await(processRunner.executeProcess(Seq("echo", stdout), timeout)) shouldBe stdout
        }
    }

    it should "run an external command unsuccessfully without timeout and capture its output" in {
        val exitCode = 1
        val stdout = "Output"
        val stderr = "Error"

        Seq(Duration.Inf, cmdTimeout).foreach { timeout =>
            val future = processRunner.executeProcess(Seq("/bin/sh", "-c", s"echo ${stdout}; echo ${stderr} 1>&2; exit ${exitCode}"), timeout)

            the[ProcessRunningException] thrownBy await(future) shouldBe ProcessRunningException(exitCode, stdout, stderr)
        }
    }

    it should "time out" in {
        val longCmd = Seq("sleep", "1") // sleep 1 second
        val timeout = 1.millisecond
        val expectedException = ProcessTimeoutException(s"Executed command did not finish within ${timeout.toCoarsest}: '${longCmd.mkString(" ")}'")

        val future = processRunner.executeProcess(longCmd, timeout)

        the[ProcessTimeoutException] thrownBy await(future) shouldBe expectedException
    }

    it should "deal with a non-existing command" in {
        an[Exception] should be thrownBy await(processRunner.executeProcess(Seq("/does-not-exist")))
    }

    it should "deal with non-readable command" in {
        val file = File.createTempFile(this.getClass.getName, null)
        try {
            val cmd = file.getCanonicalPath
            file.setExecutable(true, false) // enable execute for everybody
            file.setReadable(false, false) // disable read for everybody
            an[Exception] should be thrownBy await(processRunner.executeProcess(Seq(cmd)))
        } finally {
            file.delete()
        }
    }

    it should "deal with non-executable command" in {
        val file = File.createTempFile(this.getClass.getName, null)
        try {
            val cmd = file.getCanonicalPath
            file.setExecutable(false, false) // disable execute for everybody
            file.setReadable(true, false) // enable read for everybody
            an[Exception] should be thrownBy await(processRunner.executeProcess(Seq(cmd)))
        } finally {
            file.delete()
        }
    }
}
