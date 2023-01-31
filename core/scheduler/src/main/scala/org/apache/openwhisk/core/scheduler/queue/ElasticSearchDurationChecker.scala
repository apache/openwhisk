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
package org.apache.openwhisk.core.scheduler.queue

import akka.actor.ActorSystem
import com.sksamuel.elastic4s.http.ElasticDsl._
import com.sksamuel.elastic4s.http.{ElasticClient, ElasticProperties, NoOpRequestConfigCallback}
import com.sksamuel.elastic4s.searches.queries.Query
import com.sksamuel.elastic4s.{ElasticDate, ElasticDateMath, Seconds}
import org.apache.openwhisk.common.Logging
import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.core.entity.WhiskActionMetaData
import org.apache.openwhisk.spi.Spi
import pureconfig.loadConfigOrThrow
import spray.json.{JsArray, JsNumber, JsValue, RootJsonFormat, deserializationError, _}

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.language.implicitConversions
import scala.util.{Failure, Try}
import pureconfig.generic.auto._

trait DurationChecker {
  def checkAverageDuration(invocationNamespace: String, actionMetaData: WhiskActionMetaData)(
    callback: DurationCheckResult => DurationCheckResult): Future[DurationCheckResult]
}

case class DurationCheckResult(averageDuration: Option[Double], hitCount: Long, took: Long)

object ElasticSearchDurationChecker {
  val FilterAggregationName = "filterAggregation"
  val AverageAggregationName = "averageAggregation"

  implicit val serde = new ElasticSearchDurationCheckResultFormat()

  def getFromDate(timeWindow: FiniteDuration): ElasticDateMath =
    ElasticDate.now minus (timeWindow.toSeconds.toInt, Seconds)
}

class ElasticSearchDurationChecker(private val client: ElasticClient, val timeWindow: FiniteDuration)(
  implicit val actorSystem: ActorSystem,
  implicit val logging: Logging)
    extends DurationChecker {
  import ElasticSearchDurationChecker._
  import org.apache.openwhisk.core.database.elasticsearch.ElasticSearchActivationStore.generateIndex

  implicit val ec = actorSystem.getDispatcher

  override def checkAverageDuration(invocationNamespace: String, actionMetaData: WhiskActionMetaData)(
    callback: DurationCheckResult => DurationCheckResult): Future[DurationCheckResult] = {
    val index = generateIndex(invocationNamespace)
    val fqn = actionMetaData.fullyQualifiedName(false)
    val fromDate = getFromDate(timeWindow)

    logging.info(this, s"check average duration for $fqn in $index for last $timeWindow")

    actionMetaData.binding match {
      case Some(binding) =>
        val boolQueryResult = List(
          matchQuery("annotations.binding", s"$binding"),
          matchQuery("name", actionMetaData.name),
          rangeQuery("@timestamp").gte(fromDate))

        executeQuery(boolQueryResult, callback, index)

      case None =>
        val queryResult = List(matchQuery("path.keyword", fqn.toString), rangeQuery("@timestamp").gte(fromDate))

        executeQuery(queryResult, callback, index)
    }
  }

  private def executeQuery(boolQueryResult: List[Query],
                           callback: DurationCheckResult => DurationCheckResult,
                           index: String) = {
    client
      .execute {
        (search(index) query {
          boolQuery must {
            boolQueryResult
          }
        } aggregations
          avgAgg(AverageAggregationName, "duration")).size(0)
      }
      .map { res =>
        logging.debug(this, s"ElasticSearch query results: $res")
        Try(serde.read(res.body.getOrElse("").parseJson))
      }
      .flatMap(Future.fromTry)
      .map(callback(_))
      .andThen {
        case Failure(t) =>
          logging.error(this, s"failed to check the average duration: ${t}")
      }
  }
}

object ElasticSearchDurationCheckerProvider extends DurationCheckerProvider {
  import org.apache.openwhisk.core.database.elasticsearch.ElasticSearchActivationStore._

  override def instance(actorSystem: ActorSystem, log: Logging): ElasticSearchDurationChecker = {
    implicit val as: ActorSystem = actorSystem
    implicit val logging: Logging = log

    val elasticClient =
      ElasticClient(
        ElasticProperties(s"${elasticSearchConfig.protocol}://${elasticSearchConfig.hosts}"),
        NoOpRequestConfigCallback,
        httpClientCallback)

    new ElasticSearchDurationChecker(elasticClient, durationCheckerConfig.timeWindow)
  }
}

trait DurationCheckerProvider extends Spi {

  val durationCheckerConfig: DurationCheckerConfig =
    loadConfigOrThrow[DurationCheckerConfig](ConfigKeys.durationChecker)

  def instance(actorSystem: ActorSystem, logging: Logging): DurationChecker
}

class ElasticSearchDurationCheckResultFormat extends RootJsonFormat[DurationCheckResult] {
  import ElasticSearchDurationChecker._
  import spray.json.DefaultJsonProtocol._

  /**
   * Expected sample data
      {
          "_shards": {
              "failed": 0,
              "skipped": 0,
              "successful": 5,
              "total": 5
          },
          "aggregations": {
              "agg": {
                  "value": 14
              }
          },
          "hits": {
              "hits": [],
              "max_score": 0,
              "total": 3
          },
          "timed_out": false,
          "took": 0
      }
   */
  /**
   * Expected sample data
      {
          "_shards": {
              "failed": 0,
              "skipped": 0,
              "successful": 5,
              "total": 5
          },
          "aggregations": {
              "pathAggregation": {
                  "avg_duration": {
                      "value": 13
                  },
                  "doc_count": 3
              }
          },
          "hits": {
              "hits": [],
              "max_score": 0,
              "total": 6
          },
          "timed_out": false,
          "took": 0
      }
   */
  implicit def read(json: JsValue) = {
    val jsObject = json.asJsObject

    jsObject.getFields("aggregations", "took", "hits") match {
      case Seq(aggregations, took, hits) =>
        val hitCount = hits.asJsObject.getFields("total").headOption
        val filterAggregations = aggregations.asJsObject.getFields(FilterAggregationName)
        val averageAggregations = aggregations.asJsObject.getFields(AverageAggregationName)

        (filterAggregations, averageAggregations, hitCount) match {
          case (filterAggregations, _, Some(count)) if filterAggregations.nonEmpty =>
            val averageDuration =
              filterAggregations.headOption.flatMap(
                _.asJsObject
                  .getFields(AverageAggregationName)
                  .headOption
                  .flatMap(_.asJsObject.getFields("value").headOption))

            averageDuration match {
              case Some(JsNull) =>
                DurationCheckResult(None, count.convertTo[Long], took.convertTo[Long])

              case Some(duration) =>
                DurationCheckResult(Some(duration.convertTo[Double]), count.convertTo[Long], took.convertTo[Long])

              case _ => deserializationError("Cannot deserialize ProductItem: invalid input. Raw input: ")
            }

          case (_, averageAggregations, Some(count)) if averageAggregations.nonEmpty =>
            val averageDuration = averageAggregations.headOption.flatMap(_.asJsObject.getFields("value").headOption)

            averageDuration match {
              case Some(JsNull) =>
                DurationCheckResult(None, count.convertTo[Long], took.convertTo[Long])

              case Some(duration) =>
                DurationCheckResult(Some(duration.convertTo[Double]), count.convertTo[Long], took.convertTo[Long])

              case t => deserializationError(s"Cannot deserialize DurationCheckResult: invalid input. Raw input: $t")
            }

          case t => deserializationError(s"Cannot deserialize DurationCheckResult: invalid input. Raw input: $t")
        }

      case other => deserializationError(s"Cannot deserialize DurationCheckResult: invalid input. Raw input: $other")
    }

  }

  // This method would not be used.
  override def write(obj: DurationCheckResult): JsValue = {
    JsArray(JsNumber(obj.averageDuration.get), JsNumber(obj.hitCount), JsNumber(obj.took))
  }
}

case class DurationCheckerConfig(timeWindow: FiniteDuration)
