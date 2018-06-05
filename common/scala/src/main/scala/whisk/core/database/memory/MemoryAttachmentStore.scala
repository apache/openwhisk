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

package whisk.core.database.memory

import akka.actor.ActorSystem
import akka.http.scaladsl.model.ContentType
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.util.{ByteString, ByteStringBuilder}
import whisk.common.LoggingMarkers.{DATABASE_ATTS_DELETE, DATABASE_ATT_DELETE, DATABASE_ATT_GET, DATABASE_ATT_SAVE}
import whisk.common.{Logging, TransactionId}
import whisk.core.database.StoreUtils._
import whisk.core.database._
import whisk.core.entity.{DocId, DocInfo}

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

  override protected[core] def attach(
    doc: DocInfo,
    name: String,
    contentType: ContentType,
    docStream: Source[ByteString, _])(implicit transid: TransactionId): Future[DocInfo] = {
    checkDocState(doc)
    require(name != null, "name undefined")
    val start = transid.started(this, DATABASE_ATT_SAVE, s"[ATT_PUT] uploading attachment '$name' of document '$doc'")

    val f = docStream.runFold(new ByteStringBuilder)((builder, b) => builder ++= b)
    val g = f
      .map(b => attachments += (attachmentKey(doc.id, name) -> Attachment(b.result().compact)))
      .flatMap(_ => {
        transid
          .finished(this, start, s"[ATT_PUT] '$dbName' completed uploading attachment '$name' of document '$doc'")
        Future.successful(doc)
      })

    reportFailure(
      g,
      start,
      failure => s"[ATT_PUT] '$dbName' internal error, name: '$name', doc: '$doc', failure: '${failure.getMessage}'")
  }

  /**
   * Retrieves a saved attachment, streaming it into the provided Sink.
   */
  override protected[core] def readAttachment[T](doc: DocInfo, name: String, sink: Sink[ByteString, Future[T]])(
    implicit transid: TransactionId): Future[T] = {
    checkDocState(doc)
    require(name != null, "name undefined")

    val start =
      transid.started(this, DATABASE_ATT_GET, s"[ATT_GET] '$dbName' finding attachment '$name' of document '$doc'")

    val f = attachments.get(attachmentKey(doc.id, name)) match {
      case Some(Attachment(bytes)) =>
        val r = Source.single(bytes).toMat(sink)(Keep.right).run
        r.map(t => {
          transid.finished(this, start, s"[ATT_GET] '$dbName' completed: found attachment '$name' of document '$doc'")
          t
        })
      case None =>
        transid.finished(
          this,
          start,
          s"[ATT_GET] '$dbName', retrieving attachment '$name' of document '$doc'; not found.")
        Future.failed(NoDocumentException("Not found on 'readAttachment'."))
    }
    reportFailure(
      f,
      start,
      failure => s"[ATT_GET] '$dbName' internal error, name: '$name', doc: '$doc', failure: '${failure.getMessage}'")
  }

  override protected[core] def deleteAttachments(doc: DocInfo)(implicit transid: TransactionId): Future[Boolean] = {
    checkDocState(doc)
    val start = transid.started(this, DATABASE_ATTS_DELETE, s"[ATTS_DELETE] uploading attachment of document '$doc'")

    val prefix = doc.id.id + "/"
    attachments --= attachments.keySet.filter(_.startsWith(prefix))
    transid.finished(this, start, s"[ATTS_DELETE] completed: delete attachment of document '$doc'")
    Future.successful(true)
  }

  override protected[core] def deleteAttachment(doc: DocInfo, name: String)(
    implicit transid: TransactionId): Future[Boolean] = {
    checkDocState(doc)
    val start = transid.started(this, DATABASE_ATT_DELETE, s"[ATT_DELETE] uploading attachment of document '$doc'")
    attachments.remove(attachmentKey(doc.id, name))
    transid.finished(this, start, s"[ATT_DELETE] completed: delete attachment of document '$doc'")
    Future.successful(true)
  }

  private def checkDocState(doc: DocInfo): Unit = {
    require(doc != null, "doc undefined")
    require(doc.rev.rev != null, "doc revision must be specified")
  }

  private def attachmentKey(docId: DocId, name: String) = s"${docId.id}/$name"
}
