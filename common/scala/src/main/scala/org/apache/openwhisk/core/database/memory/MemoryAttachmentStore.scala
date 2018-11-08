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

package org.apache.openwhisk.core.database.memory

import akka.actor.ActorSystem
import akka.http.scaladsl.model.ContentType
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.util.{ByteString, ByteStringBuilder}
import org.apache.openwhisk.common.LoggingMarkers.{
  DATABASE_ATTS_DELETE,
  DATABASE_ATT_DELETE,
  DATABASE_ATT_GET,
  DATABASE_ATT_SAVE
}
import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.core.database.StoreUtils._
import org.apache.openwhisk.core.database._
import org.apache.openwhisk.core.entity.DocId

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

object MemoryAttachmentStoreProvider extends AttachmentStoreProvider {
  override def makeStore[D <: DocumentSerializer: ClassTag]()(implicit actorSystem: ActorSystem,
                                                              logging: Logging,
                                                              materializer: ActorMaterializer): AttachmentStore =
    new MemoryAttachmentStore(implicitly[ClassTag[D]].runtimeClass.getSimpleName.toLowerCase)
}

/**
 * Basic in-memory AttachmentStore implementation. Useful for testing.
 */
class MemoryAttachmentStore(dbName: String)(implicit system: ActorSystem,
                                            logging: Logging,
                                            materializer: ActorMaterializer)
    extends AttachmentStore {

  override protected[core] implicit val executionContext: ExecutionContext = system.dispatcher

  private case class Attachment(bytes: ByteString)

  private val attachments = new TrieMap[String, Attachment]
  private var closed = false

  override val scheme = "mems"

  override protected[core] def attach(
    docId: DocId,
    name: String,
    contentType: ContentType,
    docStream: Source[ByteString, _])(implicit transid: TransactionId): Future[AttachResult] = {
    require(name != null, "name undefined")
    val start =
      transid.started(this, DATABASE_ATT_SAVE, s"[ATT_PUT] uploading attachment '$name' of document 'id: $docId'")

    val uploadSink = Sink.fold[ByteStringBuilder, ByteString](new ByteStringBuilder)((builder, b) => builder ++= b)

    val f = docStream.runWith(combinedSink(uploadSink))

    val g = f.map { r =>
      attachments += (attachmentKey(docId, name) -> Attachment(r.uploadResult.result().compact))
      transid
        .finished(this, start, s"[ATT_PUT] '$dbName' completed uploading attachment '$name' of document '$docId'")
      AttachResult(r.digest, r.length)
    }

    reportFailure(
      g,
      start,
      failure =>
        s"[ATT_PUT] '$dbName' internal error, name: '$name', doc: 'id: $docId', failure: '${failure.getMessage}'")
  }

  /**
   * Retrieves a saved attachment, streaming it into the provided Sink.
   */
  override protected[core] def readAttachment[T](docId: DocId, name: String, sink: Sink[ByteString, Future[T]])(
    implicit transid: TransactionId): Future[T] = {

    val start =
      transid.started(
        this,
        DATABASE_ATT_GET,
        s"[ATT_GET] '$dbName' finding attachment '$name' of document 'id: $docId'")

    val f = attachments.get(attachmentKey(docId, name)) match {
      case Some(Attachment(bytes)) =>
        val r = Source.single(bytes).toMat(sink)(Keep.right).run
        r.map(t => {
          transid.finished(this, start, s"[ATT_GET] '$dbName' completed: found attachment '$name' of document '$docId'")
          t
        })
      case None =>
        transid.finished(
          this,
          start,
          s"[ATT_GET] '$dbName', retrieving attachment '$name' of document '$docId'; not found.")
        Future.failed(NoDocumentException("Not found on 'readAttachment'."))
    }
    reportFailure(
      f,
      start,
      failure => s"[ATT_GET] '$dbName' internal error, name: '$name', doc: '$docId', failure: '${failure.getMessage}'")
  }

  override protected[core] def deleteAttachments(docId: DocId)(implicit transid: TransactionId): Future[Boolean] = {
    val start = transid.started(this, DATABASE_ATTS_DELETE, s"[ATTS_DELETE] uploading attachment of document '$docId'")

    val prefix = docId + "/"
    attachments --= attachments.keySet.filter(_.startsWith(prefix))
    transid.finished(this, start, s"[ATTS_DELETE] completed: delete attachment of document '$docId'")
    Future.successful(true)
  }

  override protected[core] def deleteAttachment(docId: DocId, name: String)(
    implicit transid: TransactionId): Future[Boolean] = {
    val start = transid.started(this, DATABASE_ATT_DELETE, s"[ATT_DELETE] uploading attachment of document '$docId'")
    attachments.remove(attachmentKey(docId, name))
    transid.finished(this, start, s"[ATT_DELETE] completed: delete attachment of document '$docId'")
    Future.successful(true)
  }

  def attachmentCount: Int = attachments.size

  def isClosed = closed

  override def shutdown(): Unit = {
    closed = true
  }

  private def attachmentKey(docId: DocId, name: String) = s"${docId.id}/$name"
}
