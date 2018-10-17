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

import akka.actor.ActorSystem
import akka.http.scaladsl.model.ContentType
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.core.entity.DocId
import org.apache.openwhisk.spi.Spi

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

trait AttachmentStoreProvider extends Spi {
  def makeStore[D <: DocumentSerializer: ClassTag]()(implicit actorSystem: ActorSystem,
                                                     logging: Logging,
                                                     materializer: ActorMaterializer): AttachmentStore
}

case class AttachResult(digest: String, length: Long)

trait AttachmentStore {

  /** Identifies the store type */
  protected[core] def scheme: String

  /** Execution context for futures */
  protected[core] implicit val executionContext: ExecutionContext

  /**
   * Attaches a "file" of type `contentType` to an existing document.
   */
  protected[core] def attach(doc: DocId, name: String, contentType: ContentType, docStream: Source[ByteString, _])(
    implicit transid: TransactionId): Future[AttachResult]

  /**
   * Retrieves a saved attachment, streaming it into the provided Sink.
   */
  protected[core] def readAttachment[T](doc: DocId, name: String, sink: Sink[ByteString, Future[T]])(
    implicit transid: TransactionId): Future[T]

  /**
   * Deletes all attachments linked to given document
   */
  protected[core] def deleteAttachments(doc: DocId)(implicit transid: TransactionId): Future[Boolean]

  /**
   * Deletes specific attachment.
   */
  protected[core] def deleteAttachment(doc: DocId, name: String)(implicit transid: TransactionId): Future[Boolean]

  /** Shut it down. After this invocation, every other call is invalid. */
  def shutdown(): Unit
}
