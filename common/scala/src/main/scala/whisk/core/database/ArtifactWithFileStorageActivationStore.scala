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

package whisk.core.database

import java.time.Instant
import java.nio.file.{Files, Path, Paths}
import java.nio.file.attribute.PosixFilePermission.{
  GROUP_READ,
  GROUP_WRITE,
  OTHERS_READ,
  OTHERS_WRITE,
  OWNER_READ,
  OWNER_WRITE
}
import java.util.EnumSet

import akka.actor.ActorSystem
import akka.stream.alpakka.file.scaladsl.LogRotatorSink
import akka.stream.scaladsl.{Flow, MergeHub, RestartSink, Sink, Source}
import akka.stream._
import akka.util.ByteString

import spray.json._

import whisk.common.{Logging, TransactionId}
import whisk.core.containerpool.logging.ElasticSearchJsonProtocol._
import whisk.core.entity._
import whisk.core.entity.size._
import whisk.core.ConfigKeys

import scala.concurrent.Future
import scala.concurrent.duration._

import pureconfig.loadConfigOrThrow

case class ArtifactWithFileStorageActivationStoreConfig(logFilePrefix: String,
                                                        userIdField: String,
                                                        writeLogsToArtifact: Boolean,
                                                        writeResultToArtifact: Boolean,
                                                        additionalJson: String)

class ArtifactWithFileStorageActivationStore(actorSystem: ActorSystem,
                                             actorMaterializer: ActorMaterializer,
                                             logging: Logging)
    extends ArtifactActivationStore(actorSystem, actorMaterializer, logging) {

  implicit val materializer = actorMaterializer

  private val config =
    loadConfigOrThrow[ArtifactWithFileStorageActivationStoreConfig](ConfigKeys.activationStoreWithFileStorage)

  private val additionalJson = config.additionalJson.parseJson.asJsObject.fields
  private val destinationDirectory: Path = Paths.get("logs")
  private val bufferSize = 100.MB
  private val perms = EnumSet.of(OWNER_READ, OWNER_WRITE, GROUP_READ, GROUP_WRITE, OTHERS_READ, OTHERS_WRITE)
  private val writeToFile: Sink[ByteString, _] = MergeHub
    .source[ByteString]
    .batchWeighted(bufferSize.toBytes, _.length, identity)(_ ++ _)
    .to(RestartSink.withBackoff(minBackoff = 1.seconds, maxBackoff = 60.seconds, randomFactor = 0.2) { () =>
      LogRotatorSink(() => {
        val maxSize = bufferSize.toBytes
        var bytesRead = maxSize
        element =>
          {
            val size = element.size
            if (bytesRead + size > maxSize) {
              bytesRead = size
              val logFilePath = destinationDirectory.resolve(s"${config.logFilePrefix}-${Instant.now.toEpochMilli}.log")
              logging.info(this, s"Rotating log file to '$logFilePath'")
              try {
                Files.createFile(logFilePath)
                Files.setPosixFilePermissions(logFilePath, perms)
              } catch {
                case t: Throwable =>
                  logging.error(this, s"Couldn't create user log file '$t'")
                  throw t
              }
              Some(logFilePath)
            } else {
              bytesRead += size
              None
            }
          }
      })
    })
    .run()

  private def transcribeLogs(activation: WhiskActivation, additionalFields: Map[String, JsValue]) =
    activation.logs.logs.map { log =>
      val line = JsObject(
        Map("message" -> log.toJson) ++ Map("activationId" -> activation.activationId.toJson) ++ additionalFields).toJson

      ByteString(s"${line.compactPrint}\n")
    }

  private def transcribeActivation(activation: WhiskActivation, additionalFields: Map[String, JsValue]) = {
    val message = Map(
      "message" -> s"Activation record '${activation.activationId}' for entity '${activation.name}'".toJson)
    val annotations = activation.annotations.toJsObject.fields
    val addFields = annotations ++ message ++ additionalFields
    val removeFields = Seq("logs", "annotations")
    val line = activation.metadata.toExtendedJson(removeFields, addFields).toJson

    ByteString(s"${line.compactPrint}\n")
  }

  def storeToFile(activation: WhiskActivation, context: UserContext) = {
    val additionalFields = Map(config.userIdField -> context.user.namespace.uuid.toJson) ++ additionalJson
    val transcribedLogs = transcribeLogs(activation, additionalFields)
    val transcribedActivation = transcribeActivation(activation, additionalFields)

    // Write each log line to file and then write the activation metadata
    Source
      .fromIterator(() => transcribedLogs.toIterator)
      .runWith(Flow[ByteString].concat(Source.single(transcribedActivation)).to(writeToFile))
  }

  override def store(activation: WhiskActivation, context: UserContext)(
    implicit transid: TransactionId,
    notifier: Option[CacheChangeNotification]): Future[DocInfo] = {
    storeToFile(activation, context)

    if (config.writeResultToArtifact && config.writeLogsToArtifact) {
      super.store(activation, context)
    } else if (config.writeResultToArtifact) {
      super.store(activation.withoutLogs, context)
    } else if (config.writeLogsToArtifact) {
      super.store(activation.withoutResult, context)
    } else {
      super.store(activation.withoutLogsOrResult, context)
    }
  }

}

object ArtifactWithFileStorageActivationStoreProvider extends ActivationStoreProvider {
  override def instance(actorSystem: ActorSystem, actorMaterializer: ActorMaterializer, logging: Logging) =
    new ArtifactWithFileStorageActivationStore(actorSystem, actorMaterializer, logging)
}
