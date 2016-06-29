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
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.{ Try, Success, Failure }
import spray.json.JsObject
import spray.http.StatusCode
import spray.http.StatusCodes
import whisk.common.Logging
import whisk.common.TransactionId
import whisk.core.entity.WhiskDocument
import whisk.core.entity.DocRevision
import whisk.core.entity.DocInfo
import whisk.utils.ExecutionContextFactory
import akka.actor.ActorSystem
import spray.json.RootJsonFormat
import spray.json.DefaultJsonProtocol
import spray.json.JsValue
import whisk.common.LoggingMarkers

/**
 * Basic client to put and delete artifacts in a data store.
 *
 * @param dbProtocol the protocol to access the database with (http/https)
 * @param dbHost the host to access database from
 * @param dbPort the port on the host
 * @param dbUserName the user name to access database as
 * @param dbPassword the secret for the user name required to access the database
 * @param dbName the name of the database to operate on
 * @param serializerEvidence confirms the document abstraction is serializable to a Document with an id
 */
class CouchDbRestStore[DocumentAbstraction <: DocumentSerializer](
    dbProtocol: String,
    dbHost: String,
    dbPort: Int,
    dbUsername: String,
    dbPassword: String,
    dbName: String)(implicit system: ActorSystem, jsonFormat: RootJsonFormat[DocumentAbstraction])
    extends ArtifactStore[DocumentAbstraction]
    with DefaultJsonProtocol {

    protected[core] implicit val executionContext = system.dispatcher

    private val client: CouchDbRestClient = CouchDbRestClient.make(
        dbProtocol, dbHost, dbPort, dbUsername, dbPassword, dbName)

    override protected[database] def put(d: DocumentAbstraction)(implicit transid: TransactionId): Future[DocInfo] = {
        // Uses Spray-JSON only.
        val asJson = d.toDocumentRecord

        val id: String = asJson.fields("_id").convertTo[String].trim
        val rev: Option[String] = asJson.fields.get("_rev").map(_.convertTo[String])
        require(!id.isEmpty, "document id must be defined")

        val docinfoStr = s"id: $id, rev: ${rev.getOrElse("null")}"

        info(this, s"[PUT] '$dbName' saving document: '${docinfoStr}'", LoggingMarkers.DATABASE_SAVE_START)

        val request: CouchDbRestClient => Future[Either[StatusCode, JsObject]] = rev match {
            case Some(r) =>
                client => client.putDoc(id, r, asJson)

            case None =>
                client => client.putDoc(id, asJson)
        }

        reportFailure({
            for (
                eitherResponse <- request(client)
            ) yield eitherResponse match {
                case Right(response) =>
                    info(this, s"[PUT] '$dbName' completed document: '${docinfoStr}', response: '$response'", LoggingMarkers.DATABASE_SAVE_DONE)
                    val id = response.fields("id").convertTo[String]
                    val rev = response.fields("rev").convertTo[String]
                    DocInfo ! (id, rev)

                case Left(StatusCodes.Conflict) =>
                    info(this, s"[PUT] '$dbName', document: '${docinfoStr}'; conflict.", LoggingMarkers.DATABASE_SAVE_DONE)
                    // For compatibility.
                    throw DocumentConflictException("conflict on 'put'")

                case Left(code) =>
                    error(this, s"[PUT] '$dbName' failed to put document: '${docinfoStr}'; http status: '${code}'", LoggingMarkers.DATABASE_SAVE_ERROR)
                    throw new Exception("Unexpected http response code: " + code)
            }
        },
            failure => error(this, s"[PUT] '$dbName' internal error, failure: '${failure.getMessage}'", LoggingMarkers.DATABASE_SAVE_ERROR))
    }

    override protected[database] def del(doc: DocInfo)(implicit transid: TransactionId): Future[Boolean] = {
        require(doc != null && doc.rev() != null, "doc revision required for delete")
        info(this, s"[DEL] '$dbName' deleting document: '$doc'", LoggingMarkers.DATABASE_DELETE_START)

        reportFailure({
            for (
                eitherResponse <- client.deleteDoc(doc.id.id, doc.rev.rev)
            ) yield eitherResponse match {
                case Right(response) =>
                    info(this, s"[DEL] '$dbName' completed document: '$doc', response: $response", LoggingMarkers.DATABASE_DELETE_DONE)
                    response.fields("ok").convertTo[Boolean]

                case Left(StatusCodes.NotFound) =>
                    info(this, s"[DEL] '$dbName', document: '${doc}'; not found.", LoggingMarkers.DATABASE_DELETE_DONE)
                    // for compatibility
                    throw NoDocumentException("not found on 'delete'")

                case Left(code) =>
                    error(this, s"[DEL] '$dbName' failed to delete document: '${doc}'; http status: '${code}'", LoggingMarkers.DATABASE_DELETE_ERROR)
                    throw new Exception("Unexpected http response code: " + code)
            }
        },
            failure => error(this, s"[DEL] '$dbName' internal error, doc: '$doc', failure: '${failure.getMessage}'", LoggingMarkers.DATABASE_DELETE_ERROR))
    }

    override protected[database] def get[A <: DocumentAbstraction](doc: DocInfo)(
        implicit transid: TransactionId,
        ma: Manifest[A]): Future[A] = {

        reportFailure({
            require(doc != null, "doc undefined")
            info(this, s"[GET] '$dbName' finding document: '$doc'", LoggingMarkers.DATABASE_GET_START)
            val request: CouchDbRestClient => Future[Either[StatusCode, JsObject]] = if (doc.rev.rev != null) {
                client => client.getDoc(doc.id.id, doc.rev.rev)
            } else {
                client => client.getDoc(doc.id.id)
            }

            for (
                eitherResponse <- request(client)
            ) yield eitherResponse match {
                case Right(response) =>
                    info(this, s"[GET] '$dbName' completed: found document '$doc'", LoggingMarkers.DATABASE_GET_DONE)
                    val asFormat = jsonFormat.read(response)
                    // For backwards compatibility, we should fail with IllegalArgumentException
                    // if the retrieved type doesn't match the expected type. The following does
                    // just that.
                    require(asFormat.getClass == ma.runtimeClass, s"document type ${asFormat.getClass} did not match expected type ${ma.runtimeClass}.")

                    val deserialized = asFormat.asInstanceOf[A]

                    val responseRev = response.fields("_rev").convertTo[String]
                    assert(doc.rev.rev == null || doc.rev.rev == responseRev, "Returned revision should match original argument")
                    // FIXME remove mutability from appropriate classes now that it is no longer required by GSON.
                    deserialized.asInstanceOf[WhiskDocument].revision(DocRevision(responseRev))

                    deserialized

                case Left(StatusCodes.NotFound) =>
                    info(this, s"[GET] '$dbName', document: '${doc}'; not found.", LoggingMarkers.DATABASE_GET_DONE)
                    // for compatibility
                    throw NoDocumentException("not found on 'get'")

                case Left(code) =>
                    error(this, s"[GET] '$dbName' failed to get document: '${doc}'; http status: '${code}'", LoggingMarkers.DATABASE_GET_ERROR)
                    throw new Exception("Unexpected http response code: " + code)
            }
        },
            failure => error(this, s"[GET] '$dbName' internal error, doc: '$doc', failure: '${failure.getMessage}'", LoggingMarkers.DATABASE_GET_ERROR))
    }

    override protected[core] def query(table: String, startKey: List[Any], endKey: List[Any], skip: Int, limit: Int, includeDocs: Boolean, descending: Boolean, reduce: Boolean)(
        implicit transid: TransactionId): Future[List[JsObject]] = {

        require(!(reduce && includeDocs), "reduce and includeDocs cannot both be true")

        // Apparently you have to do that in addition to setting "descending"
        val (realStartKey, realEndKey) = if (descending) {
            (endKey, startKey)
        } else {
            (startKey, endKey)
        }

        val parts = table.split("/")

        info(this, s"[QUERY] '$dbName' searching '$table ${startKey}:${endKey}'", LoggingMarkers.DATABASE_QUERY_START)

        reportFailure({
            for (
                eitherResponse <- client.executeView(parts(0), parts(1))(
                    startKey = realStartKey,
                    endKey = realEndKey,
                    skip = Some(skip),
                    limit = Some(limit),
                    includeDocs = includeDocs,
                    descending = descending,
                    reduce = reduce)
            ) yield eitherResponse match {
                case Right(response) =>
                    val rows = response.fields("rows").convertTo[List[JsObject]]

                    val out = if (includeDocs) {
                        rows.map(_.fields("doc").asJsObject)
                    } else if (reduce && !rows.isEmpty) {
                        assert(rows.length == 1, s"result of reduced view contains more than one value: '$rows'")
                        rows.head.fields("value").convertTo[List[JsObject]]
                    } else if (reduce) {
                        List(JsObject())
                    } else {
                        rows
                    }

                    info(this, s"[QUERY] '$dbName' completed: matched ${out.size}", LoggingMarkers.DATABASE_QUERY_DONE)
                    out

                case Left(code) =>
                    throw new Exception("Unexpected http response code: " + code)
            }
        },
            failure => error(this, s"[QUERY] '$dbName' internal error, failure: '${failure.getMessage}'", LoggingMarkers.DATABASE_QUERY_ERROR))
    }

    override def shutdown(): Unit = {
        Await.ready(client.shutdown(), 1.minute)
    }

    private def reportFailure[T, U](f: Future[T], onFailure: Throwable => U): Future[T] = {
        f.onFailure({
            case _: ArtifactStoreException => // These failures are intentional and shouldn't trigger the catcher.
            case x                         => onFailure(x)
        })
        f
    }
}
