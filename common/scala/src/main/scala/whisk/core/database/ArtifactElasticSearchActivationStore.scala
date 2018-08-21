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
import java.nio.file.{Files, Path, Paths}
import java.nio.file.attribute.PosixFilePermission.{
  GROUP_READ,
  GROUP_WRITE,
  OTHERS_READ,
  OTHERS_WRITE,
  OWNER_READ,
  OWNER_WRITE
}
import java.util.EnumSet
import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import akka.stream.alpakka.file.scaladsl.LogRotatorSink
import akka.stream.scaladsl.{Flow, MergeHub, RestartSink, Sink, Source}
import akka.stream._
import akka.util.ByteString
import pureconfig.loadConfigOrThrow
import spray.json._
import whisk.common.{Logging, TransactionId}
import whisk.core.ConfigKeys
import whisk.core.containerpool.logging.ElasticSearchJsonProtocol._
import whisk.core.entity._
import whisk.core.entity.size._

import scala.concurrent.{Future, Promise}
import scala.concurrent.duration._
import scala.util.Try

class ArtifactElasticSearchActivationStore(
  override val system: ActorSystem,
  actorMaterializer: ActorMaterializer,
  logging: Logging,
  override val httpFlow: Option[
    Flow[(HttpRequest, Promise[HttpResponse]), (Try[HttpResponse], Promise[HttpResponse]), Any]] = None,
  override val elasticSearchConfig: ElasticSearchActivationStoreConfig =
    loadConfigOrThrow[ElasticSearchActivationStoreConfig](ConfigKeys.elasticSearchActivationStore))
    extends ArtifactActivationStore(system, actorMaterializer, logging)
    with ElasticSearchActivationRestClient {

  implicit val m = actorMaterializer

  val destinationDirectory: Path = Paths.get("logs")
  val bufferSize = 100.MB
  val perms = EnumSet.of(OWNER_READ, OWNER_WRITE, GROUP_READ, GROUP_WRITE, OTHERS_READ, OTHERS_WRITE)
  protected val writeToFile: Sink[ByteString, _] = MergeHub
    .source[ByteString]
    .batchWeighted(bufferSize.toBytes, _.length, identity)(_ ++ _)
    .to(RestartSink.withBackoff(minBackoff = 1.seconds, maxBackoff = 60.seconds, randomFactor = 0.2) { () =>
      LogRotatorSink(() => {
        val maxSize = bufferSize.toBytes
        var bytesRead = maxSize
        element =>
          {
            val size = element.size
            if (bytesRead + size > maxSize) {
              bytesRead = size
              val logFilePath = destinationDirectory.resolve(s"userlogs-${Instant.now.toEpochMilli}.log")
              logging.info(this, s"Rotating log file to '$logFilePath'")
              try {
                Files.createFile(logFilePath)
                Files.setPosixFilePermissions(logFilePath, perms)
              } catch {
                case t: Throwable =>
                  logging.error(this, s"Couldn't create activation record file '$t'")
                  throw t
              }
              Some(logFilePath)
            } else {
              bytesRead += size
              None
            }
          }
      })
    })
    .run()

  protected def extractRequiredHeaders(headers: Seq[HttpHeader]) =
    headers.filter(h => elasticSearchConfig.requiredHeaders.contains(h.lowercaseName)).toList

  protected def writeActivation(activation: WhiskActivation, context: UserContext) = {
    val userIdField = Map("namespaceId" -> context.user.namespace.uuid.toJson)
    val namespace = Map("namespace" -> activation.namespace.namespace.toJson)
    val name = Map("name" -> activation.name.toJson)
    val subject = Map("subject" -> activation.subject.toJson)
    val activationId = Map("activationId" -> activation.activationId.toJson)
    val start = Map("start" -> activation.start.toEpochMilli.toJson)
    val end = Map("end" -> activation.end.toEpochMilli.toJson)
    val cause = Map("cause" -> activation.cause.toJson)
    val result = Map("result" -> activation.response.result.get.compactPrint.toJson)
    val statusCode = Map("statusCode" -> activation.response.statusCode.toJson)
    val logs = Map("logs" -> activation.logs.toJson)
    val version = Map("version" -> activation.version.toJson)
    val annotations = activation.annotations.toJsObject.fields
    val duration = activation.duration.map(d => Map("duration" -> d.toJson)) getOrElse Map.empty
    val augmentedActivation = JsObject(
      userIdField ++ namespace ++ name ++ subject ++ activationId ++ start ++ end ++ cause ++ result ++ statusCode ++ logs ++ version ++ annotations ++ duration)
    val line = ByteString(augmentedActivation.compactPrint + "\n")

    Source.single(line).runWith(Flow[ByteString].to(writeToFile))
  }

  override def store(activation: WhiskActivation, context: UserContext)(
    implicit transid: TransactionId,
    notifier: Option[CacheChangeNotification]): Future[DocInfo] = {
    writeActivation(activation, context)
    super.store(activation, context)
  }

  override def get(activationId: ActivationId, context: UserContext)(
    implicit transid: TransactionId): Future[WhiskActivation] = {
    val headers = extractRequiredHeaders(context.request.headers)

    // Return result from ElasticSearch or from artifact store if required headers are not present
    if (headers.length == elasticSearchConfig.requiredHeaders.length) {
      val uuid = elasticSearchConfig.path.format(context.user.namespace.uuid.asString)
      val id = activationId.asString.substring(activationId.asString.indexOf("/") + 1)

      getActivation(id, uuid, headers).map(_.toActivation())
    } else {
      super.get(activationId, context)
    }
  }

  override def countActivationsInNamespace(namespace: EntityPath,
                                           name: Option[EntityPath] = None,
                                           skip: Int,
                                           since: Option[Instant] = None,
                                           upto: Option[Instant] = None,
                                           context: UserContext)(implicit transid: TransactionId): Future[JsObject] = {
    val uuid = elasticSearchConfig.path.format(context.user.namespace.uuid.asString)
    val headers = extractRequiredHeaders(context.request.headers)

    // Return result from ElasticSearch or from artifact store if required headers are not present
    if (headers.length == elasticSearchConfig.requiredHeaders.length) {
      count(uuid, name, namespace.asString, skip, since, upto, headers)
    } else {
      super.countActivationsInNamespace(namespace, name, skip, since, upto, context)
    }
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
    val uuid = elasticSearchConfig.path.format(context.user.namespace.uuid.asString)
    val headers = extractRequiredHeaders(context.request.headers)

    // Return result from ElasticSearch or from artifact store if required headers are not present
    if (headers.length == elasticSearchConfig.requiredHeaders.length) {
      listActivationMatching(uuid, name.toString, skip, limit, since, upto, headers).map { activationList =>
        Right(activationList.map(activation => activation.toActivation()))
      }
    } else {
      super.listActivationsMatchingName(namespace, name, skip, limit, includeDocs, since, upto, context)
    }
  }

  override def listActivationsInNamespace(
    namespace: EntityPath,
    skip: Int,
    limit: Int,
    includeDocs: Boolean = false,
    since: Option[Instant] = None,
    upto: Option[Instant] = None,
    context: UserContext)(implicit transid: TransactionId): Future[Either[List[JsObject], List[WhiskActivation]]] = {
    val uuid = elasticSearchConfig.path.format(context.user.namespace.uuid.asString)
    val headers = extractRequiredHeaders(context.request.headers)

    // Return result from ElasticSearch or from artifact store if required headers are not present
    if (headers.length == elasticSearchConfig.requiredHeaders.length) {
      listActivationsNamespace(uuid, namespace.asString, skip, limit, since, upto, headers).map { activationList =>
        Right(activationList.map(activation => activation.toActivation()))
      }
    } else {
      super.listActivationsInNamespace(namespace, skip, limit, includeDocs, since, upto, context)
    }
  }

}

object ArtifactElasticSearchActivationStoreProvider extends ActivationStoreProvider {
  override def instance(actorSystem: ActorSystem, actorMaterializer: ActorMaterializer, logging: Logging) =
    new ArtifactElasticSearchActivationStore(actorSystem, actorMaterializer, logging)
}
