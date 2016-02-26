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

import scala.collection.JavaConversions.asScalaBuffer
import scala.collection.JavaConverters.seqAsJavaListConverter
import scala.concurrent.Future
import org.lightcouch.CouchDbException
import org.lightcouch.NoDocumentException
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import spray.json.JsObject
import whisk.common.TransactionId
import whisk.core.entity.DocInfo
import spray.json.JsArray

/**
 * Basic client to put and delete artifacts in a data store.
 *
 * @param dbHost the host to access database from
 * @param dbPort the port on the host
 * @param dbUserName the user name to access database as
 * @param dbPassword the secret for the user name required to access the database
 * @param dbName the name of the database to operate on
 * @param serializerEvidence confirms the document abstraction is serializable to a Document with an id
 */
class CouchDbLikeStore[View: CouchDbLikeViewProvider, RawDocument, DocumentAbstraction](
    dbProvider: CouchDbLikeProvider[View],
    dbHost: String,
    dbPort: Int,
    dbUsername: String,
    dbPassword: String,
    dbName: String)(implicit providerserializerEvidence: DocumentAbstraction <:< DocumentSerializer)
    extends ArtifactStore[RawDocument, DocumentAbstraction] {

    private val viewProvider = implicitly[CouchDbLikeViewProvider[View]]

    /**
     * Puts (saves) document to database using a future.
     * If the operation is successful, the future completes with DocId else an appropriate exception.
     *
     * For CouchDB/Cloudant, per the API doc: https://docs.cloudant.com/document.html
     * The response is a JSON document containing the ID of the created document,
     * the revision string, and "ok": true. If you did not provide an _id field,
     * CouchDB generates one automatically as a UUID. If creation of the
     * document failed, the response contains a description of the error.
     * If the write quorum cannot be met, a 202 response is returned.
     *
     * @param d the document to put in the database
     * @param transid the transaction id for logging
     * @return a future that completes either with DocId
     */
    override protected[database] def put(d: DocumentAbstraction)(implicit transid: TransactionId): Future[DocInfo] = {
        reportFailure(
            Future {
                require(d != null, "doc undefined")
                val doc = d.serialize().get // throws exception if serialization failed
                require(doc != null, "doc undefined after serialization")
                doc.confirmId
                info(this, s"[PUT] '$dbName' saving document: '${doc.docinfo}'")
                val response = if (!doc.update) dbProvider.saveInDB(doc, db) else dbProvider.updateInDB(doc, db)
                info(this, s"[PUT] '$dbName' completed document: '${doc.docinfo}' ${describe(response)}")
                validate(response)
                dbProvider.mkDocInfo(response)
            }, onFailure = {
                case t => info(this, s"[PUT] '$dbName' document: failed '$d' ${inform(t)}")
            })
    }

    /**
     * Deletes document from database using a future.
     * If the operation is successful, the future completes with true.
     *
     * For CouchDB/Cloudant, per the API doc: https://docs.cloudant.com/document.html
     * If you fail to provide the latest _rev, CouchDB responds with a 409 error.
     * This error prevents you overwriting data changed by other clients.
     * If the write quorum cannot be met, a 202 response is returned.
     * CouchDB doesn’t completely delete the specified document. Instead, it leaves
     * a tombstone with very basic information about the document. The tombstone is
     * required so that the delete action can be replicated. Since the tombstones stay
     * in the database indefinitely, creating new documents and deleting them increases
     * the disk space usage of a database and the query time for the primary index,
     * which is used to look up documents by their ID.
     *
     * @param doc the document info for the record to delete (must contain valid id and rev)
     * @param transid the transaction id for logging
     * @return a future that completes either true if the document is deleted
     */
    override protected[database] def del(doc: DocInfo)(implicit transid: TransactionId): Future[Boolean] = {
        reportFailure(
            Future {
                require(doc != null && doc.rev() != null, "doc revision required for delete")
                info(this, s"[DEL] '$dbName' deleting document: '$doc'")
                val response = dbProvider.removeFromDB(doc, db)
                info(this, s"[DEL] '$dbName' completed document: '$doc' ${describe(response)}")
                validate(response)
            }, onFailure = {
                case e: NoDocumentException =>
                    info(this, s"[DEL] '$dbName' document: '$doc' does not exist")
                case t: Throwable =>
                    info(this, s"[DEL] '$dbName' document: '$doc' exception ${inform(t)}")
            })
    }

    /**
     * Gets document from database by id using a future.
     * If the operation is successful, the future completes with the requested document if it exists.
     *
     * The type parameters for the database are bounded from above to allow gets from a database that
     * contains several different but related types (for example entities are stored in the same database
     * and share common super types EntityRecord and WhiskEntity but the gets retrieves more specific types
     * e.g., ActionRecord which deserializes to WhiskAction).
     *
     * @param doc the document info for the record to get (must contain valid id and rev)
     * @param deserialize a function from R => Try[A] where R is the RawDocument type and A is the DocumentAbstraction type
     * @param transid the transaction id for logging
     * @param m manifest for R to determine its runtime type, required by db API
     * @return a future that completes either with DocumentAbstraction if the document exists and is deserializable into desired type
     */
    override protected[database] def get[R <: RawDocument, A <: DocumentAbstraction](doc: DocInfo)(
        implicit transid: TransactionId,
        deserialize: Deserializer[R, A],
        m: Manifest[R]): Future[A] = {
        reportFailure(
            Future {
                require(doc != null, "doc undefined")
                require(deserialize != null, "deserializer undefined")
                // val RawDocumentType = m.runtimeClass.asInstanceOf[Class[R]]
                info(this, s"[GET] '$dbName' finding document: '$doc'")
                // val record = db.find(RawDocumentType, doc.id(), doc.rev())
                val record = dbProvider.findInDB[R](doc, db)
                info(this, s"[GET] '$dbName' completed: found document '$doc'")
                val result = deserialize(record).get // throws exception if deserialization failed
                require(result != null, "doc undefined after deserialization")
                result
            }, onFailure = {
                case e: NoDocumentException =>
                    info(this, s"[GET] '$dbName' document: '$doc' does not exist")
                case t: Throwable =>
                    info(this, s"[GET] '$dbName' document: '$doc' exception ${inform(t)}")
            })
    }

    /**
     * Gets all documents from database view that match a start key, up to an end key, using a future.
     * If the operation is successful, the promise completes with List[View]] with zero or more documents.
     *
     * @param table the name of the table to query
     * @param startKey to starting key to query the view for
     * @param endKey to starting key to query the view for
     * @param skip the number of record to skip (for pagination)
     * @param limit the maximum number of records matching the key to return, iff > 0
     * @param includeDocs include full documents matching query iff true (shall not be used with reduce)
     * @param transid the transaction id for logging
     * @param descending reverse results iff true
     * @param reduce apply reduction associated with query to the result iff true
     * @return a future that completes with List[JsObject] of all documents from view between start and end key (list may be empty)
     */
    override protected[core] def query(table: String, startKey: List[Any], endKey: List[Any], skip: Int, limit: Int, includeDocs: Boolean, descending: Boolean, reduce: Boolean)(
        implicit transid: TransactionId): Future[List[JsObject]] = {
        reportFailure(
            Future {
                require((reduce & !includeDocs) || !reduce, "reduce and includeDocs cannot both be true")
                // val view = db.view(table).includeDocs(includeDocs).descending(descending).reduce(reduce).inclusiveEnd(true)
                val view = dbProvider.obtainViewFromDB(table, db, includeDocs, descending, reduce, true)
                val limitedView = viewProvider.skipView(if (limit > 0) viewProvider.limitView(view, limit) else view, skip)
                val keyedView = if (descending) {
                    // reverse start/end keys for descending order by date for example
                    // limitedView.startKey(endKey.asJava).endKey(startKey.asJava)
                    viewProvider.withStartEndView(limitedView, endKey, startKey)
                } else {
                    // limitedView.startKey(startKey.asJava).endKey(endKey.asJava)
                    viewProvider.withStartEndView(limitedView, startKey, endKey)
                }

                info(this, s"[QUERY] '$dbName' searching '$table ${startKey}:${endKey}'")
                // keyedView.query(classOf[JsonObject])
                val result = viewProvider.queryView[JsonObject](keyedView)
                val rows = if (reduce == false) {
                    result map {
                        whisk.utils.JsonUtils.gsonToSprayJson(_)
                    } toList
                } else if (reduce && result.length > 0) {
                    assert(result.length == 1, s"result of reduced view contains more than one value: '$result'")
                    assert(result(0).get("value").isJsonArray(), s"result is not a JSON array: '$result'")
                    whisk.utils.JsonUtils.gsonToSprayJson(result(0).getAsJsonArray("value")) match {
                        case JsArray(values) => values map { _.asJsObject } toList
                    }
                } else { List[JsObject]() }

                info(this, s"[QUERY] '$dbName' completed: matched ${rows.size}")
                rows
            }, onFailure = {
                case t: Throwable =>
                    info(this, s"[QUERY] '$dbName' '$table ${startKey}:${endKey}' exception ${inform(t)}")
            })
    }



    /**
     * Shuts down client connection - all operations on the client will fail after shutdown.
     */
    def shutdown() = dbProvider.shutdownClient(dbClient)

    /** The service connector */
    private val dbClient = dbProvider.mkClient(dbHost, dbPort, dbUsername, dbPassword)

    /** The database connector */
    private val db = dbProvider.getDB(dbClient, dbName)

    /** Gson helper */
    private val gson = new GsonBuilder().create

    /** Describe cloudant response for logging */
    private def describe(r: dbProvider.Response) = {
        // if (r == null) "undefined" else s"${r.getId}[${r.getRev}] err=${r.getError} reason=${r.getReason}"
        dbProvider.describeResponse(r)
    }

    /** Validates cloudant response */
    private def validate(response: dbProvider.Response) = {
        //require(response != null && response.getError == null && response.getId != null && response.getRev != null, "response not valid")
        dbProvider.validateResponse(response)
    }

    /** Convenience wrapper to record an onFailure function for the future and return the future for chaining */
    private def reportFailure[T, U](future: Future[T], onFailure: PartialFunction[Throwable, U]): Future[T] = {
        future.onFailure(onFailure)
        future
    }

    /** Format error message on an exception. If this is a CouchDbException, extract the HTTP response code, and response form API. */
    private def inform(t: Throwable) = {
        val msg = s"${t.getClass}(${t.getMessage})"
        t match {
            case t: CouchDbException =>
                //val code = t.getStatusCode
                //val reason = t.getReason
                //val error = t.getError
                //val msg = s"$t ${t.getStackTrace.mkString("", ",", "")}"
                //s"code: '$code', reason '$reason', error: '$error', $msg"
                msg
            case _ => msg
        }
    }
}
