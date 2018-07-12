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

package whisk.core.containerpool.logging

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.model.HttpRequest
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Flow
import akka.util.ByteString

import whisk.common.TransactionId
import whisk.core.containerpool.Container
import whisk.core.entity.{ActivationLogs, ExecutableWhiskAction, Identity, WhiskActivation}
import whisk.http.Messages

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
  override def fetchLogs(user: Identity, activation: WhiskActivation, request: HttpRequest): Future[ActivationLogs] =
    Future.successful(activation.logs)

  override def collectLogs(transid: TransactionId,
                           user: Identity,
                           activation: WhiskActivation,
                           container: Container,
                           action: ExecutableWhiskAction): Future[ActivationLogs] = {

    container
      .logs(action.limits.logs.asMegaBytes, action.exec.sentinelledLogs)(transid)
      .via(DockerToActivationLogStore.toFormattedString)
      .runWith(Sink.seq)
      .flatMap { seq =>
        val possibleErrors = Set(Messages.logFailure, Messages.truncateLogs(action.limits.logs.asMegaBytes))
        val errored = seq.lastOption.exists(last => possibleErrors.exists(last.contains))
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
