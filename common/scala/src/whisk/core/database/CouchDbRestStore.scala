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
class CouchDbRestStore[Unused, DocumentAbstraction <: DocumentSerializer](
    dbProtocol: String,
    dbHost: String,
    dbPort: Int,
    dbUsername: String,
    dbPassword: String,
    dbName: String)(implicit system: ActorSystem, jsonFormat: RootJsonFormat[DocumentAbstraction])
    extends ArtifactStore[Unused, DocumentAbstraction]
    with DefaultJsonProtocol {

    protected[core] implicit val executionContext = system.dispatcher

    private val client: CouchDbRestClient = CouchDbRestClient.make(
        dbProtocol, dbHost, dbPort, dbUsername, dbPassword, dbName
    )

    // To make the typechecker happy.
    private implicit def fakeDeserializer[U<:Unused, T<:DocumentAbstraction] : Deserializer[U,T] = (x:U) => ???

    override protected[database] def put(d: DocumentAbstraction)(implicit transid: TransactionId): Future[DocInfo] = {
        val serialized = d.serialize().get
        require(serialized != null, "doc undefined after serialization")
        serialized.confirmId
        info(this, s"[PUT] '$dbName' saving document: '${serialized.docinfo}'")

        // The spray-json version. This seems to be missing some important fields?
        val asJson = jsonFormat.write(d).asJsObject

        // The GSON version:
        val asGson = {
            import spray.json._
            import DefaultJsonProtocol._
            import com.google.gson.Gson
            val srzd: String = new Gson().toJson(serialized)
            srzd.parseJson.asJsObject
        }

        // We'll activate this warning once we start getting rid of GSON.
        // It doesn't really matter for now, because we insert from the GSON view
        // anyway.
        if(false && asJson != asGson) {
            warn(this, s"[PUT] JSON/GSON mismatch:")
            warn(this, s"[PUT]   - json : " + asJson)
            warn(this, s"[PUT]   - gson : " + asGson)
        }

        val request: CouchDbRestClient=>Future[Either[StatusCode,JsObject]] = if(serialized.update) {
            client => client.putDoc(serialized.docinfo.id.id, serialized.docinfo.rev.rev, asGson /* Not ideal. */)
        } else {
            client => client.putDoc(serialized.docinfo.id.id, asGson /* Not ideal. */)
        }

        reportFailure({
            for(
                eitherResponse <- request(client)
            ) yield eitherResponse match {
                    case Right(response) =>
                        info(this, s"[PUT] '$dbName' completed document: '${serialized.docinfo}', response: '$response'")
                        val id = response.fields("id").convertTo[String]
                        val rev = response.fields("rev").convertTo[String]
                        DocInfo ! (id, rev)

                    case Left(StatusCodes.Conflict) =>
                        info(this, s"[PUT] '$dbName', document: '${serialized.docinfo}'; conflict.")
                        // For compatibility.
                        throw new org.lightcouch.DocumentConflictException("conflict on 'put'")

                    case Left(code) =>
                        error(this, s"[PUT] '$dbName' failed to put document: '${serialized.docinfo}'; http status: '${code}'")
                        throw new Exception("Unexpected http response code: " + code)
                }
            },
            failure => error(this, s"[PUT] '$dbName' internal error, failure: '${failure.getMessage}'")
        )
    }

    override protected[database] def del(doc: DocInfo)(implicit transid: TransactionId): Future[Boolean] = {
        require(doc != null && doc.rev() != null, "doc revision required for delete")
        info(this, s"[DEL] '$dbName' deleting document: '$doc'")

        reportFailure({
            for(
                eitherResponse <- client.deleteDoc(doc.id.id, doc.rev.rev)
            ) yield eitherResponse match {
                    case Right(response) =>
                        info(this, s"[DEL] '$dbName' completed document: '$doc', response: $response")
                        response.fields("ok").convertTo[Boolean]

                    case Left(StatusCodes.NotFound) =>
                        info(this, s"[DEL] '$dbName', document: '${doc}'; not found.")
                        // for compatibility
                        throw new org.lightcouch.NoDocumentException("not found on 'del'")

                    case Left(code) =>
                        error(this, s"[DEL] '$dbName' failed to delete document: '${doc}'; http status: '${code}'")
                        throw new Exception("Unexpected http response code: " + code)
                }
            },
            failure => error(this, s"[DEL] '$dbName' internal error, doc: '$doc', failure: '${failure.getMessage}'")
        )
    }

    override protected[database] def get[U <: Unused, A <: DocumentAbstraction](doc: DocInfo)(
        implicit transid: TransactionId,
        deserialize: Deserializer[U, A],
        mu: Manifest[U],
        ma: Manifest[A]): Future[A] = {

        reportFailure({
            require(doc != null, "doc undefined")
            require(deserialize != null, "deserializer undefined")
            info(this, s"[GET] '$dbName' finding document: '$doc'")
            val request: CouchDbRestClient=>Future[Either[StatusCode,JsObject]] = if(doc.rev.rev != null) {
                client => client.getDoc(doc.id.id, doc.rev.rev)
            } else {
                client => client.getDoc(doc.id.id)
            }

            for(
                eitherResponse <- request(client)
            ) yield eitherResponse match {
                    case Right(response) =>
                        info(this, s"[GET] '$dbName' completed: found document '$doc'")
                        val asFormat = jsonFormat.read(response)
                        // For backwards compatibility, we should fail with IllegalArgumentException
                        // if the retrieved type doesn't match the expected type. The following does
                        // just that.
                        require(asFormat.getClass == ma.runtimeClass, s"document type ${asFormat.getClass} did not match expected type ${ma.runtimeClass}.")

                        val deserialized = asFormat.asInstanceOf[A]

                        val responseRev = response.fields("_rev").convertTo[String]
                        assert(doc.rev.rev == null || doc.rev.rev == responseRev, "Returned revision should match original argument")
                        // FIXME Ugly hack that hopefully will go away once we're GSON-free.
                        deserialized.asInstanceOf[WhiskDocument].revision(DocRevision(responseRev))

                        deserialized

                    case Left(StatusCodes.NotFound) =>
                        info(this, s"[GET] '$dbName', document: '${doc}'; not found.")
                        // for compatibility
                        throw new org.lightcouch.NoDocumentException("not found on 'get'")

                    case Left(code) =>
                        error(this, s"[GET] '$dbName' failed to get document: '${doc}'; http status: '${code}'")
                        throw new Exception("Unexpected http response code: " + code)
                }
           },
           failure => error(this, s"[GET] '$dbName' internal error, doc: '$doc', failure: '${failure.getMessage}'")
        )
    }

    override protected[core] def query(table: String, startKey: List[Any], endKey: List[Any], skip: Int, limit: Int, includeDocs: Boolean, descending: Boolean, reduce: Boolean)(
        implicit transid: TransactionId): Future[List[JsObject]] = {

        require(!(reduce && includeDocs), "reduce and includeDocs cannot both be true")

        // Apparently you have to do that in addition to setting "descending"
        val (realStartKey, realEndKey) = if(descending) {
            (endKey, startKey)
        } else {
            (startKey, endKey)
        }

        val parts = table.split("/")

        info(this, s"[QUERY] '$dbName' searching '$table ${startKey}:${endKey}'")

        reportFailure({
            for(
                eitherResponse <- client.executeView(parts(0), parts(1))(
                    startKey=realStartKey,
                    endKey=realEndKey,
                    skip=Some(skip),
                    limit=Some(limit),
                    includeDocs=includeDocs,
                    descending=descending,
                    reduce=reduce
                )
            ) yield eitherResponse match {
                    case Right(response) =>
                        val rows = response.fields("rows").convertTo[List[JsObject]]

                        val out = if(includeDocs) {
                            rows.map(_.fields("doc").asJsObject)
                        } else if(reduce && !rows.isEmpty) {
                            assert(rows.length == 1, s"result of reduced view contains more than one value: '$rows'")
                            rows.head.fields("value").convertTo[List[JsObject]]
                        } else if(reduce) {
                            List(JsObject())
                        } else {
                            rows
                        }

                        info(this, s"[QUERY] '$dbName' completed: matched ${out.size}")
                        out

                    case Left(code) =>
                        throw new Exception("Unexpected http response code: " + code)
                }
            },
            failure => error(this, s"[QUERY] '$dbName' internal error, failure: '${failure.getMessage}'")
        )
    }

    override def shutdown() : Unit = {
        Await.ready(client.shutdown(), 1.minute)
    }

    private def reportFailure[T,U](f: Future[T], onFailure: Throwable=>U) : Future[T] = {
        f.onFailure({ case x => onFailure(x) })
        f
    }
}
