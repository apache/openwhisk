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

import java.nio.charset.StandardCharsets
import scala.util.{Failure, Success, Try}
import spray.json._
import spray.json.DefaultJsonProtocol
import whisk.common.{Logging, TransactionId}
import whisk.core.entity._
import whisk.core.entity.size.{SizeInt, SizeString}
import scala.collection.mutable.Buffer
import whisk.http.Messages

/**
 * Represents a single log line as read from a docker log
 */
protected[core] case class LogLine(time: String, stream: String, log: String) {
  def toFormattedString = f"$time%-30s $stream: ${log.trim}"
  def dropRight(maxBytes: ByteSize) = {
    val bytes = log.getBytes(StandardCharsets.UTF_8).dropRight(maxBytes.toBytes.toInt)
    LogLine(time, stream, new String(bytes, StandardCharsets.UTF_8))
  }
}

protected[core] object LogLine extends DefaultJsonProtocol {
  implicit val serdes = jsonFormat3(LogLine.apply)
}

protected[core] object DockerActionLogDriver {
  // The action proxies inserts this line in the logs at the end of each activation for stdout/stderr
  val LOG_ACTIVATION_SENTINEL = "XXX_THE_END_OF_A_WHISK_ACTIVATION_XXX"
}

protected[core] trait DockerActionLogDriver {

  /**
   * Given the JSON driver's raw output of a docker container, convert it into our own
   * JSON format. If asked, check for sentinel markers (which are not included in the output).
   *
   * Only parses and returns so much logs to fit into the LogLimit passed.
   *
   * @param logMsgs raw String read from a JSON log-driver written file
   * @param requireSentinel determines if the processor should wait for a sentinel to appear
   * @param limit the limit to apply to the log size
   *
   * @return Tuple containing (isComplete, isTruncated, logs)
   */
  protected def processJsonDriverLogContents(logMsgs: String, requireSentinel: Boolean, limit: ByteSize)(
    implicit transid: TransactionId,
    logging: Logging): (Boolean, Boolean, Vector[String]) = {

    var hasOut = false
    var hasErr = false
    var truncated = false
    var bytesSoFar = 0.B
    val logLines = Buffer[String]()
    val lines = logMsgs.lines

    // read whiles bytesSoFar <= limit when requireSentinel to try and grab sentinel if they exist to indicate completion
    while (lines.hasNext && ((requireSentinel && bytesSoFar <= limit) || bytesSoFar < limit)) {
      // if line does not parse, there's an error in the container log driver
      Try(lines.next().parseJson.convertTo[LogLine]) match {
        case Success(t) =>
          // if sentinels are expected, do not account for their size, otherwise, all bytes are accounted for
          if (requireSentinel && t.log.trim != DockerActionLogDriver.LOG_ACTIVATION_SENTINEL || !requireSentinel) {
            // ignore empty log lines
            if (t.log.nonEmpty) {
              bytesSoFar += t.log.sizeInBytes
              if (bytesSoFar <= limit) {
                logLines.append(t.toFormattedString)
              } else {
                // chop off the right most bytes that overflow
                val chopped = t.dropRight(bytesSoFar - limit)
                if (chopped.log.nonEmpty) {
                  logLines.append(chopped.toFormattedString)
                }
                truncated = true
              }
            }
          } else if (requireSentinel) {
            // there may be more than one sentinel in stdout/stderr (as logs may leak across activations
            // if for example there log limit was exceeded in one activation and the container was reused
            // to run the same action again (this is considered a feature - otherwise, must drain the logs
            // or destroy the container as if it errored)
            if (t.stream == "stdout") {
              hasOut = true
            } else if (t.stream == "stderr") {
              hasErr = true
            }
          }

        case Failure(t) =>
          // Drop lines that did not parse to JSON objects.
          // However, should not happen since we are using the json log driver.
          logging.error(this, s"log line skipped/did not parse: $t")
      }
    }

    if (lines.hasNext) truncated = true
    if (truncated) logLines.append(Messages.truncateLogs(limit))

    ((hasOut && hasErr) || !requireSentinel, truncated, logLines.toVector)
  }
}
