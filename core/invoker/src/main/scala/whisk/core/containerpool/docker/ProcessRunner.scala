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
   * If not set to infinite, after timeout is reached the process is killed.
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

      (process.exitValue(), out.mkString("\n"), err.mkString("\n"), scheduled)
    }).flatMap {
      case (0, stdout, _, scheduled) =>
        scheduled.foreach(_.cancel())
        Future.successful(stdout)
      case (code, stdout, stderr, scheduled) =>
        scheduled.foreach(_.cancel())
        Future.failed(ProcessRunningException(code, stdout, stderr))
    }

}

case class ProcessRunningException(exitCode: Int, stdout: String, stderr: String)
    extends Exception(s"code: $exitCode ${if (exitCode == 143) "(killed)" else ""}, stdout: $stdout, stderr: $stderr")
