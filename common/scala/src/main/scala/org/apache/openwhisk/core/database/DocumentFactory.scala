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

import java.io.InputStream
import java.io.OutputStream

import scala.concurrent.{Future, Promise}
import akka.http.scaladsl.model.ContentType
import akka.stream.IOResult
import akka.stream.scaladsl.StreamConverters
import spray.json.JsObject
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.entity.Attachments.Attached
import org.apache.openwhisk.core.entity.CacheKey
import org.apache.openwhisk.core.entity.DocId
import org.apache.openwhisk.core.entity.DocInfo
import org.apache.openwhisk.core.entity.DocRevision

/**
 * An interface for modifying the revision number on a document. Hides the details of
 * the revision to some extent while providing a marker interface for operations that
 * need to update the revision on a document.
 */
protected[core] trait DocumentRevisionProvider {

  /** Gets the document id and revision as an instance of DocInfo. */
  protected[database] def docinfo: DocInfo

  /**
   * Sets the revision number when a document is deserialized from datastore. The
   * _rev is an opaque value, needed to update the record in the datastore. It is
   * not part of the core properties of this class. It is not required when saving
   * a new instance of this type to the datastore.
   */
  protected[core] final def revision[W](r: DocRevision): W = {
    _rev = r
    this.asInstanceOf[W]
  }

  protected[core] def rev = _rev

  private var _rev: DocRevision = DocRevision.empty
}

/**
 * A common trait for all records that are serialized into raw documents for
 * the datastore, where the document id is a generated unique identifier.
 */
trait DocumentSerializer {

  /**
   * A JSON view including the document metadata, for writing to the datastore.
   *
   * @return JsObject
   */
  def toDocumentRecord: JsObject
}

/**
 * A common trait for all records that are deserialized from raw documents in the datastore
 *
 * The type parameter W represents the "whisk" type, the document abstraction to
 * use in core components. The trait is invariant in W
 * but the get permits a datastore of its super type so that a single datastore client
 * may be used for multiple types (because the types are stored in the same database for example).
 */
trait DocumentFactory[W <: DocumentRevisionProvider] extends MultipleReadersSingleWriterCache[W, DocInfo] {

  /**
   * Puts a record of type W in the datastore.
   *
   * The type parameters for the database are bounded from below to allow gets from a database that
   * contains several different but related types (for example entities are stored in the same database
   * and share common super types EntityRecord and WhiskEntity.
   *
   * @param db the datastore client to fetch entity from
   * @param doc the entity to store
   * @param transid the transaction id for logging
   * @param notifier an optional callback when cache changes
   * @param old an optional old document in case of update
   * @return Future[DocInfo] with completion to DocInfo containing the save document id and revision
   */
  def put[Wsuper >: W](db: ArtifactStore[Wsuper], doc: W, old: Option[W])(
    implicit transid: TransactionId,
    notifier: Option[CacheChangeNotification]): Future[DocInfo] = {
    implicit val logger = db.logging
    implicit val ec = db.executionContext
    cacheUpdate(doc, CacheKey(doc), db.put(doc) map { newDocInfo =>
      doc.revision[W](newDocInfo.rev)
      doc.docinfo
    })
  }

  def putAndAttach[Wsuper >: W](db: ArtifactStore[Wsuper],
                                doc: W,
                                update: (W, Attached) => W,
                                contentType: ContentType,
                                bytes: InputStream,
                                oldAttachment: Option[Attached],
                                postProcess: Option[W => W] = None)(
    implicit transid: TransactionId,
    notifier: Option[CacheChangeNotification]): Future[DocInfo] = {
    implicit val logger = db.logging
    implicit val ec = db.executionContext

    val key = CacheKey(doc)
    val src = StreamConverters.fromInputStream(() => bytes)

    val p = Promise[W]
    cacheUpdate(p.future, key, db.putAndAttach[W](doc, update, contentType, src, oldAttachment) map {
      case (newDocInfo, attached) =>
        val newDoc = update(doc, attached)
        val cacheDoc = postProcess map { _(newDoc) } getOrElse newDoc
        cacheDoc.revision[W](newDocInfo.rev)
        p.success(cacheDoc)
        newDocInfo
    })
  }

  def del[Wsuper >: W](db: ArtifactStore[Wsuper], doc: DocInfo)(
    implicit transid: TransactionId,
    notifier: Option[CacheChangeNotification]): Future[Boolean] = {
    implicit val logger = db.logging
    implicit val ec = db.executionContext

    val key = CacheKey(doc.id.asDocInfo)
    cacheInvalidate(key, db.del(doc))
  }

  /**
   * Fetches a raw record of type R from the datastore by its id (and revision if given)
   * and converts it to Success(W) or Failure(Throwable) if there is an error fetching
   * the record or deserializing it.
   *
   * The type parameters for the database are bounded from below to allow gets from a database that
   * contains several different but related types (for example entities are stored in the same database
   * and share common super types EntityRecord and WhiskEntity.
   *
   * @param db the datastore client to fetch entity from
   * @param doc the entity document information (must contain a valid id)
   * @param rev the document revision (optional)
   * @param fromCache will only query cache if true (defaults to collection settings)
   * @param transid the transaction id for logging
   * @param mw a manifest for W (hint to compiler to preserve type R for runtime)
   * @return Future[W] with completion to Success(W), or Failure(Throwable) if the raw record cannot be converted into W
   */
  def get[Wsuper >: W](
    db: ArtifactStore[Wsuper],
    doc: DocId,
    rev: DocRevision = DocRevision.empty,
    fromCache: Boolean = cacheEnabled)(implicit transid: TransactionId, mw: Manifest[W]): Future[W] = {
    implicit val logger = db.logging
    implicit val ec = db.executionContext
    val key = doc.asDocInfo(rev)
    cacheLookup(CacheKey(key), db.get[W](key, None), fromCache)
  }

  /**
   *  Fetches document along with attachment. `postProcess` would be used to process the fetched document
   *  before adding it to cache. This ensures that for documents having attachment the cache is updated only
   *  post fetch of the attachment
   */
  protected def getWithAttachment[Wsuper >: W](
    db: ArtifactStore[Wsuper],
    doc: DocId,
    rev: DocRevision = DocRevision.empty,
    fromCache: Boolean,
    attachmentHandler: (W, Attached) => W,
    postProcess: W => Future[W])(implicit transid: TransactionId, mw: Manifest[W]): Future[W] = {
    implicit val logger = db.logging
    implicit val ec = db.executionContext
    val key = doc.asDocInfo(rev)
    cacheLookup(CacheKey(key), db.get[W](key, Some(attachmentHandler)).flatMap(postProcess), fromCache)
  }

  protected def getAttachment[Wsuper >: W](
    db: ArtifactStore[Wsuper],
    doc: W,
    attached: Attached,
    outputStream: OutputStream,
    postProcess: Option[W => W] = None)(implicit transid: TransactionId, mw: Manifest[W]): Future[W] = {
    implicit val ec = db.executionContext
    implicit val notifier: Option[CacheChangeNotification] = None
    implicit val logger = db.logging

    val docInfo = doc.docinfo
    val key = CacheKey(docInfo)
    val sink = StreamConverters.fromOutputStream(() => outputStream)

    db.readAttachment[IOResult](docInfo, attached, sink).map { _ =>
      val cacheDoc = postProcess.map(_(doc)).getOrElse(doc)

      cacheUpdate(cacheDoc, key, Future.successful(docInfo)) map { newDocInfo =>
        cacheDoc.revision[W](newDocInfo.rev)
      }
      cacheDoc
    }
  }

  def deleteAttachments[Wsuper >: W](db: ArtifactStore[Wsuper], doc: DocInfo)(
    implicit transid: TransactionId): Future[Boolean] = {
    implicit val ec = db.executionContext
    db.deleteAttachments(doc)
  }
}
