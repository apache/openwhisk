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
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Source
import akka.util.ByteString

import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.containerpool.Container
import org.apache.openwhisk.core.entity.{ActivationLogs, ExecutableWhiskAction, Identity, WhiskActivation}
import org.apache.openwhisk.http.Messages
import org.apache.openwhisk.core.database.UserContext

import scala.concurrent.{ExecutionContext, Future}

import spray.json._

/**
 * Represents a single log line as read from a docker log
 */
protected[core] case class LogLine(time: String, stream: String, log: String) {
  def toFormattedString = f"$time%-30s $stream: ${log.trim}"
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
  implicit val mat: ActorMaterializer = ActorMaterializer()(system)

  /* "json-file" is the log-driver that writes out to file */
  override val containerParameters = Map("--log-driver" -> Set("json-file"))

  /* As logs are already part of the activation record, just return that bit of it */
  override def fetchLogs(activation: WhiskActivation, context: UserContext): Future[ActivationLogs] =
    Future.successful(activation.logs)

  /**
   * Obtains the container's stdout and stderr output.
   *
   * In case of a timed out activation do not wait for a sentinel to appear but instead
   * collect the log as is and add a message to the log that data might be missing
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
                          action: ExecutableWhiskAction,
                          isTimedoutActivation: Boolean): Source[ByteString, Any] = {

    // wait for a sentinel only if no container (developer) error occurred to avoid
    // that log collection continues if the action code still logs after timeout
    val sentinel = action.exec.sentinelledLogs && !isTimedoutActivation
    val logs = container.logs(action.limits.logs.asMegaBytes, sentinel)(transid)
    val logsWithPossibleError = if (isTimedoutActivation) {
      logs.concat(
        Source.single(ByteString(LogLine(Instant.now.toString, "stderr", Messages.logFailure).toJson.compactPrint)))
    } else logs
    logsWithPossibleError
  }

  override def collectLogs(transid: TransactionId,
                           user: Identity,
                           activation: WhiskActivation,
                           container: Container,
                           action: ExecutableWhiskAction): Future[ActivationLogs] = {

    val isTimedoutActivation = activation.isTimedoutActivation
    val logs = logStream(transid, container, action, isTimedoutActivation)

    logs
      .via(DockerToActivationLogStore.toFormattedString)
      .runWith(Sink.seq)
      .flatMap { seq =>
        val possibleErrors = Set(Messages.logFailure, Messages.truncateLogs(action.limits.logs.asMegaBytes))
        val errored = isTimedoutActivation || seq.lastOption.exists(last => possibleErrors.exists(last.contains))
        val logs = ActivationLogs(seq.toVector)
        if (!errored) {
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
