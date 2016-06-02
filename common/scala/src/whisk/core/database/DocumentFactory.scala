/*
 * Copyright 2015-2016 IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.core.database

import scala.concurrent.Future
import scala.util.Try
import whisk.common.TransactionId
import whisk.core.entity.DocInfo
import scala.util.Failure
import scala.util.Success
import whisk.common.Logging
import scala.concurrent.Promise
import whisk.core.entity.DocRevision
import whisk.core.entity.DocRevision
import scala.language.implicitConversions

import spray.json.JsObject

/**
 * A common trait for all records that are stored in the datastore requiring an _id field,
 * the unique document identifier. The _id field on a document must be defined (not null,
 * not empty) before the document is added to the datastore, otherwise the operation will
 * reject the document.
 *
 * The field is writable because a document retrieved from the datastore will write this
 * field. Reading from the datastore requires a nullary constructor and hence a field from
 * the datastore is declared as a var for that purpose.
 */
trait Document {
    /** The document id, this is the primary key for the document and must be unique. */
    protected var _id: String = null
    /** The document revision as determined by the datastore; an opaque value. */
    protected[database] var _rev: String = null

    /** Gets the document id and revision as an instance of DocInfo. */
    protected[database] def docinfo: DocInfo

    /**
     * Checks if the document has a valid revision set, in which case
     * this is an update operation.
     *
     * @return true iff document has a valid revision
     */
    protected[database] final def update: Boolean = _rev != null

    /**
     * Confirms the document has a valid id set.
     *
     * @return true iff document has a valid id
     * @throws IllegalArgumentException iff document does not have a valid id
     */
    @throws[IllegalArgumentException]
    protected[database] final def confirmId: Boolean = {
        require(_id != null, "document id undefined")
        require(_id.trim.nonEmpty, "document id undefined")
        true
    }
}

/**
 * An interface for modifying the revision number on a document. Hides the details of
 * the revision to some extent while providing a marker interface for operations that
 * need to update the revision on a document.
 */
protected[core] trait DocumentRevisionProvider {
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

    private var _rev: DocRevision = DocRevision()
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
trait DocumentFactory[W] extends InMemoryCache[W] {
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
     * @return Future[DocInfo] with completion to DocInfo containing the save document id and revision
     */
    def put[Wsuper >: W](db: ArtifactStore[Wsuper], doc: W)(
        implicit transid: TransactionId): Future[DocInfo] = {
        Try {
            require(db != null, "db undefined")
            require(doc != null, "doc undefined")
        } map { _ =>
            implicit val logger = db: Logging
            implicit val ec = db.executionContext

            cacheInvalidate(cacheKeys(doc))
            db.put(doc) map { docinfo =>
                doc match {
                    // if doc has a revision id, update it with new version
                    case w: DocumentRevisionProvider => w.revision[W](docinfo.rev)
                }
                // cache put result iff put future was successful
                cacheUpdate(cacheKeys(doc), doc)
                docinfo
            }
        } match {
            case Success(f) => f
            case Failure(t) => Future.failed(t)
        }
    }

    def del[Wsuper >: W](db: ArtifactStore[Wsuper], doc: DocInfo)(
        implicit transid: TransactionId): Future[Boolean] = {
        Try {
            require(db != null, "db undefined")
            require(doc != null, "doc undefined")
        } map { _ =>
            implicit val logger = db: Logging
            // invalidate two keys: just the id, and id:rev
            cacheInvalidate(Set(doc, doc.id.asDocInfo))
            db.del(doc)
        } match {
            case Success(f) => f
            case Failure(t) => Future.failed(t)
        }
    }

    /**
     * FIXME UPDATE Fetches a raw record of type R from the datastore by its id (and revision if given)
     * and converts it to Success(W) or Failure(Throwable) if there is an error fetching
     * the record or deserializing it.
     *
     * The type parameters for the database are bounded from below to allow gets from a database that
     * contains several different but related types (for example entities are stored in the same database
     * and share common super types EntityRecord and WhiskEntity.
     *
     * @param db the datastore client to fetch entity from
     * @param doc the entity document information (must contain a valid id, and optional revision)
     * @param fromCache will only query cache if true (defaults to collection settings)
     * @param transid the transaction id for logging
     * @param mw a manifest for W (hint to compiler to preserve type R for runtime)
     * @return Future[W] with completion to Success(W), or Failure(Throwable) if the raw record cannot be converted into W
     */
    def get[Wsuper >: W](db: ArtifactStore[Wsuper], doc: DocInfo, fromCache: Boolean = cacheEnabled)(
        implicit transid: TransactionId, mw: Manifest[W]): Future[W] = {
        Try {
            require(db != null, "db undefined")
            require(doc != null, "doc undefined")
        } map {
            implicit val logger = db: Logging
            _ => cacheLookup(db, doc, db.get[W](doc), fromCache)
        } match {
            case Success(f) => f
            case Failure(t) => Future.failed(t)
        }
    }
}
