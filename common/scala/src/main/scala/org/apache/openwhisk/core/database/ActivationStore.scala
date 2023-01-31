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

package org.apache.openwhisk.core.database

import java.time.Instant
import akka.actor.ActorSystem
import akka.http.scaladsl.model.HttpRequest
import spray.json.JsObject
import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.spi.Spi
import pureconfig.loadConfigOrThrow

import scala.concurrent.Future

case class UserContext(user: Identity, request: HttpRequest = HttpRequest())

trait ActivationStore {
  val logging: Logging

  /* DEPRECATED: disableStoreResult config is now deprecated replaced with blocking activation store level (storeBlockingResultLevel) */
  protected val disableStoreResultConfig = loadConfigOrThrow[Boolean](ConfigKeys.disableStoreResult)
  protected val storeBlockingResultLevelConfig = {
    try {
      ActivationStoreLevel.valueOf(loadConfigOrThrow[String](ConfigKeys.storeBlockingResultLevel))
    } catch {
      case _: Exception =>
        val disableStoreResultConfig = loadConfigOrThrow[Boolean](ConfigKeys.disableStoreResult)
        logging.warn(
          this,
          s"The config ${ConfigKeys.disableStoreResult} being used is deprecated. Please use the replacement config ${ConfigKeys.storeBlockingResultLevel}")
        if (disableStoreResultConfig) ActivationStoreLevel.STORE_FAILURES else ActivationStoreLevel.STORE_ALWAYS
    }
  }
  protected val storeNonBlockingResultLevelConfig =
    ActivationStoreLevel.valueOf(loadConfigOrThrow[String](ConfigKeys.storeNonBlockingResultLevel))
  protected val unstoredLogsEnabledConfig = loadConfigOrThrow[Boolean](ConfigKeys.unstoredLogsEnabled)

  /**
   * Checks if an activation should be stored in database and stores it.
   *
   * @param activation activation to store
   * @param isBlockingActivation is activation blocking
   * @param blockingStoreLevel do not store activation if successful and blocking
   * @param nonBlockingStoreLevel do not store activation if successful and non-blocking
   * @param context user and request context
   * @param transid transaction ID for request
   * @param notifier cache change notifier
   * @return Future containing DocInfo related to stored activation
   */
  def storeAfterCheck(activation: WhiskActivation,
                      isBlockingActivation: Boolean,
                      blockingStoreLevel: Option[ActivationStoreLevel.Value],
                      nonBlockingStoreLevel: Option[ActivationStoreLevel.Value],
                      context: UserContext)(implicit transid: TransactionId,
                                            notifier: Option[CacheChangeNotification],
                                            logging: Logging): Future[DocInfo] = {
    if (context.user.limits.storeActivations.getOrElse(true) &&
        shouldStoreActivation(
          activation.response,
          isBlockingActivation,
          transid.meta.extraLogging,
          blockingStoreLevel.getOrElse(storeBlockingResultLevelConfig),
          nonBlockingStoreLevel.getOrElse(storeNonBlockingResultLevelConfig))) {

      store(activation, context)
    } else {
      if (unstoredLogsEnabledConfig) {
        logging.info(
          this,
          s"Explicitly NOT storing activation ${activation.activationId.asString} for action ${activation.name} from namespace ${activation.namespace.asString} with response_size=${activation.response.size
            .getOrElse("0")}B")
      }
      Future.successful(DocInfo(activation.docid))
    }
  }

  /**
   * Stores an activation in the database.
   *
   * @param activation activation to store
   * @param context user and request context
   * @param transid transaction ID for request
   * @param notifier cache change notifier
   * @return Future containing DocInfo related to stored activation
   */
  def store(activation: WhiskActivation, context: UserContext)(
    implicit transid: TransactionId,
    notifier: Option[CacheChangeNotification]): Future[DocInfo]

  /**
   * Retrieves an activation corresponding to the specified activation ID.
   *
   * @param activationId ID of activation to retrieve
   * @param context user and request context
   * @param transid transaction ID for request
   * @return Future containing the retrieved WhiskActivation
   */
  def get(activationId: ActivationId, context: UserContext)(implicit transid: TransactionId): Future[WhiskActivation]

  /**
   * Deletes an activation corresponding to the provided activation ID.
   *
   * @param activationId ID of activation to delete
   * @param context user and request context
   * @param transid transaction ID for the request
   * @param notifier cache change notifier
   * @return Future containing a Boolean value indication whether the activation was deleted
   */
  def delete(activationId: ActivationId, context: UserContext)(
    implicit transid: TransactionId,
    notifier: Option[CacheChangeNotification]): Future[Boolean]

  /**
   * Counts the number of activations in a namespace.
   *
   * @param namespace namespace to query
   * @param name entity name to query
   * @param skip number of activations to skip
   * @param since timestamp to retrieve activations after
   * @param upto timestamp to retrieve activations before
   * @param context user and request context
   * @param transid transaction ID for request
   * @return Future containing number of activations returned from query in JSON format
   */
  def countActivationsInNamespace(namespace: EntityPath,
                                  name: Option[EntityPath] = None,
                                  skip: Int,
                                  since: Option[Instant] = None,
                                  upto: Option[Instant] = None,
                                  context: UserContext)(implicit transid: TransactionId): Future[JsObject]

  /**
   * Returns activations corresponding to provided entity name.
   *
   * @param namespace namespace to query
   * @param name entity name to query
   * @param skip number of activations to skip
   * @param limit maximum number of activations to list
   * @param includeDocs return document with each activation
   * @param since timestamp to retrieve activations after
   * @param upto timestamp to retrieve activations before
   * @param context user and request context
   * @param transid transaction ID for request
   * @return When docs are not included, a Future containing a List of activations in JSON format is returned. When docs
   *         are included, a List of WhiskActivation is returned.
   */
  def listActivationsMatchingName(
    namespace: EntityPath,
    name: EntityPath,
    skip: Int,
    limit: Int,
    includeDocs: Boolean = false,
    since: Option[Instant] = None,
    upto: Option[Instant] = None,
    context: UserContext)(implicit transid: TransactionId): Future[Either[List[JsObject], List[WhiskActivation]]]

  /**
   * List all activations in a specified namespace.
   *
   * @param namespace namespace to query
   * @param skip number of activations to skip
   * @param limit maximum number of activations to list
   * @param includeDocs return document with each activation
   * @param since timestamp to retrieve activations after
   * @param upto timestamp to retrieve activations before
   * @param context user and request context
   * @param transid transaction ID for request
   * @return When docs are not included, a Future containing a List of activations in JSON format is returned. When docs
   *         are included, a List of WhiskActivation is returned.
   */
  def listActivationsInNamespace(
    namespace: EntityPath,
    skip: Int,
    limit: Int,
    includeDocs: Boolean = false,
    since: Option[Instant] = None,
    upto: Option[Instant] = None,
    context: UserContext)(implicit transid: TransactionId): Future[Either[List[JsObject], List[WhiskActivation]]]

  /**
   * Checks if the system is configured to not store the activation in the database.
   * Only stores activations if one of these is true:
   * - result is an error,
   * - a non-blocking activation
   * - an activation in debug mode
   * - activation stores is not disabled via a configuration parameter
   *
   * @param activationResponse to check
   * @param isBlocking is blocking activation
   * @param debugMode is logging header set to "on" for the invocation
   * @param blockingStoreLevel level of activation status to store for blocking invocations
   * @param nonBlockingStoreLevel level of activation status to store for blocking invocations
   * @return Should the activation be stored to the database
   */
  private def shouldStoreActivation(activationResponse: ActivationResponse,
                                    isBlocking: Boolean,
                                    debugMode: Boolean,
                                    blockingStoreLevel: ActivationStoreLevel.Value,
                                    nonBlockingStoreLevel: ActivationStoreLevel.Value): Boolean = {
    def shouldStoreOnLevel(storageLevel: ActivationStoreLevel.Value): Boolean = {
      storageLevel match {
        case ActivationStoreLevel.STORE_ALWAYS   => true
        case ActivationStoreLevel.STORE_FAILURES => !activationResponse.isSuccess
        case ActivationStoreLevel.STORE_FAILURES_NOT_APPLICATION_ERRORS =>
          activationResponse.isContainerError || activationResponse.isWhiskError
      }
    }

    debugMode || (isBlocking && shouldStoreOnLevel(blockingStoreLevel)) || (!isBlocking && shouldStoreOnLevel(
      nonBlockingStoreLevel))
  }
}

trait ActivationStoreProvider extends Spi {
  def instance(actorSystem: ActorSystem, logging: Logging): ActivationStore
}
