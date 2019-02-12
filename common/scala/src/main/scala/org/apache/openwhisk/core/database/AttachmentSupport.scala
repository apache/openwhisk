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

import java.util.Base64

import akka.NotUsed
import akka.http.scaladsl.model.{ContentType, Uri}
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import spray.json.DefaultJsonProtocol
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.database.AttachmentSupport.MemScheme
import org.apache.openwhisk.core.entity.Attachments.Attached
import org.apache.openwhisk.core.entity.{ByteSize, DocId, DocInfo, UUID}

import scala.concurrent.{ExecutionContext, Future}

object AttachmentSupport {

  /**
   * Scheme name for attachments which are inlined
   */
  val MemScheme: String = "mem"
}

case class InliningConfig(maxInlineSize: ByteSize)

/**
 * Provides support for inlining small attachments. Inlined attachment contents are encoded as part of attachment
 * name itself.
 */
trait AttachmentSupport[DocumentAbstraction <: DocumentSerializer] extends DefaultJsonProtocol {

  /** Materializer required for stream processing */
  protected[core] implicit val materializer: Materializer

  protected def executionContext: ExecutionContext

  /**
   * Attachment scheme name to use for non inlined attachments
   */
  protected def attachmentScheme: String

  protected def inliningConfig: InliningConfig

  /**
   * Attachments having size less than this would be inlined
   */
  def maxInlineSize: ByteSize = inliningConfig.maxInlineSize

  /**
   * See {{ ArtifactStore#put }}
   */
  protected[database] def put(d: DocumentAbstraction)(implicit transid: TransactionId): Future[DocInfo]

  /**
   * Given a ByteString source it determines if the source can be inlined or not by returning an
   * Either - Left(byteString) containing all the bytes from the source or Right(Source[ByteString, _])
   * if the source is large
   */
  protected[database] def inlineOrAttach(
    docStream: Source[ByteString, _],
    previousPrefix: ByteString = ByteString.empty): Future[Either[ByteString, Source[ByteString, _]]] = {
    implicit val ec = executionContext
    docStream.prefixAndTail(1).runWith(Sink.head).flatMap {
      case (Nil, _) =>
        Future.successful(Left(previousPrefix))
      case (Seq(prefix), tail) =>
        val completePrefix = previousPrefix ++ prefix
        if (completePrefix.size < maxInlineSize.toBytes) {
          inlineOrAttach(tail, completePrefix)
        } else {
          Future.successful(Right(tail.prepend(Source.single(completePrefix))))
        }
    }
  }

  /**
   * Constructs a URI for the attachment
   *
   * @param bytesOrSource either byteString or byteString source
   * @param path function to generate the attachment name for non inlined case
   * @return constructed uri. In case of inlined attachment the uri contains base64 encoded inlined attachment content
   */
  protected[database] def uriOf(bytesOrSource: Either[ByteString, Source[ByteString, _]], path: => String): Uri = {
    bytesOrSource match {
      case Left(bytes) => Uri.from(scheme = MemScheme, path = encode(bytes))
      case Right(_)    => uriFrom(scheme = attachmentScheme, path = path)
    }
  }

  //Not using Uri.from due to https://github.com/akka/akka-http/issues/2080
  protected[database] def uriFrom(scheme: String, path: String): Uri = Uri(s"$scheme:$path")

  /**
   * Constructs a source from inlined attachment contents
   */
  protected[database] def memorySource(uri: Uri): Source[ByteString, NotUsed] = {
    require(uri.scheme == MemScheme, s"URI $uri scheme is not $MemScheme")
    Source.single(ByteString(decode(uri)))
  }

  protected[database] def isInlined(uri: Uri): Boolean = uri.scheme == MemScheme

  /**
   * Computes digest for passed bytes as hex encoded string
   */
  protected[database] def digest(bytes: TraversableOnce[Byte]): String = {
    val digester = StoreUtils.emptyDigest()
    digester.update(bytes.toArray)
    StoreUtils.encodeDigest(digester.digest())
  }

  /**
   * Attaches the passed source content to  an {{ AttachmentStore }}
   *
   * @param doc document with attachment
   * @param update function to update the `Attached` state with attachment metadata
   * @param contentType contentType of the attachment
   * @param docStream attachment source
   * @param oldAttachment old attachment in case of update. Required for deleting the old attachment
   * @param attachmentStore attachmentStore where attachment needs to be stored
   *
   * @return a tuple of updated document info and attachment metadata
   */
  protected[database] def attachToExternalStore[A <: DocumentAbstraction](
    doc: A,
    update: (A, Attached) => A,
    contentType: ContentType,
    docStream: Source[ByteString, _],
    oldAttachment: Option[Attached],
    attachmentStore: AttachmentStore)(implicit transid: TransactionId): Future[(DocInfo, Attached)] = {

    val asJson = doc.toDocumentRecord
    val id = asJson.fields("_id").convertTo[String].trim

    implicit val ec = executionContext

    for {
      bytesOrSource <- inlineOrAttach(docStream)
      uri = uriOf(bytesOrSource, UUID().asString)
      attached <- {
        // Upload if cannot be inlined
        bytesOrSource match {
          case Left(bytes) =>
            Future.successful(Attached(uri.toString, contentType, Some(bytes.size), Some(digest(bytes))))
          case Right(source) =>
            attachmentStore
              .attach(DocId(id), uri.path.toString, contentType, source)
              .map(r => Attached(uri.toString, contentType, Some(r.length), Some(r.digest)))
        }
      }
      i1 <- put(update(doc, attached))

      //Remove old attachment if it was part of attachmentStore
      _ <- oldAttachment
        .map { old =>
          val oldUri = Uri(old.attachmentName)
          if (oldUri.scheme == attachmentStore.scheme) {
            attachmentStore.deleteAttachment(DocId(id), oldUri.path.toString)
          } else {
            Future.successful(true)
          }
        }
        .getOrElse(Future.successful(true))
    } yield (i1, attached)
  }

  private def encode(bytes: Seq[Byte]): String = {
    Base64.getUrlEncoder.encodeToString(bytes.toArray)
  }

  private def decode(uri: Uri): Array[Byte] = {
    Base64.getUrlDecoder.decode(uri.path.toString())
  }
}
