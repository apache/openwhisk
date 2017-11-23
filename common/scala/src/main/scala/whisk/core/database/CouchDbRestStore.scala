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

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._

import akka.actor.ActorSystem
import akka.event.Logging.ErrorLevel
import akka.http.scaladsl.model._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._
import akka.util.ByteString
import spray.json._
import whisk.common.Logging
import whisk.common.LoggingMarkers
import whisk.common.TransactionId
import whisk.core.entity.BulkEntityResult
import whisk.core.entity.DocInfo
import whisk.core.entity.DocRevision
import whisk.core.entity.WhiskDocument
import whisk.http.Messages
import whisk.core.entity.DocumentReader

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
class CouchDbRestStore[DocumentAbstraction <: DocumentSerializer](dbProtocol: String,
                                                                  dbHost: String,
                                                                  dbPort: Int,
                                                                  dbUsername: String,
                                                                  dbPassword: String,
                                                                  dbName: String,
                                                                  useBatching: Boolean = false)(
  implicit system: ActorSystem,
  val logging: Logging,
  jsonFormat: RootJsonFormat[DocumentAbstraction],
  materializer: ActorMaterializer,
  docReader: DocumentReader)
    extends ArtifactStore[DocumentAbstraction]
    with DefaultJsonProtocol {

  protected[core] implicit val executionContext = system.dispatcher

  private val client: CouchDbRestClient =
    new CouchDbRestClient(dbProtocol, dbHost, dbPort.toInt, dbUsername, dbPassword, dbName)

  // This the the amount of allowed parallel requests for each entity, before batching starts. If there are already maxOpenDbRequests
  // and more documents need to be stored, then all arriving documents will be put into batches (if enabled) to avoid a long queue.
  private val maxOpenDbRequests = system.settings.config.getInt("akka.http.host-connection-pool.max-connections") / 2

  private val batcher: Batcher[JsObject, Either[ArtifactStoreException, DocInfo]] =
    new Batcher(500, maxOpenDbRequests)(put(_)(TransactionId.unknown))

  override protected[database] def put(d: DocumentAbstraction)(implicit transid: TransactionId): Future[DocInfo] = {
    val asJson = d.toDocumentRecord

    val id: String = asJson.fields("_id").convertTo[String].trim
    val rev: Option[String] = asJson.fields.get("_rev").map(_.convertTo[String])
    require(!id.isEmpty, "document id must be defined")

    val docinfoStr = s"id: $id, rev: ${rev.getOrElse("null")}"
    val start = transid.started(this, LoggingMarkers.DATABASE_SAVE, s"[PUT] '$dbName' saving document: '${docinfoStr}'")

    val f = if (useBatching) {
      batcher.put(asJson).map { e =>
        e match {
          case Right(response) =>
            transid.finished(this, start, s"[PUT] '$dbName' completed document: '${docinfoStr}', response: '$response'")
            response

          case Left(e: DocumentConflictException) =>
            transid.finished(this, start, s"[PUT] '$dbName', document: '${docinfoStr}'; conflict.")
            // For compatibility.
            throw DocumentConflictException("conflict on 'put'")

          case Left(e: ArtifactStoreException) =>
            transid.finished(this, start, s"[PUT] '$dbName', document: '${docinfoStr}'; ${e.getMessage}.")
            throw PutException("error on 'put'")
        }
      }
    } else {
      val request: CouchDbRestClient => Future[Either[StatusCode, JsObject]] = rev match {
        case Some(r) =>
          client =>
            client.putDoc(id, r, asJson)
        case None =>
          client =>
            client.putDoc(id, asJson)
      }
      request(client).map {
        case Right(response) =>
          transid.finished(this, start, s"[PUT] '$dbName' completed document: '${docinfoStr}', response: '$response'")
          response.convertTo[DocInfo]
        case Left(StatusCodes.Conflict) =>
          transid.finished(this, start, s"[PUT] '$dbName', document: '${docinfoStr}'; conflict.")
          // For compatibility.
          throw DocumentConflictException("conflict on 'put'")
        case Left(code) =>
          transid.failed(
            this,
            start,
            s"[PUT] '$dbName' failed to put document: '${docinfoStr}'; http status: '${code}'",
            ErrorLevel)
          throw new Exception("Unexpected http response code: " + code)
      }
    }

    reportFailure(
      f,
      failure =>
        transid.failed(this, start, s"[PUT] '$dbName' internal error, failure: '${failure.getMessage}'", ErrorLevel))
  }

  private def put(ds: Seq[JsObject])(
    implicit transid: TransactionId): Future[Seq[Either[ArtifactStoreException, DocInfo]]] = {
    val count = ds.size
    val start = transid.started(this, LoggingMarkers.DATABASE_BULK_SAVE, s"'$dbName' saving $count documents")

    val f = client.putDocs(ds).map {
      _ match {
        case Right(response) =>
          transid.finished(this, start, s"'$dbName' completed $count documents")

          response.convertTo[Seq[BulkEntityResult]].map { singleResult =>
            singleResult.error
              .map {
                case "conflict" => Left(DocumentConflictException("conflict on 'bulk_put'"))
                case e          => Left(PutException(s"Unexpected $e: ${singleResult.reason.getOrElse("")} on 'bulk_put'"))
              }
              .getOrElse {
                Right(singleResult.toDocInfo)
              }
          }

        case Left(code) =>
          transid.failed(this, start, s"'$dbName' failed to put documents, http status: '${code}'", ErrorLevel)
          throw new Exception("Unexpected http response code: " + code)
      }
    }

    reportFailure(
      f,
      failure =>
        transid.failed(this, start, s"[PUT] '$dbName' internal error, failure: '${failure.getMessage}'", ErrorLevel))
  }

  override protected[database] def del(doc: DocInfo)(implicit transid: TransactionId): Future[Boolean] = {
    require(doc != null && doc.rev.asString != null, "doc revision required for delete")

    val start = transid.started(this, LoggingMarkers.DATABASE_DELETE, s"[DEL] '$dbName' deleting document: '$doc'")

    val f = client.deleteDoc(doc.id.id, doc.rev.rev).map { e =>
      e match {
        case Right(response) =>
          transid.finished(this, start, s"[DEL] '$dbName' completed document: '$doc', response: $response")
          response.fields("ok").convertTo[Boolean]

        case Left(StatusCodes.NotFound) =>
          transid.finished(this, start, s"[DEL] '$dbName', document: '${doc}'; not found.")
          // for compatibility
          throw NoDocumentException("not found on 'delete'")

        case Left(StatusCodes.Conflict) =>
          transid.finished(this, start, s"[DEL] '$dbName', document: '${doc}'; conflict.")
          throw DocumentConflictException("conflict on 'delete'")

        case Left(code) =>
          transid.failed(
            this,
            start,
            s"[DEL] '$dbName' failed to delete document: '${doc}'; http status: '${code}'",
            ErrorLevel)
          throw new Exception("Unexpected http response code: " + code)
      }
    }

    reportFailure(
      f,
      failure =>
        transid.failed(
          this,
          start,
          s"[DEL] '$dbName' internal error, doc: '$doc', failure: '${failure.getMessage}'",
          ErrorLevel))
  }

  override protected[database] def get[A <: DocumentAbstraction](doc: DocInfo)(implicit transid: TransactionId,
                                                                               ma: Manifest[A]): Future[A] = {

    val start = transid.started(this, LoggingMarkers.DATABASE_GET, s"[GET] '$dbName' finding document: '$doc'")

    require(doc != null, "doc undefined")
    val request: CouchDbRestClient => Future[Either[StatusCode, JsObject]] = if (doc.rev.rev != null) { client =>
      client.getDoc(doc.id.id, doc.rev.rev)
    } else { client =>
      client.getDoc(doc.id.id)
    }

    val f = request(client).map { e =>
      e match {
        case Right(response) =>
          transid.finished(this, start, s"[GET] '$dbName' completed: found document '$doc'")

          val asFormat = try {
            docReader.read(ma, response)
          } catch {
            case e: Exception => jsonFormat.read(response)
          }

          if (asFormat.getClass != ma.runtimeClass) {
            throw DocumentTypeMismatchException(
              s"document type ${asFormat.getClass} did not match expected type ${ma.runtimeClass}.")
          }

          val deserialized = asFormat.asInstanceOf[A]

          val responseRev = response.fields("_rev").convertTo[String]
          assert(doc.rev.rev == null || doc.rev.rev == responseRev, "Returned revision should match original argument")
          // FIXME remove mutability from appropriate classes now that it is no longer required by GSON.
          deserialized.asInstanceOf[WhiskDocument].revision(DocRevision(responseRev))

          deserialized

        case Left(StatusCodes.NotFound) =>
          transid.finished(this, start, s"[GET] '$dbName', document: '${doc}'; not found.")
          // for compatibility
          throw NoDocumentException("not found on 'get'")

        case Left(code) =>
          transid.finished(this, start, s"[GET] '$dbName' failed to get document: '${doc}'; http status: '${code}'")
          throw new Exception("Unexpected http response code: " + code)
      }
    } recoverWith {
      case e: DeserializationException => throw DocumentUnreadable(Messages.corruptedEntity)
    }

    reportFailure(
      f,
      failure =>
        transid.failed(
          this,
          start,
          s"[GET] '$dbName' internal error, doc: '$doc', failure: '${failure.getMessage}'",
          ErrorLevel))
  }

  override protected[core] def query(table: String,
                                     startKey: List[Any],
                                     endKey: List[Any],
                                     skip: Int,
                                     limit: Int,
                                     includeDocs: Boolean,
                                     descending: Boolean,
                                     reduce: Boolean,
                                     stale: StaleParameter)(implicit transid: TransactionId): Future[List[JsObject]] = {

    require(!(reduce && includeDocs), "reduce and includeDocs cannot both be true")

    // Apparently you have to do that in addition to setting "descending"
    val (realStartKey, realEndKey) = if (descending) {
      (endKey, startKey)
    } else {
      (startKey, endKey)
    }

    val parts = table.split("/")

    val start = transid.started(this, LoggingMarkers.DATABASE_QUERY, s"[QUERY] '$dbName' searching '$table")

    val f = for (eitherResponse <- client.executeView(parts(0), parts(1))(
                   startKey = realStartKey,
                   endKey = realEndKey,
                   skip = Some(skip),
                   limit = Some(limit),
                   stale = stale,
                   includeDocs = includeDocs,
                   descending = descending,
                   reduce = reduce))
      yield
        eitherResponse match {
          case Right(response) =>
            val rows = response.fields("rows").convertTo[List[JsObject]]

            val out = if (reduce && !rows.isEmpty) {
              assert(rows.length == 1, s"result of reduced view contains more than one value: '$rows'")
              rows.head.fields("value").convertTo[List[JsObject]]
            } else if (reduce) {
              List(JsObject())
            } else {
              rows
            }

            transid.finished(this, start, s"[QUERY] '$dbName' completed: matched ${out.size}")
            out

          case Left(code) =>
            transid.failed(this, start, s"Unexpected http response code: $code", ErrorLevel)
            throw new Exception("Unexpected http response code: " + code)
        }

    reportFailure(
      f,
      failure =>
        transid.failed(this, start, s"[QUERY] '$dbName' internal error, failure: '${failure.getMessage}'", ErrorLevel))
  }

  override protected[core] def attach(
    doc: DocInfo,
    name: String,
    contentType: ContentType,
    docStream: Source[ByteString, _])(implicit transid: TransactionId): Future[DocInfo] = {

    val start = transid.started(
      this,
      LoggingMarkers.DATABASE_ATT_SAVE,
      s"[ATT_PUT] '$dbName' uploading attachment '$name' of document '$doc'")

    require(doc != null, "doc undefined")
    require(doc.rev.rev != null, "doc revision must be specified")

    val f = client.putAttachment(doc.id.id, doc.rev.rev, name, contentType, docStream).map { e =>
      e match {
        case Right(response) =>
          transid
            .finished(this, start, s"[ATT_PUT] '$dbName' completed uploading attachment '$name' of document '$doc'")
          val id = response.fields("id").convertTo[String]
          val rev = response.fields("rev").convertTo[String]
          DocInfo ! (id, rev)

        case Left(StatusCodes.NotFound) =>
          transid
            .finished(this, start, s"[ATT_PUT] '$dbName' uploading attachment '$name' of document '$doc'; not found")
          throw NoDocumentException("Not found on 'readAttachment'.")

        case Left(code) =>
          transid.failed(
            this,
            start,
            s"[ATT_PUT] '$dbName' failed to upload attachment '$name' of document '$doc'; http status '$code'")
          throw new Exception("Unexpected http response code: " + code)
      }
    }

    reportFailure(
      f,
      failure =>
        transid.failed(
          this,
          start,
          s"[ATT_PUT] '$dbName' internal error, name: '$name', doc: '$doc', failure: '${failure.getMessage}'",
          ErrorLevel))
  }

  override protected[core] def readAttachment[T](doc: DocInfo, name: String, sink: Sink[ByteString, Future[T]])(
    implicit transid: TransactionId): Future[(ContentType, T)] = {

    val start = transid.started(
      this,
      LoggingMarkers.DATABASE_ATT_GET,
      s"[ATT_GET] '$dbName' finding attachment '$name' of document '$doc'")

    require(doc != null, "doc undefined")
    require(doc.rev.rev != null, "doc revision must be specified")

    val f = client.getAttachment[T](doc.id.id, doc.rev.rev, name, sink)
    val g = f.map { e =>
      e match {
        case Right((contentType, result)) =>
          transid.finished(this, start, s"[ATT_GET] '$dbName' completed: found attachment '$name' of document '$doc'")
          (contentType, result)

        case Left(StatusCodes.NotFound) =>
          transid.finished(
            this,
            start,
            s"[ATT_GET] '$dbName', retrieving attachment '$name' of document '$doc'; not found.")
          throw NoDocumentException("Not found on 'readAttachment'.")

        case Left(code) =>
          transid.failed(
            this,
            start,
            s"[ATT_GET] '$dbName' failed to get attachment '$name' of document '$doc'; http status: '${code}'")
          throw new Exception("Unexpected http response code: " + code)
      }
    }

    reportFailure(
      g,
      failure =>
        transid.failed(
          this,
          start,
          s"[ATT_GET] '$dbName' internal error, name: '$name', doc: '$doc', failure: '${failure.getMessage}'",
          ErrorLevel))
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
