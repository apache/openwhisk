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

import akka.http.scaladsl.model.ContentType
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import whisk.common.TransactionId
import whisk.core.entity.DocInfo

import scala.concurrent.{ExecutionContext, Future}

trait AttachmentStore {

  /** Execution context for futures */
  protected[core] implicit val executionContext: ExecutionContext

  /**
   * Attaches a "file" of type `contentType` to an existing document. The revision for the document must be set.
   */
  protected[core] def attach(doc: DocInfo, name: String, contentType: ContentType, docStream: Source[ByteString, _])(
    implicit transid: TransactionId): Future[DocInfo]

  /**
   * Retrieves a saved attachment, streaming it into the provided Sink.
   */
  protected[core] def readAttachment[T](doc: DocInfo, name: String, sink: Sink[ByteString, Future[T]])(
    implicit transid: TransactionId): Future[(ContentType, T)]

  /**
   * Deletes all attachments linked to given document
   */
  protected[core] def deleteAttachments(doc: DocInfo)(implicit transid: TransactionId): Future[Boolean]
}
