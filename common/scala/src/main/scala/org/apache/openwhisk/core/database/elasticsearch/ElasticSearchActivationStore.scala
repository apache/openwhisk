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

package org.apache.openwhisk.core.database.elasticsearch

import java.time.Instant
import java.util.concurrent.TimeUnit

import scala.language.postfixOps
import akka.actor.ActorSystem
import akka.event.Logging.ErrorLevel
import akka.http.scaladsl.model._
import akka.stream.scaladsl.Flow
import akka.stream._
import com.sksamuel.elastic4s.http.search.SearchHit
import com.sksamuel.elastic4s.http.{ElasticClient, ElasticProperties, NoOpRequestConfigCallback}
import com.sksamuel.elastic4s.indexes.IndexRequest
import com.sksamuel.elastic4s.searches.queries.RangeQuery
import com.sksamuel.elastic4s.searches.queries.matches.MatchPhrase
import org.apache.http.auth.{AuthScope, UsernamePasswordCredentials}
import org.apache.http.impl.client.BasicCredentialsProvider
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder
import pureconfig.loadConfigOrThrow
import pureconfig.generic.auto._
import spray.json._
import org.apache.openwhisk.common.{Logging, LoggingMarkers, TransactionId}
import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.core.containerpool.logging.ElasticSearchJsonProtocol._
import org.apache.openwhisk.core.database._
import org.apache.openwhisk.core.database.StoreUtils._
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.http.Messages
import org.elasticsearch.client.RestClientBuilder.HttpClientConfigCallback

import scala.concurrent.{ExecutionContextExecutor, Future, Promise}
import scala.util.Try

case class ElasticSearchActivationStoreConfig(protocol: String,
                                              hosts: String,
                                              indexPattern: String,
                                              username: String,
                                              password: String)

class ElasticSearchActivationStore(
  httpFlow: Option[Flow[(HttpRequest, Promise[HttpResponse]), (Try[HttpResponse], Promise[HttpResponse]), Any]] = None,
  elasticSearchConfig: ElasticSearchActivationStoreConfig =
    loadConfigOrThrow[ElasticSearchActivationStoreConfig](ConfigKeys.elasticSearchActivationStore),
  useBatching: Boolean = false)(implicit actorSystem: ActorSystem,
                                actorMaterializer: ActorMaterializer,
                                logging: Logging)
    extends ActivationStore {

  import com.sksamuel.elastic4s.http.ElasticDsl._

  private implicit val executionContextExecutor: ExecutionContextExecutor = actorSystem.dispatcher

  private val httpClientCallback = new HttpClientConfigCallback {
    override def customizeHttpClient(httpClientBuilder: HttpAsyncClientBuilder): HttpAsyncClientBuilder = {
      val provider = new BasicCredentialsProvider
      provider.setCredentials(
        AuthScope.ANY,
        new UsernamePasswordCredentials(elasticSearchConfig.username, elasticSearchConfig.password))
      httpClientBuilder.setDefaultCredentialsProvider(provider)
    }
  }

  private val client =
    ElasticClient(
      ElasticProperties(s"${elasticSearchConfig.protocol}://${elasticSearchConfig.hosts}"),
      NoOpRequestConfigCallback,
      httpClientCallback)

  private val esType = "_doc"
  private val maxOpenDbRequests = actorSystem.settings.config
    .getInt("akka.http.host-connection-pool.max-connections") / 2
  private val batcher: Batcher[IndexRequest, Either[ArtifactStoreException, DocInfo]] =
    new Batcher(500, maxOpenDbRequests)(doStore(_)(TransactionId.dbBatcher))

  private val minStart = 0L
  private val maxStart = Instant.now.toEpochMilli + TimeUnit.DAYS.toMillis(365 * 100) //100 years from now

  override def store(activation: WhiskActivation, context: UserContext)(
    implicit transid: TransactionId,
    notifier: Option[CacheChangeNotification]): Future[DocInfo] = {

    val start =
      transid.started(this, LoggingMarkers.DATABASE_SAVE, s"[PUT] 'activations' document: '${activation.docid}'")

    val bindingPath = activation.annotations
      .getAs[String](WhiskActivation.bindingAnnotation)
      .toOption
      .map(binding => s"$binding/${activation.name}")

    val path = bindingPath.getOrElse(
      activation.annotations
        .getAs[String](WhiskActivation.pathAnnotation)
        .getOrElse(s"${activation.namespace}/${activation.name}"))

    // Escape `_id` field as it's not permitted in ElasticSearch, add `path` field for search, and
    // convert annotations to JsObject as ElasticSearch doesn't support array with mixed types
    // response.result can be any type ElasticSearch also doesn't support that, so convert it to a string
    val response = JsObject(
      activation.response.toJsonObject.fields
        .updated("result", JsString(activation.response.result.toJson.compactPrint)))
    val payload = JsObject(
      activation.toDocumentRecord.fields - "_id" ++ Map(
        "path" -> JsString(path),
        "@timestamp" -> JsString(activation.start.toString),
        "annotations" -> activation.annotations.toJsObject,
        "response" -> response))

    val index = generateIndex(activation.namespace.namespace)
    val op = indexInto(index, esType).doc(payload.toString).id(activation.docid.asString)

    // always use batching
    val res = batcher.put(op).map {
      case Right(docInfo) =>
        transid
          .finished(this, start, s"[PUT] 'activations' completed document: '${activation.docid}', response: '$docInfo'")
        docInfo
      case Left(e: ArtifactStoreException) =>
        transid.failed(
          this,
          start,
          s"[PUT] 'activations' failed to put document: '${activation.docid}'; ${e.getMessage}.",
          ErrorLevel)
        throw PutException("error on 'put'")
    }

    reportFailure(res, start, failure => s"[PUT] 'activations' internal error, failure: '${failure.getMessage}'")
  }

  private def doStore(ops: Seq[IndexRequest])(
    implicit transid: TransactionId): Future[Seq[Either[ArtifactStoreException, DocInfo]]] = {
    val count = ops.size
    val start = transid.started(this, LoggingMarkers.DATABASE_BULK_SAVE, s"'activations' saving $count documents")
    val res = client
      .execute {
        bulk(ops)
      }
      .map { res =>
        if (res.status == StatusCodes.OK.intValue || res.status == StatusCodes.Created.intValue) {
          res.result.items.map { bulkRes =>
            if (bulkRes.status == StatusCodes.OK.intValue || bulkRes.status == StatusCodes.Created.intValue)
              Right(DocInfo(bulkRes.id))
            else
              Left(PutException(
                s"Unexpected error: ${bulkRes.error.map(e => s"${e.`type`}:${e.reason}").getOrElse("unknown")}, code: ${bulkRes.status} on 'bulk_put'"))
          }
        } else {
          transid.failed(
            this,
            start,
            s"'activations' failed to put documents, http status: '${res.status}'",
            ErrorLevel)
          throw PutException("Unexpected http response code: " + res.status)
        }
      }

    reportFailure(res, start, failure => s"[PUT] 'activations' internal error, failure: '${failure.getMessage}'")
  }

  override def get(activationId: ActivationId, context: UserContext)(
    implicit transid: TransactionId): Future[WhiskActivation] = {

    val start =
      transid.started(this, LoggingMarkers.DATABASE_GET, s"[GET] 'activations' finding activation: '$activationId'")

    val index = generateIndex(extractNamespace(activationId))
    val res = client
      .execute {
        search(index) query { termQuery("_id", activationId.asString) }
      }
      .map { res =>
        if (res.status == StatusCodes.OK.intValue) {
          if (res.result.hits.total == 0) {
            transid.finished(this, start, s"[GET] 'activations', document: '$activationId'; not found.")
            throw NoDocumentException("not found on 'get'")
          } else {
            transid.finished(this, start, s"[GET] 'activations' completed: found activation '$activationId'")
            deserializeHitToWhiskActivation(res.result.hits.hits(0))
          }
        } else if (res.status == StatusCodes.NotFound.intValue) {
          transid.finished(this, start, s"[GET] 'activations', document: '$activationId'; not found.")
          throw NoDocumentException("not found on 'get'")
        } else {
          transid
            .finished(
              this,
              start,
              s"[GET] 'activations' failed to get document: '$activationId'; http status: '${res.status}'")
          throw GetException("Unexpected http response code: " + res.status)
        }
      } recoverWith {
      case _: DeserializationException => throw DocumentUnreadable(Messages.corruptedEntity)
    }

    reportFailure(
      res,
      start,
      failure => s"[GET] 'activations' internal error, doc: '$activationId', failure: '${failure.getMessage}'")
  }

  override def delete(activationId: ActivationId, context: UserContext)(
    implicit transid: TransactionId,
    notifier: Option[CacheChangeNotification]): Future[Boolean] = {

    val start =
      transid.started(this, LoggingMarkers.DATABASE_DELETE, s"[DEL] 'activations' deleting document: '$activationId'")

    val index = generateIndex(extractNamespace(activationId))

    val res = client
      .execute {
        deleteByQuery(index, esType, termQuery("_id", activationId.asString))
      }
      .map { res =>
        if (res.status == StatusCodes.OK.intValue) {
          if (res.result.deleted == 0) {
            transid.finished(this, start, s"[DEL] 'activations', document: '$activationId'; not found.")
            throw NoDocumentException("not found on 'delete'")
          } else {
            transid
              .finished(
                this,
                start,
                s"[DEL] 'activations' completed document: '$activationId', response: ${res.result}")
            true
          }
        } else if (res.status == StatusCodes.NotFound.intValue) {
          transid.finished(this, start, s"[DEL] 'activations', document: '$activationId'; not found.")
          throw NoDocumentException("not found on 'delete'")
        } else {
          transid.failed(
            this,
            start,
            s"[DEL] 'activations' failed to delete document: '$activationId'; http status: '${res.status}'",
            ErrorLevel)
          throw DeleteException("Unexpected http response code: " + res.status)
        }
      }

    reportFailure(
      res,
      start,
      failure => s"[DEL] 'activations' internal error, doc: '$activationId', failure: '${failure.getMessage}'")
  }

  override def countActivationsInNamespace(namespace: EntityPath,
                                           name: Option[EntityPath] = None,
                                           skip: Int,
                                           since: Option[Instant] = None,
                                           upto: Option[Instant] = None,
                                           context: UserContext)(implicit transid: TransactionId): Future[JsObject] = {
    require(skip >= 0, "skip should be non negative")
    val start = transid.started(this, LoggingMarkers.DATABASE_QUERY, s"[COUNT] 'activations'")

    val nameQuery = name
      .map { path =>
        matchPhraseQuery("path", namespace.addPath(path).asString)
      }
      .getOrElse {
        matchPhraseQuery("namespace", namespace.asString)
      }
    val startRange = generateRangeQuery("start", since, upto)

    val index = generateIndex(namespace.namespace)

    val res = client
      .execute {
        count(index) query { must(nameQuery, startRange) }
      }
      .map { res =>
        if (res.status == StatusCodes.OK.intValue) {
          val out = if (res.result.count > skip) res.result.count - skip else 0L
          transid.finished(this, start, s"[COUNT] 'activations' completed: count $out")
          JsObject(WhiskActivation.collectionName -> JsNumber(out))
        } else {
          transid.failed(this, start, s"Unexpected http response code: ${res.status}", ErrorLevel)
          throw QueryException("Unexpected http response code: " + res.status)
        }
      }

    reportFailure(res, start, failure => s"[COUNT] 'activations' internal error, failure: '${failure.getMessage}'")
  }

  override def listActivationsMatchingName(
    namespace: EntityPath,
    name: EntityPath,
    skip: Int,
    limit: Int,
    includeDocs: Boolean = false,
    since: Option[Instant] = None,
    upto: Option[Instant] = None,
    context: UserContext)(implicit transid: TransactionId): Future[Either[List[JsObject], List[WhiskActivation]]] = {

    val nameQuery = matchPhraseQuery("path", namespace.addPath(name).asString)
    listActivations(namespace, skip, limit, nameQuery, includeDocs, since, upto, context)
  }

  override def listActivationsInNamespace(
    namespace: EntityPath,
    skip: Int,
    limit: Int,
    includeDocs: Boolean = false,
    since: Option[Instant] = None,
    upto: Option[Instant] = None,
    context: UserContext)(implicit transid: TransactionId): Future[Either[List[JsObject], List[WhiskActivation]]] = {

    val nameQuery = matchPhraseQuery("namespace", namespace.asString)
    listActivations(namespace, skip, limit, nameQuery, includeDocs, since, upto, context)
  }

  private def listActivations(
    namespace: EntityPath,
    skip: Int,
    limit: Int,
    nameQuery: MatchPhrase,
    includeDocs: Boolean = false,
    since: Option[Instant] = None,
    upto: Option[Instant] = None,
    context: UserContext)(implicit transid: TransactionId): Future[Either[List[JsObject], List[WhiskActivation]]] = {

    require(skip >= 0, "skip should be non negative")
    require(limit >= 0, "limit should be non negative")

    val start = transid.started(this, LoggingMarkers.DATABASE_QUERY, s"[QUERY] 'activations'")
    val startRange = generateRangeQuery("start", since, upto)
    val index = generateIndex(namespace.namespace)

    val res = client
      .execute {
        search(index) query { must(nameQuery, startRange) } sortByFieldDesc "start" limit limit from skip
      }
      .map { res =>
        if (res.status == StatusCodes.OK.intValue) {
          val out =
            if (includeDocs)
              Right(res.result.hits.hits.map(deserializeHitToWhiskActivation).toList)
            else
              Left(res.result.hits.hits.map(deserializeHitToWhiskActivation(_).summaryAsJson).toList)
          transid.finished(this, start, s"[QUERY] 'activations' completed: matched ${res.result.hits.total}")
          out

        } else {
          transid.failed(this, start, s"Unexpected http response code: ${res.status}", ErrorLevel)
          throw QueryException("Unexpected http response code: " + res.status)
        }
      }

    reportFailure(res, start, failure => s"failed to query activation with error ${failure.getMessage}")
  }

  private def deserializeHitToWhiskActivation(hit: SearchHit): WhiskActivation = {
    restoreAnnotations(restoreResponse(hit.sourceAsString.parseJson.asJsObject)).convertTo[WhiskActivation]
  }

  private def restoreAnnotations(js: JsObject): JsObject = {
    val annotations = js.fields
      .get("annotations")
      .map { anno =>
        Try {
          JsArray(anno.asJsObject.fields map { p =>
            JsObject("key" -> JsString(p._1), "value" -> p._2)
          } toSeq: _*)
        }.getOrElse(JsArray.empty)
      }
      .getOrElse(JsArray.empty)
    JsObject(js.fields.updated("annotations", annotations))
  }

  private def restoreResponse(js: JsObject): JsObject = {
    val response = js.fields
      .get("response")
      .map { res =>
        val temp = res.asJsObject.fields
        Try {
          val result = temp
            .get("result")
            .map { r =>
              val JsString(data) = r
              data.parseJson.asJsObject
            }
            .getOrElse(JsObject.empty)
          JsObject(temp.updated("result", result))
        }.getOrElse(JsObject(temp - "result"))
      }
      .getOrElse(JsObject.empty)
    JsObject(js.fields.updated("response", response))
  }

  private def extractNamespace(activationId: ActivationId): String = {
    activationId.toString.split("/")(0)
  }

  private def generateIndex(namespace: String): String = {
    elasticSearchConfig.indexPattern.dropWhile(_ == '/') format namespace.toLowerCase
  }

  private def generateRangeQuery(key: String, since: Option[Instant], upto: Option[Instant]): RangeQuery = {
    rangeQuery(key)
      .gte(since.map(_.toEpochMilli).getOrElse(minStart))
      .lte(upto.map(_.toEpochMilli).getOrElse(maxStart))
  }
}

object ElasticSearchActivationStoreProvider extends ActivationStoreProvider {
  override def instance(actorSystem: ActorSystem, actorMaterializer: ActorMaterializer, logging: Logging) =
    new ElasticSearchActivationStore(useBatching = true)(actorSystem, actorMaterializer, logging)
}
