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

package whisk.core.database.cosmosdb

import java.io.ByteArrayInputStream

import _root_.rx.RxReactiveStreams
import akka.actor.ActorSystem
import akka.http.scaladsl.model.{ContentType, StatusCodes}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source, StreamConverters}
import akka.util.{ByteString, ByteStringBuilder}
import com.microsoft.azure.cosmosdb._
import com.microsoft.azure.cosmosdb.rx.AsyncDocumentClient
import spray.json.{DefaultJsonProtocol, JsObject, JsString, JsValue, RootJsonFormat, _}
import whisk.common.{Logging, LoggingMarkers, TransactionId}
import whisk.core.database.StoreUtils.{checkDocHasRevision, deserialize, reportFailure}
import whisk.core.database._
import whisk.core.database.cosmosdb.CosmosDBArtifactStoreProvider.DocumentClientRef
import whisk.core.database.cosmosdb.CosmosDBConstants._
import whisk.core.entity._
import whisk.http.Messages

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}

class CosmosDBArtifactStore[DocumentAbstraction <: DocumentSerializer](protected val collName: String,
                                                                       protected val config: CosmosDBConfig,
                                                                       clientRef: DocumentClientRef,
                                                                       documentHandler: DocumentHandler,
                                                                       protected val viewMapper: CosmosDBViewMapper)(
  implicit system: ActorSystem,
  val logging: Logging,
  jsonFormat: RootJsonFormat[DocumentAbstraction],
  materializer: ActorMaterializer,
  docReader: DocumentReader)
    extends ArtifactStore[DocumentAbstraction]
    with DefaultJsonProtocol
    with DocumentProvider
    with CosmosDBSupport {

  protected val client: AsyncDocumentClient = clientRef.get.client
  private val (database, collection) = initialize()

  private val _id = "_id"
  private val _rev = "_rev"

  override protected[core] implicit val executionContext: ExecutionContext = system.dispatcher

  override protected[database] def put(d: DocumentAbstraction)(implicit transid: TransactionId): Future[DocInfo] = {
    val asJson = d.toDocumentRecord

    //TODO Batching support
    val doc = toCosmosDoc(asJson)
    val id = doc.getId
    val docinfoStr = s"id: $id, rev: ${doc.getETag}"
    val start = transid.started(this, LoggingMarkers.DATABASE_SAVE, s"[PUT] '$collName' saving document: '$docinfoStr'")

    val o = if (doc.getETag == null) {
      client.createDocument(collection.getSelfLink, doc, newRequestOption(id), true)
    } else {
      client.replaceDocument(doc, matchRevOption(id, doc.getETag))
    }

    val f = o
      .head()
      .transform(
        { r =>
          transid.finished(this, start, s"[PUT] '$collName' completed document: '$docinfoStr'")
          toDocInfo(r.getResource)
        }, {
          case e: DocumentClientException if isConflict(e) =>
            transid.finished(this, start, s"[PUT] '$collName', document: '$docinfoStr'; conflict.")
            DocumentConflictException("conflict on 'put'")
          case e => e
        })

    reportFailure(f, start, failure => s"[PUT] '$collName' internal error, failure: '${failure.getMessage}'")
  }

  override protected[database] def del(doc: DocInfo)(implicit transid: TransactionId): Future[Boolean] = {
    checkDocHasRevision(doc)
    val start = transid.started(this, LoggingMarkers.DATABASE_DELETE, s"[DEL] '$collName' deleting document: '$doc'")
    val f = client
      .deleteDocument(selfLinkOf(doc.id), matchRevOption(doc))
      .head()
      .transform(
        { _ =>
          transid.finished(this, start, s"[DEL] '$collName' completed document: '$doc'")
          true
        }, {
          case e: DocumentClientException if isNotFound(e) =>
            transid.finished(this, start, s"[DEL] '$collName', document: '${doc}'; not found.")
            NoDocumentException("not found on 'delete'")
          case e: DocumentClientException if isConflict(e) =>
            transid.finished(this, start, s"[DEL] '$collName', document: '${doc}'; conflict.")
            DocumentConflictException("conflict on 'delete'")
          case e => e
        })

    reportFailure(
      f,
      start,
      failure => s"[DEL] '$collName' internal error, doc: '$doc', failure: '${failure.getMessage}'")
  }

  override protected[database] def get[A <: DocumentAbstraction](doc: DocInfo)(implicit transid: TransactionId,
                                                                               ma: Manifest[A]): Future[A] = {
    val start = transid.started(this, LoggingMarkers.DATABASE_GET, s"[GET] '$collName' finding document: '$doc'")

    require(doc != null, "doc undefined")
    val f = client
      .readDocument(selfLinkOf(doc.id), newRequestOption(doc.id.id))
      .head()
      .transform(
        { rr =>
          val js = getResultToWhiskJsonDoc(rr.getResource)
          transid.finished(this, start, s"[GET] '$collName' completed: found document '$doc'")
          deserialize[A, DocumentAbstraction](doc, js)
        }, {
          case e: DocumentClientException if isNotFound(e) =>
            transid.finished(this, start, s"[GET] '$collName', document: '$doc'; not found.")
            // for compatibility
            throw NoDocumentException("not found on 'get'")
          case e => e
        })
      .recoverWith {
        case _: DeserializationException => throw DocumentUnreadable(Messages.corruptedEntity)
      }

    reportFailure(
      f,
      start,
      failure => s"[GET] '$collName' internal error, doc: '$doc', failure: '${failure.getMessage}'")

  }

  override protected[database] def get(id: DocId)(implicit transid: TransactionId): Future[Option[JsObject]] = {
    val start = transid.started(this, LoggingMarkers.DATABASE_GET, s"[GET_BY_ID] '$collName' finding document: '$id'")

    val f = client
      .readDocument(selfLinkOf(id), newRequestOption(id.id))
      .head()
      .map { rr =>
        val js = getResultToWhiskJsonDoc(rr.getResource)
        transid.finished(this, start, s"[GET_BY_ID] '$collName' completed: found document '$id'")
        Some(js)
      }
      .recoverWith {
        case e: DocumentClientException if isNotFound(e) => Future.successful(None)
      }

    reportFailure(
      f,
      start,
      failure => s"[GET_BY_ID] '$collName' internal error, doc: '$id', failure: '${failure.getMessage}'")
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
    require(!reduce, "Reduce scenario not supported") //TODO Investigate reduce
    documentHandler.checkIfTableSupported(table)

    val Array(ddoc, viewName) = table.split("/")

    val start = transid.started(this, LoggingMarkers.DATABASE_QUERY, s"[QUERY] '$collName' searching '$table'")
    val realIncludeDocs = includeDocs | documentHandler.shouldAlwaysIncludeDocs(ddoc, viewName)
    val realLimit = if (limit > 0) skip + limit else limit

    val querySpec = viewMapper.prepareQuery(ddoc, viewName, startKey, endKey, realLimit, realIncludeDocs, descending)

    val publisher =
      RxReactiveStreams.toPublisher(client.queryDocuments(collection.getSelfLink, querySpec, newFeedOptions()))
    val f = Source
      .fromPublisher(publisher)
      .mapConcat(asSeq)
      .drop(skip)
      .map(queryResultToWhiskJsonDoc)
      .map(js =>
        documentHandler
          .transformViewResult(ddoc, viewName, startKey, endKey, realIncludeDocs, js, CosmosDBArtifactStore.this))
      .mapAsync(1)(identity)
      .mapConcat(identity)
      .runWith(Sink.seq)
      .map(_.toList)

    f.onSuccess({
      case out => transid.finished(this, start, s"[QUERY] '$collName' completed: matched ${out.size}")
    })
    reportFailure(f, start, failure => s"[QUERY] '$collName' internal error, failure: '${failure.getMessage}'")
  }

  override protected[core] def count(table: String,
                                     startKey: List[Any],
                                     endKey: List[Any],
                                     skip: Int,
                                     stale: StaleParameter)(implicit transid: TransactionId): Future[Long] = {
    val Array(ddoc, viewName) = table.split("/")

    val start = transid.started(this, LoggingMarkers.DATABASE_QUERY, s"[COUNT] '$collName' searching '$table")
    val querySpec = viewMapper.prepareCountQuery(ddoc, viewName, startKey, endKey)

    //For aggregates the value is in _aggregates fields
    val f = client
      .queryDocuments(collection.getSelfLink, querySpec, newFeedOptions())
      .head()
      .map(_.getResults.asScala.head.getLong(aggregate).longValue() - skip)

    f.onSuccess({
      case out => transid.finished(this, start, s"[COUNT] '$collName' completed: count $out")
    })

    reportFailure(f, start, failure => s"[COUNT] '$collName' internal error, failure: '${failure.getMessage}'")
  }

  override protected[core] def attach(
    doc: DocInfo,
    name: String,
    contentType: ContentType,
    docStream: Source[ByteString, _])(implicit transid: TransactionId): Future[DocInfo] = {
    val start = transid.started(
      this,
      LoggingMarkers.DATABASE_ATT_SAVE,
      s"[ATT_PUT] '$collName' uploading attachment '$name' of document '$doc'")

    checkDocHasRevision(doc)
    val options = new MediaOptions
    options.setContentType(contentType.toString())
    options.setSlug(name)

    //TODO Temporary implementation till AttachmentStore PR is merged
    val f = docStream
      .runFold(new ByteStringBuilder)((builder, b) => builder ++= b)
      .map(_.result().toArray)
      .map(new ByteArrayInputStream(_))
      .flatMap(s => client.upsertAttachment(selfLinkOf(doc.id), s, options, matchRevOption(doc)).head())
      .transform(
        { _ =>
          transid
            .finished(this, start, s"[ATT_PUT] '$collName' completed uploading attachment '$name' of document '$doc'")
          doc //Adding attachment does not change the revision of document. So retain the doc info
        }, {
          case e: DocumentClientException if isConflict(e) =>
            transid
              .finished(this, start, s"[ATT_PUT] '$collName' uploading attachment '$name' of document '$doc'; conflict")
            DocumentConflictException("conflict on 'attachment put'")
          case e => e
        })

    reportFailure(
      f,
      start,
      failure => s"[ATT_PUT] '$collName' internal error, name: '$name', doc: '$doc', failure: '${failure.getMessage}'")
  }

  override protected[core] def readAttachment[T](doc: DocInfo, name: String, sink: Sink[ByteString, Future[T]])(
    implicit transid: TransactionId): Future[(ContentType, T)] = {
    val start = transid.started(
      this,
      LoggingMarkers.DATABASE_ATT_GET,
      s"[ATT_GET] '$collName' finding attachment '$name' of document '$doc'")
    checkDocHasRevision(doc)

    val f = client
      .readAttachment(s"${selfLinkOf(doc.id)}/attachments/$name", matchRevOption(doc))
      .head()
      .flatMap(a => client.readMedia(a.getResource.getMediaLink).head())
      .transform(
        { r =>
          //Here stream can only be fetched once
          StreamConverters
            .fromInputStream(() => r.getMedia)
            .runWith(sink)
            .map((parseContentType(r), _))
        }, {
          case e: DocumentClientException if isNotFound(e) =>
            transid.finished(
              this,
              start,
              s"[ATT_GET] '$collName', retrieving attachment '$name' of document '$doc'; not found.")
            NoDocumentException("not found on 'delete'")
          case e => e
        })
      .flatMap(identity)

    reportFailure(
      f,
      start,
      failure => s"[ATT_GET] '$collName' internal error, name: '$name', doc: '$doc', failure: '${failure.getMessage}'")
  }

  override protected[core] def deleteAttachments[T](doc: DocInfo)(implicit transid: TransactionId): Future[Boolean] =
    // NOTE: this method is not intended for standalone use for CosmosDB.
    // To delete attachments, it is expected that the entire document is deleted.
    Future.successful(true)

  override def shutdown(): Unit = clientRef.close()

  private def parseContentType(r: MediaResponse): ContentType = {
    val typeString = r.getResponseHeaders.asScala.getOrElse(
      "Content-Type",
      throw new RuntimeException(s"Content-Type header not found in response ${r.getResponseHeaders}"))
    ContentType.parse(typeString) match {
      case Right(ct) => ct
      case Left(_)   => throw new RuntimeException(s"Invalid Content-Type header $typeString") //Should not happen
    }
  }

  private def isNotFound[A <: DocumentAbstraction](e: DocumentClientException) =
    e.getStatusCode == StatusCodes.NotFound.intValue

  private def isConflict(e: DocumentClientException) = {
    e.getStatusCode == StatusCodes.Conflict.intValue || e.getStatusCode == StatusCodes.PreconditionFailed.intValue
  }

  private def toCosmosDoc(json: JsObject): Document = {
    val computedJs = documentHandler.computedFields(json)
    val computedOpt = if (computedJs.fields.nonEmpty) Some(computedJs) else None
    val fieldsToAdd =
      Seq(
        (cid, Some(JsString(escapeId(json.fields(_id).convertTo[String])))),
        (etag, json.fields.get(_rev)),
        (computed, computedOpt))
    val fieldsToRemove = Seq(_id, _rev)
    val mapped = transform(json, fieldsToAdd, fieldsToRemove)
    val doc = new Document(mapped.compactPrint)
    doc.set(selfLink, createSelfLink(doc.getId))
    doc
  }

  private def queryResultToWhiskJsonDoc(doc: Document): JsObject = {
    val docJson = doc.toJson.parseJson.asJsObject
    //If includeDocs is true then document json is to be used
    val js = if (doc.has(alias)) docJson.fields(alias).asJsObject else docJson
    val id = js.fields(cid).convertTo[String]
    toWhiskJsonDoc(js, id, None)
  }

  private def getResultToWhiskJsonDoc(doc: Document): JsObject = {
    checkDoc(doc)
    val js = doc.toJson.parseJson.asJsObject
    toWhiskJsonDoc(js, doc.getId, Some(JsString(doc.getETag)))
  }

  private def toWhiskJsonDoc(js: JsObject, id: String, etag: Option[JsString]): JsObject = {
    val fieldsToAdd = Seq((_id, Some(JsString(unescapeId(id)))), (_rev, etag))
    transform(stripInternalFields(js), fieldsToAdd, Seq.empty)
  }

  private def transform(json: JsObject, fieldsToAdd: Seq[(String, Option[JsValue])], fieldsToRemove: Seq[String]) = {
    val fields = json.fields ++ fieldsToAdd.flatMap(f => f._2.map((f._1, _))) -- fieldsToRemove
    JsObject(fields)
  }

  private def stripInternalFields(js: JsObject) = {
    //Strip out all field name starting with '_' which are considered as db specific internal fields
    JsObject(js.fields.filter { case (k, _) => !k.startsWith("_") && k != cid })
  }

  private def toDocInfo[T <: Resource](doc: T) = {
    checkDoc(doc)
    DocInfo(DocId(unescapeId(doc.getId)), DocRevision(doc.getETag))
  }

  private def selfLinkOf(id: DocId) = createSelfLink(escapeId(id.id))

  private def createSelfLink(id: String) = s"dbs/${database.getId}/colls/${collection.getId}/docs/$id"

  private def matchRevOption(info: DocInfo): RequestOptions = matchRevOption(info.id.id, info.rev.rev)

  private def matchRevOption(id: String, etag: String): RequestOptions = {
    val options = newRequestOption(id)
    val condition = new AccessCondition
    condition.setCondition(etag)
    options.setAccessCondition(condition)
    options
  }

  private def newRequestOption(id: String) = {
    val options = new RequestOptions
    options.setPartitionKey(new PartitionKey(escapeId(id)))
    options
  }

  private def newFeedOptions() = {
    val options = new FeedOptions()
    options.setEnableCrossPartitionQuery(true)
    options
  }

  private def checkDoc[T <: Resource](doc: T): Unit = {
    require(doc.getId != null, s"$doc does not have id field set")
    require(doc.getETag != null, s"$doc does not have etag field set")
  }
}
