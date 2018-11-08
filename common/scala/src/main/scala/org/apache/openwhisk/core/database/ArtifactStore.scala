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

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import akka.http.scaladsl.model._
import akka.stream.scaladsl._
import akka.util.ByteString
import spray.json.JsObject
import org.apache.openwhisk.common.Logging
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.entity.Attachments.Attached
import org.apache.openwhisk.core.entity.DocInfo

abstract class StaleParameter(val value: Option[String])

object StaleParameter {
  case object Ok extends StaleParameter(Some("ok"))
  case object UpdateAfter extends StaleParameter(Some("update_after"))
  case object No extends StaleParameter(None)
}

/** Basic client to put and delete artifacts in a data store. */
trait ArtifactStore[DocumentAbstraction] {

  /** Execution context for futures */
  protected[core] implicit val executionContext: ExecutionContext

  implicit val logging: Logging

  /**
   * Puts (saves) document to database using a future.
   * If the operation is successful, the future completes with DocId else an appropriate exception.
   *
   * @param d the document to put in the database
   * @param transid the transaction id for logging
   * @return a future that completes either with DocId
   */
  protected[database] def put(d: DocumentAbstraction)(implicit transid: TransactionId): Future[DocInfo]

  /**
   * Deletes document from database using a future.
   * If the operation is successful, the future completes with true.
   *
   * @param doc the document info for the record to delete (must contain valid id and rev)
   * @param transid the transaction id for logging
   * @return a future that completes true iff the document is deleted, else future is failed
   */
  protected[database] def del(doc: DocInfo)(implicit transid: TransactionId): Future[Boolean]

  /**
   * Gets document from database by id using a future.
   * If the operation is successful, the future completes with the requested document if it exists.
   *
   * @param doc the document info for the record to get (must contain valid id and rev)
   * @param attachmentHandler function to update the attachment details in document
   * @param transid the transaction id for logging
   * @param ma manifest for A to determine its runtime type, required by some db APIs
   * @return a future that completes either with DocumentAbstraction if the document exists and is deserializable into desired type
   */
  protected[database] def get[A <: DocumentAbstraction](
    doc: DocInfo,
    attachmentHandler: Option[(A, Attached) => A] = None)(implicit transid: TransactionId, ma: Manifest[A]): Future[A]

  /**
   * Gets all documents from database view that match a start key, up to an end key, using a future.
   * If the operation is successful, the promise completes with List[View] with zero or more documents.
   *
   * @param table the name of the table to query
   * @param startKey to starting key to query the view for
   * @param endKey to starting key to query the view for
   * @param skip the number of record to skip (for pagination)
   * @param limit the maximum number of records matching the key to return, iff > 0
   * @param includeDocs include full documents matching query iff true (shall not be used with reduce)
   * @param descending reverse results iff true
   * @param reduce apply reduction associated with query to the result iff true
   * @param stale a flag to permit a stale view result to be returned
   * @param transid the transaction id for logging
   * @return a future that completes with List[JsObject] of all documents from view between start and end key (list may be empty)
   */
  protected[core] def query(table: String,
                            startKey: List[Any],
                            endKey: List[Any],
                            skip: Int,
                            limit: Int,
                            includeDocs: Boolean,
                            descending: Boolean,
                            reduce: Boolean,
                            stale: StaleParameter)(implicit transid: TransactionId): Future[List[JsObject]]

  /**
   * Counts all documents from database view that match a start key, up to an end key, using a future.
   * If the operation is successful, the promise completes with Long.
   *
   * @param table the name of the table to query
   * @param startKey to starting key to query the view for
   * @param endKey to starting key to query the view for
   * @param skip the number of record to skip (for pagination)
   * @param stale a flag to permit a stale view result to be returned
   * @param transid the transaction id for logging
   * @return a future that completes with Long that is the number of documents from view between start and end key (count may be zero)
   */
  protected[core] def count(table: String, startKey: List[Any], endKey: List[Any], skip: Int, stale: StaleParameter)(
    implicit transid: TransactionId): Future[Long]

  /**
   * Attaches a "file" of type `contentType` to an existing document. The revision for the document must be set.
   *
   * @param update - function to transform the document with new attachment details
   * @param oldAttachment Optional old document instance for the update scenario. It would be used to determine
   *                      the existing attachment details.
   */
  protected[database] def putAndAttach[A <: DocumentAbstraction](
    d: A,
    update: (A, Attached) => A,
    contentType: ContentType,
    docStream: Source[ByteString, _],
    oldAttachment: Option[Attached])(implicit transid: TransactionId): Future[(DocInfo, Attached)]

  /**
   * Retrieves a saved attachment, streaming it into the provided Sink.
   */
  protected[core] def readAttachment[T](doc: DocInfo, attached: Attached, sink: Sink[ByteString, Future[T]])(
    implicit transid: TransactionId): Future[T]

  /**
   * Deletes all attachments linked to given document
   */
  protected[core] def deleteAttachments[T](doc: DocInfo)(implicit transid: TransactionId): Future[Boolean]

  /** Shut it down. After this invocation, every other call is invalid. */
  def shutdown(): Unit
}
