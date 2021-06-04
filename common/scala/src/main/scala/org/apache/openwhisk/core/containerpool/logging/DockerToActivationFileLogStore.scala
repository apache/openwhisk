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
import java.time.Instant

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.alpakka.file.scaladsl.LogRotatorSink
import akka.stream.{Graph, RestartSettings, SinkShape, UniformFanOutShape}
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Keep, MergeHub, RestartSink, Sink, Source}
import akka.util.ByteString

import org.apache.openwhisk.common.{AkkaLogging, TransactionId}
import org.apache.openwhisk.core.containerpool.Container
import org.apache.openwhisk.core.entity.{ActivationLogs, ExecutableWhiskAction, Identity, WhiskActivation}
import org.apache.openwhisk.core.entity.size._

import spray.json._
import spray.json.DefaultJsonProtocol._

import scala.concurrent.Future
import scala.concurrent.duration._

/**
 * Docker based implementation of a LogStore.
 *
 * Relies on docker's implementation details with regards to the JSON log-driver. When using the JSON log-driver
 * docker writes stdout/stderr to a JSON formatted file which is read by this store. Logs are written in the
 * activation record itself.
 *
 * Additionally writes logs to a separate file which can be processed by any backend service asynchronously.
 */
class DockerToActivationFileLogStore(system: ActorSystem, destinationDirectory: Path = Paths.get("logs"))
    extends DockerToActivationLogStore(system) {

  private val logging = new AkkaLogging(system.log)

  /**
   * End of an event as written to a file. Closes the json-object and also appends a newline.
   */
  private val eventEnd = ByteString("}\n")

  private def fieldsString(fields: Map[String, JsValue]) =
    fields
      .map {
        case (key, value) => s""""$key":${value.compactPrint}"""
      }
      .mkString(",")

  /**
   * Merges all file-writing streams into one globally buffered stream.
   *
   * This effectively decouples the time it takes to {@code collectLogs} from the time it takes to write the augmented
   * logging data to a file on the disk.
   *
   * All lines are written to a rotating sink, which will create a new file, appended with the creation timestamp,
   * once the defined limit is reached.
   */
  val bufferSize = 100.MB
  val perms = EnumSet.of(OWNER_READ, OWNER_WRITE, GROUP_READ, GROUP_WRITE, OTHERS_READ, OTHERS_WRITE)
  protected val writeToFile: Sink[ByteString, _] = MergeHub
    .source[ByteString]
    .batchWeighted(bufferSize.toBytes, _.length, identity)(_ ++ _)
    .to(RestartSink.withBackoff(RestartSettings(minBackoff = 1.seconds, maxBackoff = 60.seconds, randomFactor = 0.2)) {
      () =>
        LogRotatorSink(() => {
          val maxSize = bufferSize.toBytes
          var bytesRead = maxSize
          element =>
            {
              val size = element.size
              if (bytesRead + size > maxSize) {
                bytesRead = size
                val logFilePath = destinationDirectory.resolve(s"userlogs-${Instant.now.toEpochMilli}.log")
                logging.info(this, s"Rotating log file to '$logFilePath'")
                try {
                  Files.createFile(logFilePath)
                  Files.setPosixFilePermissions(logFilePath, perms)
                } catch {
                  case t: Throwable =>
                    logging.error(this, s"Couldn't create userlogs file: $t")
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

  override def collectLogs(transid: TransactionId,
                           user: Identity,
                           activation: WhiskActivation,
                           container: Container,
                           action: ExecutableWhiskAction): Future[ActivationLogs] = {

    val logLimit = action.limits.logs
    val isDeveloperError = activation.response.isContainerError // container error means developer error
    val logs = logStream(transid, container, logLimit, action.exec.sentinelledLogs, isDeveloperError)

    // Adding the userId field to every written record, so any background process can properly correlate.
    val userIdField = Map("namespaceId" -> user.namespace.uuid.toJson)

    val additionalMetadata = Map(
      "activationId" -> activation.activationId.asString.toJson,
      "action" -> action.fullyQualifiedName(false).asString.toJson,
      "namespace" -> user.namespace.name.asString.toJson) ++ userIdField

    val augmentedActivation = JsObject(activation.toJson.fields ++ userIdField)

    // Manually construct JSON fields to omit parsing the whole structure
    val metadata = ByteString("," + fieldsString(additionalMetadata))

    val toSeq = Flow[ByteString].via(DockerToActivationLogStore.toFormattedString).toMat(Sink.seq[String])(Keep.right)
    val toFile = Flow[ByteString]
    // As each element is a JSON-object, we know we can add the manually constructed fields to it by dropping
    // the closing "}", adding the fields and finally add "}\n" to the end again.
      .map(_.dropRight(1) ++ metadata ++ eventEnd)
      // As the last element of the stream, print the activation record.
      .concat(Source.single(ByteString(augmentedActivation.toJson.compactPrint + "\n")))
      .to(writeToFile)

    val combined = OwSink.combine(toSeq, toFile)(Broadcast[ByteString](_))

    logs.runWith(combined)._1.flatMap { seq =>
      val logs = ActivationLogs(seq.toVector)
      if (!isLogCollectingError(logs, logLimit, isDeveloperError)) {
        Future.successful(logs)
      } else {
        Future.failed(LogCollectingException(logs))
      }
    }
  }
}

object DockerToActivationFileLogStoreProvider extends LogStoreProvider {
  override def instance(actorSystem: ActorSystem): LogStore = new DockerToActivationFileLogStore(actorSystem)
}

object OwSink {

  /**
   * Combines two sinks into one sink using the given strategy. The materialized value is a Tuple2 of the materialized
   * values of either sink. Code basically copied from {@code Sink.combine}
   */
  def combine[T, U, M1, M2](first: Sink[U, M1], second: Sink[U, M2])(
    strategy: Int => Graph[UniformFanOutShape[T, U], NotUsed]): Sink[T, (M1, M2)] = {
    Sink.fromGraph(GraphDSL.create(first, second)((_, _)) { implicit b => (s1, s2) =>
      import GraphDSL.Implicits._
      val d = b.add(strategy(2))

      d ~> s1
      d ~> s2

      SinkShape(d.in)
    })
  }
}
