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

package org.apache.openwhisk.core.database

import java.security.MessageDigest

import akka.event.Logging.ErrorLevel
import akka.stream.SinkShape
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Keep, Sink}
import akka.util.ByteString
import com.google.common.base.Throwables
import spray.json.DefaultJsonProtocol._
import spray.json.{JsObject, JsValue, RootJsonFormat}
import org.apache.openwhisk.common.{Logging, StartMarker, TransactionId}
import org.apache.openwhisk.core.entity.{DocInfo, DocRevision, DocumentReader, WhiskDocument}

import scala.concurrent.{ExecutionContext, Future}

private[database] object StoreUtils {
  private val digestAlgo = "SHA-256"
  private val encodedAlgoName = digestAlgo.toLowerCase.replaceAllLiterally("-", "")

  def reportFailure[T](f: Future[T], start: StartMarker, failureMessage: Throwable => String)(
    implicit transid: TransactionId,
    logging: Logging,
    ec: ExecutionContext): Future[T] = {
    f.failed.foreach {
      case _: ArtifactStoreException => // These failures are intentional and shouldn't trigger the catcher.
      case x =>
        transid.failed(
          this,
          start,
          s"${failureMessage(x)} [${x.getClass.getSimpleName}]\n" + Throwables.getStackTraceAsString(x),
          ErrorLevel)
    }
    f
  }

  def checkDocHasRevision(doc: DocInfo): Unit = {
    require(doc != null, "doc undefined")
    require(doc.rev.rev != null, "doc revision must be specified")
  }

  def deserialize[A <: DocumentAbstraction, DocumentAbstraction](doc: DocInfo, js: JsObject)(
    implicit docReader: DocumentReader,
    ma: Manifest[A],
    jsonFormat: RootJsonFormat[DocumentAbstraction]): A = {
    val asFormat = try {
      docReader.read(ma, js)
    } catch {
      case _: Exception => jsonFormat.read(js)
    }

    if (asFormat.getClass != ma.runtimeClass) {
      throw DocumentTypeMismatchException(
        s"document type ${asFormat.getClass} did not match expected type ${ma.runtimeClass}.")
    }

    val deserialized = asFormat.asInstanceOf[A]

    val responseRev = js.fields("_rev").convertTo[String]
    if (doc.rev.rev != null && doc.rev.rev != responseRev) {
      throw DocumentRevisionMismatchException(
        s"Returned revision should match original argument ${doc.rev.rev} ${responseRev}")
    }
    // FIXME remove mutability from appropriate classes now that it is no longer required by GSON.
    deserialized.asInstanceOf[WhiskDocument].revision(DocRevision(responseRev))
  }

  def combinedSink[T](dest: Sink[ByteString, Future[T]])(
    implicit ec: ExecutionContext): Sink[ByteString, Future[AttachmentUploadResult[T]]] = {
    Sink.fromGraph(GraphDSL.createGraph(digestSink(), lengthSink(), dest)(combineResult) {
      implicit builder => (dgs, ls, dests) =>
        import GraphDSL.Implicits._

        val bcast = builder.add(Broadcast[ByteString](3))

        bcast ~> dgs.in
        bcast ~> ls.in
        bcast ~> dests.in

        SinkShape(bcast.in)
    })
  }

  def emptyDigest(): MessageDigest = MessageDigest.getInstance(digestAlgo)

  def encodeDigest(bytes: Array[Byte]): String = {
    val digest = bytes.map("%02x".format(_)).mkString
    s"$encodedAlgoName-$digest"
  }

  /**
   * Transforms a json object by adding and removing fields
   *
   * @param json base json object to transform
   * @param fieldsToAdd list of fields to add. If the value provided is `None` then it would be ignored
   * @param fieldsToRemove list of field names to remove
   * @return transformed json
   */
  def transform(json: JsObject,
                fieldsToAdd: Seq[(String, Option[JsValue])],
                fieldsToRemove: Seq[String] = Seq.empty): JsObject = {
    val fields = json.fields ++ fieldsToAdd.flatMap(f => f._2.map((f._1, _))) -- fieldsToRemove
    JsObject(fields)
  }

  private def combineResult[T](digest: Future[String], length: Future[Long], upload: Future[T])(
    implicit ec: ExecutionContext) = {
    for {
      d <- digest
      l <- length
      u <- upload
    } yield AttachmentUploadResult(d, l, u)
  }

  case class AttachmentUploadResult[T](digest: String, length: Long, uploadResult: T)

  private def digestSink(): Sink[ByteString, Future[String]] = {
    Flow[ByteString]
      .fold(emptyDigest())((digest, bytes) => { digest.update(bytes.toArray); digest })
      .map(md => encodeDigest(md.digest()))
      .toMat(Sink.head)(Keep.right)
  }

  private def lengthSink(): Sink[ByteString, Future[Long]] = {
    Sink.fold[Long, ByteString](0)((length, bytes) => length + bytes.size)
  }
}
