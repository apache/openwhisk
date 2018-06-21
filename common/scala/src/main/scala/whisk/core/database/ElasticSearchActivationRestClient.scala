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

import java.time.Instant

import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import akka.stream.scaladsl.Flow

import spray.json.{DefaultJsonProtocol, _}

import whisk.common.TransactionId
import whisk.core.containerpool.logging.ElasticSearchJsonProtocol._
import whisk.core.containerpool.logging.{ElasticSearchRestClient, EsQuery, EsQueryString, EsSearchResult, _}
import whisk.core.entity._

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.Try

case class ElasticSearchActivationFieldConfig(name: String,
                                              namespace: String,
                                              subject: String,
                                              version: String,
                                              start: String,
                                              end: String,
                                              duration: String,
                                              result: String,
                                              statusCode: String,
                                              activationId: String,
                                              activationRecord: String,
                                              stream: String)

case class ElasticSearchActivationStoreConfig(protocol: String,
                                              host: String,
                                              port: Int,
                                              path: String,
                                              schema: ElasticSearchActivationFieldConfig,
                                              requiredHeaders: Seq[String] = Seq.empty)

trait ElasticSearchActivationRestClient {
  implicit val executionContext: ExecutionContext
  implicit val system: ActorSystem
  val httpFlow: Option[Flow[(HttpRequest, Promise[HttpResponse]), (Try[HttpResponse], Promise[HttpResponse]), Any]]
  val elasticSearchConfig: ElasticSearchActivationStoreConfig

  protected val esActivationClient =
    new ElasticSearchRestClient(
      elasticSearchConfig.protocol,
      elasticSearchConfig.host,
      elasticSearchConfig.port,
      httpFlow)

  // Schema of resultant activations from ES
  case class ActivationEntry(name: String,
                             subject: String,
                             activationId: String,
                             version: String,
                             start: Long,
                             end: Long,
                             result: String,
                             statusCode: Int,
                             duration: Option[Long] = None,
                             namespace: String,
                             kind: Option[String] = None,
                             cause: Option[String] = None,
                             causedBy: Option[String] = None,
                             limits: Option[ActionLimits] = None,
                             path: Option[String] = None,
                             logs: ActivationLogs,
                             waitTime: Option[Int] = None,
                             initTime: Option[Int] = None) {

    def toActivation() = {
      val response = statusCode match {
        case 0 => ActivationResponse.success(Some(result.parseJson.asJsObject))
        case 1 => ActivationResponse.applicationError(result.parseJson.asJsObject.fields("error"))
        case 2 => ActivationResponse.containerError(result.parseJson.asJsObject.fields("error"))
        case 3 => ActivationResponse.whiskError(result.parseJson.asJsObject.fields("error"))
      }
      val causedByAnnotation = causedBy.map(value => Parameters("causedBy", value.toJson)).getOrElse(Parameters())
      val memoryAnnotation = limits
        .map { value =>
          Parameters(
            "limits",
            JsObject(
              "memory" -> value.memory.megabytes.toJson,
              "timeout" -> value.timeout.toJson,
              "logs" -> value.logs.toJson))
        }
        .getOrElse(Parameters())
      val kindAnnotation = kind.map(value => Parameters("kind", value.toJson)).getOrElse(Parameters())
      val pathAnnotation = path.map(value => Parameters("path", value.toJson)).getOrElse(Parameters())
      val waitTimeAnnotation = waitTime.map(value => Parameters("waitTime", value.toJson)).getOrElse(Parameters())
      val initTimeAnnotation = initTime.map(value => Parameters("initTime", value.toJson)).getOrElse(Parameters())

      WhiskActivation(
        EntityPath(namespace),
        EntityName(name),
        Subject(subject),
        ActivationId(activationId),
        Instant.ofEpochMilli(start),
        Instant.ofEpochMilli(end),
        response = response,
        logs = logs,
        duration = duration,
        version = SemVer(version),
        annotations = kindAnnotation ++ causedByAnnotation ++ memoryAnnotation ++ pathAnnotation ++ waitTimeAnnotation ++ initTimeAnnotation,
        cause = cause.map(value => Some(ActivationId(value))).getOrElse(None))
    }
  }

  object ActivationEntry extends DefaultJsonProtocol {
    implicit val serdes =
      jsonFormat(
        ActivationEntry.apply,
        elasticSearchConfig.schema.name,
        elasticSearchConfig.schema.subject,
        elasticSearchConfig.schema.activationId,
        elasticSearchConfig.schema.version,
        elasticSearchConfig.schema.start,
        elasticSearchConfig.schema.end,
        "result",
        "statusCode",
        elasticSearchConfig.schema.duration,
        elasticSearchConfig.schema.namespace,
        "kind",
        "cause",
        "causedBy",
        "limits",
        "path",
        "logs",
        "waitTime",
        "initTime")
  }

  protected def transcribeActivations(queryResult: EsSearchResult): List[ActivationEntry] =
    queryResult.hits.hits.map(_.source.convertTo[ActivationEntry]).toList

  protected def getRanges(since: Option[Instant] = None, upto: Option[Instant] = None) = {
    val sinceRange: Option[EsQueryRange] = since.map { time =>
      Some(EsQueryRange(elasticSearchConfig.schema.start, EsRangeGt, time.toEpochMilli.toString))
    } getOrElse None
    val uptoRange: Option[EsQueryRange] = upto.map { time =>
      Some(EsQueryRange(elasticSearchConfig.schema.start, EsRangeLt, time.toEpochMilli.toString))
    } getOrElse None

    Vector(sinceRange, uptoRange).flatten
  }

  protected def generateGetPayload(activationId: String) = {
    val query =
      s"_type: ${elasticSearchConfig.schema.activationRecord} AND ${elasticSearchConfig.schema.activationId}: $activationId"

    EsQuery(EsQueryString(query))
  }

  protected def generateCountActivationsInNamespacePayload(name: Option[EntityPath] = None,
                                                           skip: Int,
                                                           since: Option[Instant] = None,
                                                           upto: Option[Instant] = None) = {
    val queryRanges = getRanges(since, upto)
    val activationMatch = Some(EsQueryBoolMatch("_type", elasticSearchConfig.schema.activationRecord))
    val entityMatch: Option[EsQueryBoolMatch] = name.map { n =>
      Some(EsQueryBoolMatch(elasticSearchConfig.schema.name, n.asString))
    } getOrElse None
    val queryTerms = Vector(activationMatch, entityMatch).flatten
    val queryMust = EsQueryMust(queryTerms, queryRanges)
    val queryOrder = EsQueryOrder(elasticSearchConfig.schema.start, EsOrderDesc)

    EsQuery(queryMust, Some(queryOrder), from = skip)
  }

  protected def generateListActiationsMatchNamePayload(name: String,
                                                       skip: Int,
                                                       limit: Int,
                                                       since: Option[Instant] = None,
                                                       upto: Option[Instant] = None) = {
    val queryRanges = getRanges(since, upto)
    val queryTerms = Vector(
      EsQueryBoolMatch("_type", elasticSearchConfig.schema.activationRecord),
      EsQueryBoolMatch(elasticSearchConfig.schema.name, name))
    val queryMust = EsQueryMust(queryTerms, queryRanges)
    val queryOrder = EsQueryOrder(elasticSearchConfig.schema.start, EsOrderDesc)

    EsQuery(queryMust, Some(queryOrder), Some(limit), from = skip)
  }

  protected def generateListActivationsInNamespacePayload(namespace: String,
                                                          skip: Int,
                                                          limit: Int,
                                                          since: Option[Instant] = None,
                                                          upto: Option[Instant] = None) = {
    val queryRanges = getRanges(since, upto)
    val queryTerms = Vector(
      EsQueryBoolMatch("_type", elasticSearchConfig.schema.activationRecord),
      EsQueryBoolMatch(elasticSearchConfig.schema.subject, namespace))
    val queryMust = EsQueryMust(queryTerms, queryRanges)
    val queryOrder = EsQueryOrder(elasticSearchConfig.schema.start, EsOrderDesc)

    EsQuery(queryMust, Some(queryOrder), Some(limit), from = skip)
  }

  def getActivation(activationId: String, uuid: String, headers: List[HttpHeader] = List.empty)(
    implicit transid: TransactionId): Future[ActivationEntry] = {
    val payload = generateGetPayload(activationId)

    esActivationClient.search[EsSearchResult](uuid, payload, headers).flatMap {
      case Right(queryResult) =>
        val res = transcribeActivations(queryResult)

        if (res.nonEmpty) {
          Future.successful(res.head)
        } else {
          Future.failed(new NoDocumentException("Document not found"))
        }

      case Left(code) =>
        Future.failed(new RuntimeException(s"Status code '$code' was returned from activation store"))
    }
  }

  def count(uuid: String,
            name: Option[EntityPath] = None,
            namespace: String,
            skip: Int,
            since: Option[Instant] = None,
            upto: Option[Instant] = None,
            headers: List[HttpHeader] = List.empty)(implicit transid: TransactionId): Future[JsObject] = {
    val payload = generateCountActivationsInNamespacePayload(name, skip, since, upto)

    esActivationClient.search[EsSearchResult](uuid, payload, headers).flatMap {
      case Right(queryResult) =>
        val total = Math.max(0, queryResult.hits.total - skip)
        Future.successful(JsObject("activations" -> total.toJson))
      case Left(code) =>
        Future.failed(new RuntimeException(s"Status code '$code' was returned from activation store"))
    }
  }

  def listActivationMatching(
    uuid: String,
    name: String,
    skip: Int,
    limit: Int,
    since: Option[Instant] = None,
    upto: Option[Instant] = None,
    headers: List[HttpHeader] = List.empty)(implicit transid: TransactionId): Future[List[ActivationEntry]] = {
    val payload = generateListActiationsMatchNamePayload(name, skip, limit, since, upto)

    esActivationClient.search[EsSearchResult](uuid, payload, headers).flatMap {
      case Right(queryResult) =>
        Future.successful(transcribeActivations(queryResult))
      case Left(code) =>
        Future.failed(new RuntimeException(s"Status code '$code' was returned from activation store"))
    }
  }

  def listActivationsNamespace(
    uuid: String,
    namespace: String,
    skip: Int,
    limit: Int,
    since: Option[Instant] = None,
    upto: Option[Instant] = None,
    headers: List[HttpHeader] = List.empty)(implicit transid: TransactionId): Future[List[ActivationEntry]] = {
    val payload = generateListActivationsInNamespacePayload(namespace, skip, limit, since, upto)

    esActivationClient.search[EsSearchResult](uuid, payload, headers).flatMap {
      case Right(queryResult) =>
        Future.successful(transcribeActivations(queryResult))
      case Left(code) =>
        Future.failed(new RuntimeException(s"Status code '$code' was returned from activation store"))
    }
  }

}
