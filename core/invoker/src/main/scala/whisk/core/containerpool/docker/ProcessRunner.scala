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

package whisk.core.containerpool.docker

import akka.actor.ActorSystem

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.blocking
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.sys.process._

trait ProcessRunner {

  /**
   * Runs the specified command with arguments asynchronously and
   * capture stdout as well as stderr.
   *
   * If not set to infinite, after timeout is reached the process is terminated.
   *
   * Be cautious with the execution context you pass because the command
   * is blocking.
   *
   * @param args command to be run including arguments
   * @param timeout maximum time the command is allowed to take
   * @return a future completing according to the command's exit code
   */
  protected def executeProcess(args: Seq[String], timeout: Duration)(implicit ec: ExecutionContext, as: ActorSystem) =
    Future(blocking {
      val out = new mutable.ListBuffer[String]
      val err = new mutable.ListBuffer[String]
      val process = args.run(ProcessLogger(o => out += o, e => err += e))

      val scheduled = timeout match {
        case t: FiniteDuration => Some(as.scheduler.scheduleOnce(t)(process.destroy()))
        case _                 => None
      }

      (ExitStatus(process.exitValue()), out.mkString("\n"), err.mkString("\n"), scheduled)
    }).flatMap {
      case (ExitStatus(0), stdout, _, scheduled) =>
        scheduled.foreach(_.cancel())
        Future.successful(stdout)
      case (exitStatus, stdout, stderr, scheduled) =>
        scheduled.foreach(_.cancel())
        timeout match {
          case t: FiniteDuration if exitStatus.terminatedBySIGTERM =>
            Future.failed(ProcessTimeoutException(timeout, exitStatus, stdout, stderr))
          case _ => Future.failed(ProcessRunningException(exitStatus, stdout, stderr))
        }
    }
}

case class ExitStatus(statusValue: Int) {

  // Based on The Open Group Base Specifications Issue 7, 2018 edition:
  // Shell & Utilities - Shell Command Language - 2.8.2 Exit Status for Commands
  // http://pubs.opengroup.org/onlinepubs/9699919799/utilities/V3_chap02.html#tag_18_08_02
  val STATUS_SUCCESSFULL = 0
  val STATUS_NOT_EXECUTABLE = 126
  val STATUS_NOT_FOUND = 127
  // When a command is stopped by a signal, the exit status is 128 + signal numer
  val STATUS_SIGNAL = 128

  // Based on The Open Group Base Specifications Issue 7, 2018 edition:
  // Shell & Utilities - Utilities - kill
  // http://pubs.opengroup.org/onlinepubs/9699919799/utilities/kill.html
  val SIGHUP = 1
  val SIGINT = 2
  val SIGQUIT = 3
  val SIGABRT = 6
  val SIGKILL = 9
  val SIGALRM = 14
  val SIGTERM = 15

  override def toString(): String = {
    def signalAsString(signal: Int): String = {
      signal match {
        case SIGHUP  => "SIGHUP"
        case SIGINT  => "SIGINT"
        case SIGQUIT => "SIGQUIT"
        case SIGABRT => "SIGABRT"
        case SIGKILL => "SIGKILL"
        case SIGALRM => "SIGALRM"
        case SIGTERM => "SIGTERM"
        case _       => signal.toString
      }
    }

    val detail = statusValue match {
      case STATUS_SUCCESSFULL                => "successful"
      case STATUS_NOT_EXECUTABLE             => "not executable"
      case STATUS_NOT_FOUND                  => "not found"
      case _ if statusValue >= STATUS_SIGNAL => "terminated by signal " + signalAsString(statusValue - STATUS_SIGNAL)
      case _                                 => "unsuccessful"
    }

    s"$statusValue ($detail)"
  }

  val successful = statusValue == STATUS_SUCCESSFULL
  val terminatedBySIGTERM = (statusValue - STATUS_SIGNAL) == SIGTERM
}

case class ProcessRunningException(exitStatus: ExitStatus, stdout: String, stderr: String)
    extends Exception(s"code: $exitStatus, stdout: $stdout, stderr: $stderr")

case class ProcessTimeoutException(timeout: Duration, exitStatus: ExitStatus, stdout: String, stderr: String)
    extends Exception(
      s"command was terminated, took longer than $timeout, code: $exitStatus, stdout: $stdout, stderr: $stderr")
