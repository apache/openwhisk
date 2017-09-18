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

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.blocking
import scala.sys.process._

trait ProcessRunner {

  /**
   * Runs the specified command with arguments asynchronously and
   * capture stdout as well as stderr.
   *
   * Be cautious with the execution context you pass because the command
   * is blocking.
   *
   * @param args command to be run including arguments
   * @return a future completing according to the command's exit code
   */
  protected def executeProcess(args: String*)(implicit ec: ExecutionContext) =
    Future(blocking {
      val out = new mutable.ListBuffer[String]
      val err = new mutable.ListBuffer[String]
      val exitCode = args ! ProcessLogger(o => out += o, e => err += e)

      (exitCode, out.mkString("\n"), err.mkString("\n"))
    }).flatMap {
      case (0, stdout, _) =>
        Future.successful(stdout)
      case (code, stdout, stderr) =>
        Future.failed(ProcessRunningException(code, stdout, stderr))
    }
}

case class ProcessRunningException(exitCode: Int, stdout: String, stderr: String)
    extends Exception(s"code: $exitCode, stdout: $stdout, stderr: $stderr")
