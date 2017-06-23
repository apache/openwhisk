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

package whisk.core.container

import akka.event.Logging.ErrorLevel
import whisk.common.{ Logging, LoggingMarkers, SimpleExec, TransactionId }

object RuncUtils {

    def list()(implicit transid: TransactionId, logging: Logging): (Int, String) = {
        runRuncCmd(false, Seq("list"))
    }

    def pause(id: ContainerIdentifier)(implicit transid: TransactionId, logging: Logging): (Int, String) = {
        runRuncCmd(false, Seq("pause", id.toString))
    }

    def resume(id: ContainerIdentifier)(implicit transid: TransactionId, logging: Logging): (Int, String) = {
        runRuncCmd(false, Seq("resume", id.toString))
    }

    /**
     * Synchronously runs the given runc command returning stdout if successful.
     */
    def runRuncCmd(skipLogError: Boolean, args: Seq[String])(implicit transid: TransactionId, logging: Logging): (Int, String) = {
        val start = transid.started(this, LoggingMarkers.INVOKER_RUNC_CMD(args(0)))
        try {
            val fullCmd = getRuncCmd() ++ args

            val (stdout, stderr, exitCode) = SimpleExec.syncRunCmd(fullCmd)

            if (exitCode == 0) {
                transid.finished(this, start)
                (exitCode, stdout.trim)
            } else {
                if (!skipLogError) {
                    transid.failed(this, start, s"stdout:\n$stdout\nstderr:\n$stderr", ErrorLevel)
                } else {
                    transid.failed(this, start)
                }
                (exitCode, (stdout + stderr).trim)
            }
        } catch {
            case t: Throwable =>
                val errorMsg = "error: " + t.getMessage
                transid.failed(this, start, errorMsg, ErrorLevel)
                (-1, errorMsg)
        }
    }

    def isSuccessful(result : (Int, String)) : Boolean =
        result match {
            case (0, _) => true
            case _ => false
        }

    /*
     *  Any global flags are added here.
     */
    private def getRuncCmd(): Seq[String] = {
        val runcBin = "/usr/bin/docker-runc"
        Seq(runcBin)
    }

}
