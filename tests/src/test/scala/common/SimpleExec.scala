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

package common

import org.apache.openwhisk.common.{Logging, TransactionId}

import scala.sys.process.{stringSeqToProcess, ProcessLogger}

/**
 * Utility to exec processes
 */
object SimpleExec {

  /**
   * Runs a external process.
   *
   * @param cmd an array of String -- their concatenation is the command to exec
   * @return a triple of (stdout, stderr, exitcode) from running the command
   */
  def syncRunCmd(cmd: Seq[String])(implicit transid: TransactionId, logging: Logging): (String, String, Int) = {
    logging.info(this, s"Running command: ${cmd.mkString(" ")}")
    val pb = stringSeqToProcess(cmd)

    val outs = new StringBuilder()
    val errs = new StringBuilder()

    val exitCode = pb ! ProcessLogger(outStr => {
      outs.append(outStr)
      outs.append("\n")
    }, errStr => {
      errs.append(errStr)
      errs.append("\n")
    })

    logging.debug(this, s"Done running command: ${cmd.mkString(" ")}")

    def noLastNewLine(sb: StringBuilder) = {
      if (sb.isEmpty) "" else sb.substring(0, sb.size - 1)
    }

    (noLastNewLine(outs), noLastNewLine(errs), exitCode)
  }
}
