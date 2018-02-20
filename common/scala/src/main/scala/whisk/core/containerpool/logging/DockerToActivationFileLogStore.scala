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

import java.nio.file.{Path, Paths}
import java.time.Instant

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.model.HttpRequest
import akka.stream.alpakka.file.scaladsl.LogRotatorSink
import akka.stream.{ActorMaterializer, Graph, SinkShape, UniformFanOutShape}
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Keep, MergeHub, Sink, Source}
import akka.util.ByteString

import whisk.common.TransactionId
import whisk.core.containerpool.Container
import whisk.core.entity._
import whisk.core.entity.size._
import whisk.http.Messages

import spray.json._
import spray.json.DefaultJsonProtocol._

import scala.concurrent.{ExecutionContext, Future}

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
    extends LogDriverForwarderLogStore(system) {

  implicit val ec: ExecutionContext = system.dispatcher
  implicit val mat: ActorMaterializer = ActorMaterializer()(system)

  /* "json-file" is the log-driver that writes out to file */
  override val containerParameters = Map("--log-driver" -> Set("json-file"))

  /* As logs are already part of the activation record, just return that bit of it */
  override def fetchLogs(user: Identity, activation: WhiskActivation, request: HttpRequest): Future[ActivationLogs] = Future.successful(activation.logs)

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
  protected val writeToFile: Sink[ByteString, _] = MergeHub
    .source[ByteString]
    .batchWeighted(bufferSize.toBytes, _.length, identity)(_ ++ _)
    .to(LogRotatorSink(() => {
      val maxSize = bufferSize.toBytes
      var bytesRead = maxSize
      element =>
        {
          val size = element.size
          if (bytesRead + size > maxSize) {
            bytesRead = size
            Some(destinationDirectory.resolve(s"userlogs-${Instant.now.toEpochMilli}.log"))
          } else {
            bytesRead += size
            None
          }
        }
    }))
    .run()

  def forwardLogs(transid: TransactionId,
                  container: Container,
                  sizeLimit: ByteSize,
                  sentinelledLogs: Boolean,
                  additionalMetadata: Map[String, JsValue],
                  augmentedActivation: JsObject): Future[ActivationLogs] = {

    val logs = container.logs(sizeLimit, sentinelledLogs)(transid)

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
      val possibleErrors = Set(Messages.logFailure, Messages.truncateLogs(sizeLimit))
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

object DockerToActivationFileLogStoreProvider extends LogStoreProvider {
  override def logStore(actorSystem: ActorSystem): LogStore = new DockerToActivationFileLogStore(actorSystem)
}

object OwSink {

  /**
   * Combines two sinks into one sink using the given strategy. The materialized value is a Tuple2 of the materialized
   * values of either sink. Code basically copied from {@code Sink.combine}
   */
  def combine[T, U, M1, M2](first: Sink[U, M1], second: Sink[U, M2])(
    strategy: Int ⇒ Graph[UniformFanOutShape[T, U], NotUsed]): Sink[T, (M1, M2)] = {
    Sink.fromGraph(GraphDSL.create(first, second)((_, _)) { implicit b => (s1, s2) =>
      import GraphDSL.Implicits._
      val d = b.add(strategy(2))

      d ~> s1
      d ~> s2

      SinkShape(d.in)
    })
  }
}
