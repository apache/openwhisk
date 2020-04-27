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

package org.apache.openwhisk.core.containerpool.logging

import java.nio.file.{Path, Paths}
import java.time.Instant

import akka.actor.ActorSystem
import akka.stream.scaladsl.Flow
import akka.http.scaladsl.model._
import org.apache.openwhisk.core.entity.{ActivationId, ActivationLogs, Identity}
import org.apache.openwhisk.core.containerpool.logging.ElasticSearchJsonProtocol._
import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.core.database.UserContext

import scala.concurrent.{Future, Promise}
import scala.util.Try
import spray.json._
import pureconfig._
import pureconfig.generic.auto._

case class ElasticSearchLogFieldConfig(userLogs: String,
                                       message: String,
                                       tenantId: String,
                                       activationId: String,
                                       stream: String,
                                       time: String)

case class ElasticSearchLogStoreConfig(protocol: String,
                                       host: String,
                                       port: Int,
                                       path: String,
                                       logSchema: ElasticSearchLogFieldConfig,
                                       requiredHeaders: Seq[String] = Seq.empty)

/**
 * ElasticSearch based implementation of a DockerToActivationFileLogStore. When using the JSON log driver, docker writes
 * stdout/stderr to JSON formatted files. Those files can be processed by a backend service asynchronously to store
 * user logs in ElasticSearch. This log store allows user logs then to be fetched from ElasticSearch.
 */
class ElasticSearchLogStore(
  system: ActorSystem,
  httpFlow: Option[Flow[(HttpRequest, Promise[HttpResponse]), (Try[HttpResponse], Promise[HttpResponse]), Any]] = None,
  destinationDirectory: Path = Paths.get("logs"),
  elasticSearchConfig: ElasticSearchLogStoreConfig =
    loadConfigOrThrow[ElasticSearchLogStoreConfig](ConfigKeys.logStoreElasticSearch))
    extends DockerToActivationFileLogStore(system, destinationDirectory) {

  // Schema of resultant logs from ES
  case class UserLogEntry(message: String, stream: String, time: String) {
    def toFormattedString = s"${time} ${stream}: ${message.stripLineEnd}"
  }

  object UserLogEntry extends DefaultJsonProtocol {
    implicit val serdes =
      jsonFormat(
        UserLogEntry.apply,
        elasticSearchConfig.logSchema.message,
        elasticSearchConfig.logSchema.stream,
        elasticSearchConfig.logSchema.time)
  }

  implicit val actorSystem = system

  private val esClient = new ElasticSearchRestClient(
    elasticSearchConfig.protocol,
    elasticSearchConfig.host,
    elasticSearchConfig.port,
    httpFlow)

  private def transcribeLogs(queryResult: EsSearchResult): ActivationLogs =
    ActivationLogs(queryResult.hits.hits.map(_.source.convertTo[UserLogEntry].toFormattedString))

  private def extractRequiredHeaders(headers: Seq[HttpHeader]) =
    headers.filter(h => elasticSearchConfig.requiredHeaders.contains(h.lowercaseName)).toList

  private def generatePayload(namespace: String, activationId: ActivationId) = {
    val logQuery =
      s"_type: ${elasticSearchConfig.logSchema.userLogs} AND ${elasticSearchConfig.logSchema.tenantId}: ${namespace} AND ${elasticSearchConfig.logSchema.activationId}: ${activationId}"
    val queryString = EsQueryString(logQuery)
    val queryOrder = EsQueryOrder(elasticSearchConfig.logSchema.time, EsOrderAsc)

    EsQuery(queryString, Some(queryOrder))
  }

  private def generatePath(user: Identity) = elasticSearchConfig.path.format(user.namespace.uuid.asString)

  override def fetchLogs(namespace: String,
                         activationId: ActivationId,
                         start: Option[Instant],
                         end: Option[Instant],
                         activationLogs: Option[ActivationLogs],
                         context: UserContext): Future[ActivationLogs] = {
    val headers = extractRequiredHeaders(context.request.headers)

    // Return logs from ElasticSearch, or return logs from activation if required headers are not present
    if (headers.length == elasticSearchConfig.requiredHeaders.length) {
      esClient
        .search[EsSearchResult](generatePath(context.user), generatePayload(namespace, activationId), headers)
        .flatMap {
          case Right(queryResult) =>
            Future.successful(transcribeLogs(queryResult))
          case Left(code) =>
            Future.failed(new RuntimeException(s"Status code '$code' was returned from log store"))
        }
    } else {
      activationLogs match {
        case Some(logs) => Future.successful(logs)
        case None =>
          Future.failed(new RuntimeException(s"Activation logs not available for activation ${activationId}"))
      }
    }
  }
}

object ElasticSearchLogStoreProvider extends LogStoreProvider {
  override def instance(actorSystem: ActorSystem): LogStore = new ElasticSearchLogStore(actorSystem)
}
