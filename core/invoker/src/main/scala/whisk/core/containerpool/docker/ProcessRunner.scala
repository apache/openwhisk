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

package whisk.core.containerpool.docker

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.blocking
import scala.sys.process._
import java.util.concurrent.TimeoutException
import scala.concurrent.duration._
import scala.concurrent.Await
import scala.util.Try
import scala.util.Failure

trait ProcessRunner {

    /**
     * Runs the specified command with arguments asynchronously and
     * captures stdout as well as stderr. If a timeout is specified,
     * aborts the running command if execution exceeds timeout.
     *
     * Be cautious with the execution context you pass because the command
     * is blocking.
     *
     * @param args command to be run including arguments
     * @param timeout
     *        optional wait time after which the running command is aborted
     *        if not specified or set to [[scala.concurrent.duration.Duration.Inf Duration.Inf]],
     *        the command runs without time limit
     *        a negative timeout requires immediate command execution which
     *        only makes sense in test scenarios
     * @return a future completing with the commands stdout if successful or a failure otherwise
     */
    protected[docker] def executeProcess(args: Seq[String], timeout: Duration = Duration.Inf)(implicit ec: ExecutionContext): Future[String] = {
        val out = mutable.ListBuffer[String]()
        val err = mutable.ListBuffer[String]()

        Future(args.run(ProcessLogger(o => out += o, e => err += e))).flatMap { process =>
            blocking {
                if (timeout.isFinite) {
                    val exitCode = Try(Await.result(Future(blocking(process.exitValue())), timeout)).recoverWith {
                        case e: TimeoutException =>
                            process.destroy()
                            Failure(ProcessTimeoutException(s"Executed command did not finish within ${timeout.toCoarsest}: '${args.mkString(" ")}'"))
                    }
                    Future.fromTry(exitCode)
                } else {
                    Future.successful(process.exitValue())
                }
            }
        }.flatMap {
            case 0 =>
                Future.successful(out.mkString("\n"))
            case exitCode =>
                Future.failed(ProcessRunningException(exitCode, out.mkString("\n"), err.mkString("\n")))
        }
    }
}

case class ProcessRunningException(exitCode: Int, stdout: String, stderr: String) extends Exception(s"code: $exitCode, stdout: $stdout, stderr: $stderr")
case class ProcessTimeoutException(message: String) extends Exception(message)
