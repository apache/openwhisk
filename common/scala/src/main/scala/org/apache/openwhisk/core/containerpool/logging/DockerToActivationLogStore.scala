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

package org.apache.openwhisk.core.containerpool.logging

import java.time.Instant

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Source
import akka.util.ByteString
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.containerpool.Container
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.http.Messages
import org.apache.openwhisk.core.database.UserContext

import scala.concurrent.{ExecutionContext, Future}
import spray.json._

/**
 * Represents a single log line as read from a docker log
 */
protected[core] case class LogLine(time: String, stream: String, log: String) {
  def toFormattedString = f"$time%-30s $stream: ${log.stripLineEnd}"
}

protected[core] object LogLine extends DefaultJsonProtocol {
  implicit val serdes = jsonFormat3(LogLine.apply)
}

object DockerToActivationLogStore {

  /** Transforms chunked JsObjects into formatted strings */
  val toFormattedString: Flow[ByteString, String, NotUsed] =
    Flow[ByteString].map(_.utf8String.parseJson.convertTo[LogLine].toFormattedString)
}

/**
 * Docker based implementation of a LogStore.
 *
 * Relies on docker's implementation details with regards to the JSON log-driver. When using the JSON log-driver
 * docker writes stdout/stderr to a JSON formatted file which is read by this store. Logs are written in the
 * activation record itself.
 */
class DockerToActivationLogStore(system: ActorSystem) extends LogStore {
  implicit val ec: ExecutionContext = system.dispatcher
  implicit val actorSystem: ActorSystem = system

  /* "json-file" is the log-driver that writes out to file */
  override val containerParameters = Map("--log-driver" -> Set("json-file"))

  /* As logs are already part of the activation record, just return that bit of it */
  override def fetchLogs(namespace: String,
                         activationId: ActivationId,
                         start: Option[Instant],
                         end: Option[Instant],
                         activationLogs: Option[ActivationLogs],
                         context: UserContext): Future[ActivationLogs] =
    activationLogs match {
      case Some(logs) => Future.successful(logs)
      case None       => Future.failed(new RuntimeException(s"Activation logs not available for activation ${activationId}"))
    }

  /**
   * Obtains the container's stdout and stderr output.
   *
   * Managed action runtimes are expected to produce sentinels on developer errors during
   * init and run. For certain developer errors like process abortion due to unhandled errors
   * or memory limit exhaustion, the action runtime will likely not be able to produce sentinels.
   *
   * In addition, there are situations where user actions (un)intentionally cause a developer error
   * in a managed action runtime and prevent the production of sentinels. In that case, log file
   * reading may continue endlessly.
   *
   * For these reasons, do not wait for sentinels to appear in log output when activations end up
   * in a developer error. It is expected that sentinels are filtered in container.logs() even
   * if they are not waited for.
   *
   * In case of a developer error, append a warning message to the logs that data might be missing.
   *
   * TODO: instead of just appending a warning message when a developer error occurs, we should
   *       have an out-of-band error handling that injects such messages later on.
   *
   * @param transid transaction id
   * @param container container to obtain the log from
   * @param action action that defines the log limit
   * @param isTimedoutActivation is activation timed out
   *
   * @return a vector of Strings with log lines in our own JSON format
   */
  protected def logStream(transid: TransactionId,
                          container: Container,
                          logLimit: LogLimit,
                          sentinelledLogs: Boolean,
                          isDeveloperError: Boolean): Source[ByteString, Any] = {

    // Wait for a sentinel only if no container (developer) error occurred to avoid
    // that log collection continues if the action code still logs after developer error.
    val waitForSentinel = sentinelledLogs && !isDeveloperError
    val logs = container.logs(logLimit.asMegaBytes, waitForSentinel)(transid)
    val logsWithPossibleError = if (isDeveloperError) {
      logs.concat(
        Source.single(
          ByteString(LogLine(Instant.now.toString, "stderr", Messages.logWarningDeveloperError).toJson.compactPrint)))
    } else logs
    logsWithPossibleError
  }

  /**
   * Determine whether the passed activation log had a log collecting error or not.
   * It is expected that the log collecting stream appends a message from a well known
   * set of error messages if log collecting failed.
   *
   * If the activation failed due to a developer error, an additional error message is appended.
   * In that case, the second last message indicates whether there was a log collecting error AND
   * the last message MUST be the additional error message mentioned above.
   *
   * TODO: this function needs to deal with different combinations of error / warning messages that
   *       were appended to / injected into the log collecting stream.
   *       Instead, we should have an out-of-band error handling that does not use log messages to
   *       detect error conditions but detects errors and appends error / warning messages in
   *       a different way.
   *
   * @param actLogs the activation logs to check
   * @param logLimit the log limit applying to the activation
   * @param isDeveloperError did activation fail due to developer error?
   * @return true if log collecting failed, false otherwise
   */
  protected def isLogCollectingError(actLogs: ActivationLogs,
                                     logLimit: LogLimit,
                                     isDeveloperError: Boolean): Boolean = {
    val logs = actLogs.logs
    val logCollectingErrorMessages = Set(Messages.logFailure, Messages.truncateLogs(logLimit.asMegaBytes))
    val lastLine: Option[String] = logs.lastOption
    val secondLastLine: Option[String] = logs.takeRight(2).dropRight(1).lastOption

    if (isDeveloperError) {
      // Developer error: the second last line indicates whether there was a log collecting error.
      val secondLastLineContainsLogCollectingError =
        secondLastLine.exists(line => logCollectingErrorMessages.exists(line.contains))

      // If a developer error occurred when initializing or running an action,
      // the last message in logs must be Messages.logWarningDeveloperError.
      // If not, this is a log collecting error.
      val lastLineContainsDeveloperError = lastLine.exists(line => line.contains(Messages.logWarningDeveloperError))

      secondLastLineContainsLogCollectingError || !lastLineContainsDeveloperError
    } else {
      // The last line indicates whether there was a log collecting error.
      lastLine.exists(line => logCollectingErrorMessages.exists(line.contains))
    }
  }

  override def collectLogs(transid: TransactionId,
                           user: Identity,
                           activation: WhiskActivation,
                           container: Container,
                           action: ExecutableWhiskAction): Future[ActivationLogs] = {

    val logLimit = action.limits.logs
    val isDeveloperError = activation.response.isContainerError // container error means developer error
    val logs = logStream(transid, container, logLimit, action.exec.sentinelledLogs, isDeveloperError)

    logs
      .via(DockerToActivationLogStore.toFormattedString)
      .runWith(Sink.seq)
      .flatMap { seq =>
        val logs = ActivationLogs(seq.toVector)
        if (!isLogCollectingError(logs, logLimit, isDeveloperError)) {
          Future.successful(logs)
        } else {
          Future.failed(LogCollectingException(logs))
        }
      }
  }
}

object DockerToActivationLogStoreProvider extends LogStoreProvider {
  override def instance(actorSystem: ActorSystem): LogStore = new DockerToActivationLogStore(actorSystem)
}
