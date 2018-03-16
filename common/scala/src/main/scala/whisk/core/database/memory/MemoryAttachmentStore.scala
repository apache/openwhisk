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
 *
 */

package whisk.core.database.memory

import akka.actor.ActorSystem
import akka.event.Logging.ErrorLevel
import akka.http.scaladsl.model.ContentType
import akka.stream.{ActorMaterializer, OverflowStrategy, QueueOfferResult}
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.util.{ByteString, ByteStringBuilder}
import whisk.common.{Logging, StartMarker, TransactionId}
import whisk.common.LoggingMarkers.DATABASE_ATT_GET
import whisk.common.LoggingMarkers.DATABASE_ATT_SAVE
import whisk.common.LoggingMarkers.DATABASE_ATT_DELETE
import whisk.core.database._
import whisk.core.entity.{DocId, DocInfo}

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.reflect.ClassTag

object MemoryAttachmentStoreProvider extends AttachmentStoreProvider {
  override def makeStore[D <: DocumentSerializer: ClassTag]()(implicit actorSystem: ActorSystem,
                                                              logging: Logging,
                                                              materializer: ActorMaterializer): AttachmentStore =
    new MemoryAttachmentStore(implicitly[ClassTag[D]].runtimeClass.getSimpleName.toLowerCase)
}

class MemoryAttachmentStore(dbName: String)(implicit system: ActorSystem,
                                            logging: Logging,
                                            materializer: ActorMaterializer)
    extends AttachmentStore {

  override protected[core] implicit val executionContext: ExecutionContext = system.dispatcher

  private sealed trait Action {
    def doc: DocInfo
  }
  private case class AddAction(doc: DocInfo, name: String, attachment: Attachment) extends Action
  private case class DelAction(doc: DocInfo) extends Action
  private case class Attachment(bytes: ByteString, contentType: ContentType)

  @volatile
  private var attachments: Map[String, Attachment] = Map()

  private val actionQueue =
    Source
      .queue(16 * 1024, OverflowStrategy.dropNew)
      .toMat(Sink.foreach[(Action, Promise[DocInfo])](a => process(a._1, a._2)))(Keep.left)
      .run

  override protected[core] def attach(
    doc: DocInfo,
    name: String,
    contentType: ContentType,
    docStream: Source[ByteString, _])(implicit transid: TransactionId): Future[DocInfo] = {

    val start = transid.started(this, DATABASE_ATT_SAVE, s"[ATT_PUT] uploading attachment '$name' of document '$doc'")

    checkDocState(doc)

    val f = docStream.runFold(new ByteStringBuilder)((builder, b) => builder ++= b)
    val g = f
      .map(b => AddAction(doc, name, Attachment(b.result().compact, contentType)))
      .map(enqueue(_))
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
    implicit transid: TransactionId): Future[(ContentType, T)] = {
    val start =
      transid.started(this, DATABASE_ATT_GET, s"[ATT_GET] '$dbName' finding attachment '$name' of document '$doc'")

    val f = attachments.get(attachmentKey(doc.id, name)) match {
      case Some(Attachment(bytes, contentType)) =>
        val r = Source.single(bytes).toMat(sink)(Keep.right).run
        r.map(t => {
          transid.finished(this, start, s"[ATT_GET] '$dbName' completed: found attachment '$name' of document '$doc'")
          (contentType, t)
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
    val start = transid.started(this, DATABASE_ATT_DELETE, s"[ATT_DELETE] uploading attachment of document '$doc'")
    checkDocState(doc)

    val f = enqueue(DelAction(doc)).flatMap(_ => Future.successful(true))
    f.onSuccess {
      case _ =>
        transid.finished(this, start, s"[ATT_DELETE] completed: delete attachment of document '$doc'")
    }

    reportFailure(
      f,
      start,
      failure => s"[ATT_DELETE] '$dbName' internal error, doc: '$doc', failure: '${failure.getMessage}'")
  }

  private def checkDocState(doc: DocInfo): Unit = {
    require(doc != null, "doc undefined")
    require(doc.rev.rev != null, "doc revision must be specified")
  }

  private def process(action: Action, p: Promise[DocInfo]): Unit = {
    action match {
      case DelAction(doc) =>
        val prefix = doc.id.id + "/"
        attachments = attachments -- attachments.keySet.filter(_.startsWith(prefix))
        p.success(doc)
      case AddAction(doc, name, attachment) =>
        attachments = attachments + (attachmentKey(doc.id, name) -> attachment)
        p.success(doc)
    }
  }

  private def enqueue(action: Action): Future[DocInfo] = {
    val promise = Promise[DocInfo]

    actionQueue.offer(action -> promise).flatMap {
      case QueueOfferResult.Enqueued =>
        promise.future

      case QueueOfferResult.Dropped =>
        Future.failed(new Exception("Memory request queue is full."))

      case QueueOfferResult.QueueClosed =>
        Future.failed(new Exception("Memory request queue was closed."))

      case QueueOfferResult.Failure(f) =>
        Future.failed(f)
    }
  }

  private def attachmentKey(docId: DocId, name: String) = s"${docId.id}/$name"

  private def reportFailure[T](f: Future[T], start: StartMarker, failureMessage: Throwable => String)(
    implicit transid: TransactionId): Future[T] = {
    f.onFailure({
      case _: ArtifactStoreException => // These failures are intentional and shouldn't trigger the catcher.
      case x                         => transid.failed(this, start, failureMessage(x), ErrorLevel)
    })
    f
  }
}
