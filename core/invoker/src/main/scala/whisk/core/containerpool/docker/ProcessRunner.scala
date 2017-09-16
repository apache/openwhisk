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

import java.nio.ByteBuffer

import akka.util.ByteString
import com.zaxxer.nuprocess.{NuAbstractProcessHandler, NuProcessBuilder}

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future, Promise}

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
  protected def executeProcess(args: String*)(implicit ec: ExecutionContext): Future[String] = {
    val promise = Promise[String]()
    val pb: NuProcessBuilder = new NuProcessBuilder(args.asJava)
    pb.setProcessListener(new NuAbstractProcessHandler {
      var out = ByteString.empty
      var err = ByteString.empty

      override def onExit(code: Int): Unit = code match {
        case 0 => promise.success(out.utf8String.trim)
        case _ => promise.failure(ProcessRunningException(code, out.utf8String.trim, err.utf8String.trim))
      }

      override def onStderr(buffer: ByteBuffer, closed: Boolean) = {
        err = err ++ ByteString.fromByteBuffer(buffer)
      }

      override def onStdout(buffer: ByteBuffer, closed: Boolean): Unit = {
        out = out ++ ByteString.fromByteBuffer(buffer)
      }
    })

    pb.start()
    promise.future
  }
}

case class ProcessRunningException(exitCode: Int, stdout: String, stderr: String)
    extends Exception(s"code: $exitCode, stdout: $stdout, stderr: $stderr")
