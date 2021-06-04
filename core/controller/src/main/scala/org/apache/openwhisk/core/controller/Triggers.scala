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

package org.apache.openwhisk.core.controller

import java.time.{Clock, Instant}

import scala.collection.immutable.Map
import scala.concurrent.Future
import scala.util.Try
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.HttpMethods.POST
import akka.http.scaladsl.model.StatusCodes.{Accepted, BadRequest, InternalServerError, NoContent, OK, ServerError}
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model.headers.Authorization
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.{RequestContext, RouteResult}
import akka.http.scaladsl.unmarshalling.{Unmarshal, Unmarshaller}
import spray.json.DefaultJsonProtocol._
import pureconfig._
import pureconfig.generic.auto._
import spray.json._
import org.apache.openwhisk.common.Https.HttpsConfig
import org.apache.openwhisk.common.{Https, TransactionId}
import org.apache.openwhisk.core.controller.RestApiCommons.{ListLimit, ListSkip}
import org.apache.openwhisk.core.database.{ActivationStore, CacheChangeNotification}
import org.apache.openwhisk.core.entitlement.Collection
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.entity.types.EntityStore
import org.apache.openwhisk.http.ErrorResponse
import org.apache.openwhisk.http.Messages
import org.apache.openwhisk.core.database.UserContext

/** A trait implementing the triggers API. */
trait WhiskTriggersApi extends WhiskCollectionAPI {
  services: WhiskServices =>

  protected override val collection = Collection(Collection.TRIGGERS)

  /** An actor system for timed based futures. */
  protected implicit val actorSystem: ActorSystem

  /** Database service to CRUD triggers. */
  protected val entityStore: EntityStore

  /** Connection context for HTTPS */
  protected lazy val httpsConnectionContext = {
    val httpsConfig = loadConfigOrThrow[HttpsConfig]("whisk.controller.https")
    Https.connectionContextClient(httpsConfig, true)
  }

  protected val controllerProtocol = loadConfigOrThrow[String]("whisk.controller.protocol")

  /**
   * Sends a request either over http or https depending on the configuration
   * @param request http request to send
   * @return http response packed in a future
   */
  private def singleRequest(request: HttpRequest): Future[HttpResponse] = {
    if (controllerProtocol == "https")
      Http().singleRequest(request, connectionContext = httpsConnectionContext)
    else
      Http().singleRequest(request)
  }

  /** Notification service for cache invalidation. */
  protected implicit val cacheChangeNotification: Some[CacheChangeNotification]

  /** Database service to get activations. */
  protected val activationStore: ActivationStore

  /** JSON response formatter. */
  /** Path to Triggers REST API. */
  protected val triggersPath = "triggers"
  protected val url = Uri(s"${controllerProtocol}://localhost:${whiskConfig.servicePort}")

  import RestApiCommons.emptyEntityToJsObject

  /**
   * Creates or updates trigger if it already exists. The PUT content is deserialized into a WhiskTriggerPut
   * which is a subset of WhiskTrigger (it eschews the namespace and entity name since the former is derived
   * from the authenticated user and the latter is derived from the URI). The WhiskTriggerPut is merged with
   * the existing WhiskTrigger in the datastore, overriding old values with new values that are defined.
   * Any values not defined in the PUT content are replaced with old values.
   *
   * Responses are one of (Code, Message)
   * - 200 WhiskAction as JSON
   * - 400 Bad Request
   * - 409 Conflict
   * - 500 Internal Server Error
   */
  override def create(user: Identity, entityName: FullyQualifiedEntityName)(implicit transid: TransactionId) = {
    parameter('overwrite ? false) { overwrite =>
      entity(as[WhiskTriggerPut]) { content =>
        putEntity(WhiskTrigger, entityStore, entityName.toDocId, overwrite, update(content) _, () => {
          create(content, entityName)
        }, postProcess = Some { trigger =>
          completeAsTriggerResponse(trigger)
        })
      }
    }
  }

  /**
   * Fires trigger if it exists. The POST content is deserialized into a Payload and posted
   * to the loadbalancer.
   *
   * Responses are one of (Code, Message)
   * - 200 ActivationId as JSON
   * - 404 Not Found
   * - 500 Internal Server Error
   */
  override def activate(user: Identity, entityName: FullyQualifiedEntityName, env: Option[Parameters])(
    implicit transid: TransactionId) = {
    extractRequest { request =>
      val context = UserContext(user, request)

      entity(as[Option[JsObject]]) { payload =>
        getEntity(WhiskTrigger.get(entityStore, entityName.toDocId), Some {
          trigger: WhiskTrigger =>
            // List of active rules associated with the trigger
            val activeRules: Map[FullyQualifiedEntityName, ReducedRule] =
              trigger.rules.map(_.filter(_._2.status == Status.ACTIVE)).getOrElse(Map.empty)

            if (activeRules.nonEmpty) {
              val triggerActivationId = activationIdFactory.make()
              logging.info(this, s"[POST] trigger activation id: ${triggerActivationId}")
              val triggerActivation = WhiskActivation(
                namespace = user.namespace.name.toPath, // all activations should end up in the one space regardless trigger.namespace
                entityName.name,
                user.subject,
                triggerActivationId,
                Instant.now(Clock.systemUTC()),
                Instant.EPOCH,
                response = ActivationResponse.success(payload orElse Some(JsObject.empty)),
                version = trigger.version,
                duration = None)
              val args: JsObject = trigger.parameters.merge(payload).getOrElse(JsObject.empty)

              activateRules(user, args, trigger.rules.getOrElse(Map.empty))
                .map(results => triggerActivation.withLogs(ActivationLogs(results.map(_.toJson.compactPrint).toVector)))
                .recover {
                  case e =>
                    logging.error(this, s"Failed to write action activation results to trigger activation: $e")
                    triggerActivation
                }
                .map { activation =>
                  activationStore.storeAfterCheck(activation, false, None, context)
                }

              respondWithActivationIdHeader(triggerActivationId) {
                complete(Accepted, triggerActivationId.toJsObject)
              }
            } else {
              logging
                .debug(
                  this,
                  s"[POST] trigger without an active rule was activated; no trigger activation record created for $entityName")
              complete(NoContent)
            }
        })
      }
    }
  }

  /**
   * Deletes trigger.
   *
   * Responses are one of (Code, Message)
   * - 200 WhiskTrigger as JSON
   * - 404 Not Found
   * - 409 Conflict
   * - 500 Internal Server Error
   */
  override def remove(user: Identity, entityName: FullyQualifiedEntityName)(implicit transid: TransactionId) = {
    deleteEntity(
      WhiskTrigger,
      entityStore,
      entityName.toDocId,
      (t: WhiskTrigger) => Future.successful({}),
      postProcess = Some { trigger =>
        completeAsTriggerResponse(trigger)
      })
  }

  /**
   * Gets trigger. The trigger name is prefixed with the namespace to create the primary index key.
   *
   * Responses are one of (Code, Message)
   * - 200 WhiskTrigger has JSON
   * - 404 Not Found
   * - 500 Internal Server Error
   */
  override def fetch(user: Identity, entityName: FullyQualifiedEntityName, env: Option[Parameters])(
    implicit transid: TransactionId) = {
    getEntity(WhiskTrigger.get(entityStore, entityName.toDocId), Some { trigger =>
      completeAsTriggerResponse(trigger)
    })
  }

  /**
   * Gets all triggers in namespace.
   *
   * Responses are one of (Code, Message)
   * - 200 [] or [WhiskTrigger as JSON]
   * - 500 Internal Server Error
   */
  override def list(user: Identity, namespace: EntityPath)(implicit transid: TransactionId) = {
    parameter(
      'skip.as[ListSkip] ? ListSkip(collection.defaultListSkip),
      'limit.as[ListLimit] ? ListLimit(collection.defaultListLimit),
      'count ? false) { (skip, limit, count) =>
      if (!count) {
        listEntities {
          WhiskTrigger.listCollectionInNamespace(entityStore, namespace, skip.n, limit.n, includeDocs = false) map {
            list =>
              list.fold((js) => js, (ts) => ts.map(WhiskTrigger.serdes.write(_)))
          }
        }
      } else {
        countEntities {
          WhiskTrigger.countCollectionInNamespace(entityStore, namespace, skip.n)
        }
      }
    }
  }

  /** Creates a WhiskTrigger from PUT content, generating default values where necessary. */
  private def create(content: WhiskTriggerPut, triggerName: FullyQualifiedEntityName)(
    implicit transid: TransactionId): Future[WhiskTrigger] = {
    val newTrigger = WhiskTrigger(
      triggerName.path,
      triggerName.name,
      content.parameters getOrElse Parameters(),
      content.limits getOrElse TriggerLimits(),
      content.version getOrElse SemVer(),
      content.publish getOrElse false,
      content.annotations getOrElse Parameters())
    validateTriggerFeed(newTrigger)
  }

  /** Updates a WhiskTrigger from PUT content, merging old trigger where necessary. */
  private def update(content: WhiskTriggerPut)(trigger: WhiskTrigger)(
    implicit transid: TransactionId): Future[WhiskTrigger] = {
    val newTrigger = WhiskTrigger(
      trigger.namespace,
      trigger.name,
      content.parameters getOrElse trigger.parameters,
      content.limits getOrElse trigger.limits,
      content.version getOrElse trigger.version.upPatch,
      content.publish getOrElse trigger.publish,
      content.annotations getOrElse trigger.annotations,
      trigger.rules).revision[WhiskTrigger](trigger.docinfo.rev)
    Future.successful(newTrigger)
  }

  /**
   * Validates a trigger feed annotation.
   * A trigger feed must be a valid entity name, e.g., one of 'namespace/package/name'
   * or 'namespace/name', or just 'name'.
   *
   * TODO: check if the feed actually exists. This is deferred because the macro
   * operation of creating a trigger and initializing the feed is handled as one
   * atomic operation in the CLI and the UI. At some point these may be promoted
   * to a single atomic operation in the controller; at which point, validating
   * the trigger feed should execute the action (verifies it is a valid name that
   * the subject is entitled to) and iff that succeeds will the trigger be created
   * or updated.
   */
  private def validateTriggerFeed(trigger: WhiskTrigger)(implicit transid: TransactionId) = {
    trigger.annotations.get(Parameters.Feed) map {
      case JsString(f) if (EntityPath.validate(f)) =>
        Future successful trigger
      case _ =>
        Future failed {
          RejectRequest(BadRequest, "Feed name is not valid")
        }
    } getOrElse {
      Future successful trigger
    }
  }

  /**
   * Completes an HTTP request with a WhiskRule including the computed Status
   *
   * @param rule the rule to send
   * @param status the status to include in the response
   */
  private def completeAsTriggerResponse(trigger: WhiskTrigger): RequestContext => Future[RouteResult] = {
    complete(OK, trigger)
  }

  /**
   * Iterates through each rule and invoking each active rule's mapped action.
   */
  private def activateRules(user: Identity,
                            args: JsObject,
                            rulesToActivate: Map[FullyQualifiedEntityName, ReducedRule])(
    implicit transid: TransactionId): Future[Iterable[RuleActivationResult]] = {
    val ruleResults = rulesToActivate.map {
      case (ruleName, rule) if (rule.status != Status.ACTIVE) =>
        Future.successful {
          RuleActivationResult(
            ActivationResponse.ApplicationError,
            ruleName,
            rule.action,
            Left(Messages.triggerWithInactiveRule(ruleName.asString, rule.action.asString)))
        }
      case (ruleName, rule) =>
        // Invoke the action. Retain action results for inclusion in the trigger activation record
        postActivation(user, rule, args)
          .flatMap { response =>
            response.status match {
              case OK | Accepted =>
                Unmarshal(response.entity).to[JsObject].map { activationResponse =>
                  val activationId = activationResponse.fields("activationId").convertTo[ActivationId]
                  logging.debug(this, s"trigger-fired action '${rule.action}' invoked with activation $activationId")
                  RuleActivationResult(ActivationResponse.Success, ruleName, rule.action, Right(activationId))
                }

              case code =>
                Unmarshal(response.entity).to[String].map { error =>
                  val failureType = code match {
                    case _: ServerError => ActivationResponse.WhiskError // all 500s are to be considered whisk errors
                    case _              => ActivationResponse.ApplicationError
                  }
                  val errorMessage: String = Try(error.parseJson.convertTo[ErrorResponse])
                    .map { e =>
                      def logMsg = s"trigger-fired action '${rule.action}' failed to invoke with ${e.error}, ${e.code}"
                      if (failureType == ActivationResponse.ApplicationError) logging.debug(this, logMsg)
                      else logging.error(this, logMsg)

                      e.error
                    }
                    .getOrElse {
                      logging
                        .error(this, s"trigger-fired action '${rule.action}' failed to invoke with status code $code")
                      InternalServerError.defaultMessage
                    }

                  RuleActivationResult(failureType, ruleName, rule.action, Left(errorMessage))
                }
            }
          }
          .recover {
            case t =>
              logging.error(this, s"trigger-fired action '${rule.action}' failed to invoke with $t")
              RuleActivationResult(
                ActivationResponse.WhiskError,
                ruleName,
                rule.action,
                Left(InternalServerError.defaultMessage))
          }
    }

    Future.sequence(ruleResults)
  }

  /**
   * Posts an action activation. Currently done by posting internally to the controller.
   * TODO: use a proper path that does not route through HTTP.
   *
   * @param rule the name of the rule that is activated
   * @param args the arguments to post to the action
   * @return a future with the HTTP response from the action activation
   */
  private def postActivation(user: Identity, rule: ReducedRule, args: JsObject)(
    implicit transid: TransactionId): Future[HttpResponse] = {
    // Build the url to invoke an action mapped to the rule
    val actionUrl = baseControllerPath / rule.action.path.root.asString / "actions"

    val actionPath = rule.action.path.relativePath
      .map(pkg => Path / pkg.namespace / rule.action.name.asString)
      .getOrElse(Path / rule.action.name.asString)

    user.authkey.getCredentials
      .map { creds =>
        val request = HttpRequest(
          method = POST,
          uri = url.withPath(actionUrl ++ actionPath),
          headers = List(Authorization(creds), transid.toHeader),
          entity = HttpEntity(MediaTypes.`application/json`, args.compactPrint))

        singleRequest(request)
      }
      .getOrElse(Future.failed(new NoCredentialsAvailable()))
  }

  /** Contains the result of invoking a rule */
  case class RuleActivationResult(statusCode: Int,
                                  ruleName: FullyQualifiedEntityName,
                                  actionName: FullyQualifiedEntityName,
                                  response: Either[String, ActivationId]) {
    def toJson: JsObject =
      JsObject(
        Map(
          "rule" -> ruleName.asString.toJson,
          "action" -> actionName.asString.toJson,
          "statusCode" -> statusCode.toJson,
          "success" -> (statusCode == ActivationResponse.Success).toJson,
          response.fold("error" -> _.toJson, "activationId" -> _.toJson)))
  }

  /** Common base bath for the controller, used by internal action activation mechanism. */
  private val baseControllerPath = Path / "api" / "v1" / "namespaces"

  /** Custom unmarshaller for query parameters "limit" for "list" operations. */
  private implicit val stringToListLimit: Unmarshaller[String, ListLimit] = RestApiCommons.stringToListLimit(collection)

  /** Custom unmarshaller for query parameters "skip" for "list" operations. */
  private implicit val stringToListSkip: Unmarshaller[String, ListSkip] = RestApiCommons.stringToListSkip(collection)

  private case class NoCredentialsAvailable() extends IllegalArgumentException

}
