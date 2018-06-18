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

import java.util.Base64

import akka.NotUsed
import akka.http.scaladsl.model.Uri
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import whisk.core.database.AttachmentSupport.MemScheme
import whisk.core.entity.ByteSize

import scala.concurrent.{ExecutionContext, Future}

object AttachmentSupport {

  /**
   * Scheme name for attachments which are inlined
   */
  val MemScheme: String = "mem"
}

case class InliningConfig(maxInlineSize: ByteSize, chunkSize: ByteSize)

/**
 * Provides support for inlining small attachments. Inlined attachment contents are encoded as part of attachment
 * name itself.
 */
trait AttachmentSupport {

  /** Materializer required for stream processing */
  protected[core] implicit val materializer: Materializer

  protected[database] def inlineAndTail(docStream: Source[ByteString, _],
                                        previousPrefix: ByteString = ByteString.empty)(
    implicit ec: ExecutionContext): Future[Either[ByteString, Source[ByteString, _]]] = {
    docStream.prefixAndTail(1).runWith(Sink.head).flatMap {
      case (prefix, tail) =>
        prefix.headOption.fold {
          Future.successful[Either[ByteString, Source[ByteString, _]]](Left(previousPrefix))
        } { prefix =>
          val completePrefix = previousPrefix ++ prefix
          if (completePrefix.size < maxInlineSize.toBytes) {
            inlineAndTail(tail, completePrefix)
          } else {
            Future.successful(Right(tail.prepend(Source.single(completePrefix))))
          }
        }
    }
  }

  protected[database] def uriOf(bytesOrSource: Either[ByteString, Source[ByteString, _]], path: => String): Uri = {
    bytesOrSource match {
      case Left(bytes) => Uri.from(scheme = MemScheme, path = encode(bytes))
      case Right(_)    => Uri.from(scheme = attachmentScheme, path = path)
    }
  }

  /**
   * Constructs a source from inlined attachment contents
   */
  protected[database] def memorySource(uri: Uri): Source[ByteString, NotUsed] = {
    require(uri.scheme == MemScheme, s"URI $uri scheme is not $MemScheme")
    Source.single(ByteString(decode(uri)))
  }

  protected[database] def isInlined(uri: Uri): Boolean = uri.scheme == MemScheme

  protected[database] def digest(bytes: TraversableOnce[Byte]): String = {
    val digester = StoreUtils.emptyDigest()
    digester.update(bytes.toArray)
    StoreUtils.encodeDigest(digester.digest())
  }

  /**
   * Attachments having size less than this would be inlined
   */
  def maxInlineSize: ByteSize = inliningConfig.maxInlineSize

  def chunkSize: ByteSize = inliningConfig.chunkSize

  protected def inliningConfig: InliningConfig

  protected def attachmentScheme: String

  private def encode(bytes: Seq[Byte]): String = {
    Base64.getUrlEncoder.encodeToString(bytes.toArray)
  }

  private def decode(uri: Uri): Array[Byte] = {
    Base64.getUrlDecoder.decode(uri.path.toString())
  }
}
