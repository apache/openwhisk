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
import akka.stream.scaladsl.{Concat, Sink, Source}
import akka.util.{ByteString, ByteStringBuilder}
import whisk.core.database.AttachmentInliner.MemScheme
import whisk.core.entity.ByteSize

import scala.collection.immutable
import scala.concurrent.Future

object AttachmentInliner {

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
trait AttachmentInliner {

  /** Materializer required for stream processing */
  protected[core] implicit val materializer: Materializer

  protected[database] def inlineAndTail(
    docStream: Source[ByteString, _]): Future[(immutable.Seq[Byte], Source[Byte, _])] = {
    docStream
      .mapConcat(_.seq)
      .prefixAndTail(maxInlineSize.toBytes.toInt)
      .runWith(Sink.head[(immutable.Seq[Byte], Source[Byte, _])])
  }

  protected[database] def uriOf(bytes: Seq[Byte], path: => String): Uri = {
    //For less than case its definitive that tail source would be empty
    //for equal case it cannot be determined if tail source is empty. Actual max inline size
    //would be inlineSize - 1
    if (bytes.size < maxInlineSize.toBytes) {
      Uri.from(scheme = MemScheme, path = encode(bytes))
    } else {
      Uri.from(scheme = attachmentScheme, path = path)
    }
  }

  /**
   * Constructs a combined source based on attachment content read so far and rest of unread content.
   * Emitted elements are up to `chunkSize` sized [[akka.util.ByteString]] elements.
   */
  protected[database] def combinedSource(inlinedBytes: immutable.Seq[Byte],
                                         tailSource: Source[Byte, _]): Source[ByteString, NotUsed] =
    Source
      .combine(Source(inlinedBytes), tailSource)(Concat[Byte])
      .batch[ByteStringBuilder](chunkSize.toBytes, b => { val bb = new ByteStringBuilder(); bb += b })((bb, b) =>
        bb += b)
      .map(_.result())

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
