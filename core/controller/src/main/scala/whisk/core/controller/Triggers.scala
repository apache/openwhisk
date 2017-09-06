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

package whisk.core.controller

import java.time.Clock
import java.time.Instant

import scala.concurrent.Future
import scala.util.{Failure, Success}

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.http.scaladsl.model.headers.BasicHttpCredentials
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.server.RouteResult
import akka.http.scaladsl.model.HttpMethods.POST
import akka.http.scaladsl.model.headers.Authorization
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model.MediaTypes
import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.server.RequestContext
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.unmarshalling.Unmarshal

import spray.json._
import spray.json.DefaultJsonProtocol.RootJsObjectFormat

import whisk.common.TransactionId
import whisk.core.database.CacheChangeNotification
import whisk.core.entitlement.Collection
import whisk.core.entity.ActivationResponse
import whisk.core.entity.EntityPath
import whisk.core.entity.Parameters
import whisk.core.entity.SemVer
import whisk.core.entity.Status
import whisk.core.entity.TriggerLimits
import whisk.core.entity.WhiskActivation
import whisk.core.entity.WhiskTrigger
import whisk.core.entity.WhiskTriggerPut
import whisk.core.entity.types.ActivationStore
import whisk.core.entity.types.EntityStore
import whisk.core.entity.Identity
import whisk.core.entity.FullyQualifiedEntityName
import whisk.http.ErrorResponse.terminate

/** A trait implementing the triggers API. */
trait WhiskTriggersApi extends WhiskCollectionAPI {
  services: WhiskServices =>

  protected override val collection = Collection(Collection.TRIGGERS)

  /** An actor system for timed based futures. */
  protected implicit val actorSystem: ActorSystem

  /** Database service to CRUD triggers. */
  protected val entityStore: EntityStore

  /** Notification service for cache invalidation. */
  protected implicit val cacheChangeNotification: Some[CacheChangeNotification]

  /** Database service to get activations. */
  protected val activationStore: ActivationStore

  /** JSON response formatter. */
  import RestApiCommons.jsonDefaultResponsePrinter

  /** Path to Triggers REST API. */
  protected val triggersPath = "triggers"

  protected implicit val materializer: ActorMaterializer

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
    entity(as[Option[JsObject]]) { payload =>
      getEntity(WhiskTrigger, entityStore, entityName.toDocId, Some {
        trigger: WhiskTrigger =>
          val args = trigger.parameters.merge(payload)
          val triggerActivationId = activationIdFactory.make()
          logging.info(this, s"[POST] trigger activation id: ${triggerActivationId}")

          val triggerActivation = WhiskActivation(
            namespace = user.namespace.toPath, // all activations should end up in the one space regardless trigger.namespace,
            entityName.name,
            user.subject,
            triggerActivationId,
            Instant.now(Clock.systemUTC()),
            Instant.EPOCH,
            response = ActivationResponse.success(payload orElse Some(JsObject())),
            version = trigger.version,
            duration = None)
          logging.info(this, s"[POST] trigger activated, writing activation record to datastore: $triggerActivationId")
          val saveTriggerActivation = WhiskActivation.put(activationStore, triggerActivation) map { _ =>
            triggerActivationId
          }

          val url = Uri(s"http://localhost:${whiskConfig.servicePort}")

          trigger.rules.map {
            _.filter {
              case (ruleName, rule) => rule.status == Status.ACTIVE
            } foreach {
              case (ruleName, rule) =>
                val ruleActivation = WhiskActivation(
                  namespace = user.namespace.toPath, // all activations should end up in the one space regardless trigger.namespace,
                  ruleName.name,
                  user.subject,
                  activationIdFactory.make(),
                  Instant.now(Clock.systemUTC()),
                  Instant.EPOCH,
                  cause = Some(triggerActivationId),
                  response = ActivationResponse.success(),
                  version = trigger.version,
                  duration = None)
                logging.info(this, s"[POST] rule ${ruleName} activated, writing activation record to datastore")
                WhiskActivation.put(activationStore, ruleActivation)

                val actionNamespace = rule.action.path.root.asString
                val actionPath = {
                  rule.action.path.relativePath.map { pkg =>
                    (Path.SingleSlash + pkg.namespace) / rule.action.name.asString
                  } getOrElse {
                    Path.SingleSlash + rule.action.name.asString
                  }
                }.toString

                val actionUrl = Path("/api/v1") / "namespaces" / actionNamespace / "actions"
                val request = HttpRequest(
                  method = POST,
                  uri = url.withPath(actionUrl + actionPath),
                  headers =
                    List(Authorization(BasicHttpCredentials(user.authkey.uuid.asString, user.authkey.key.asString))),
                  entity = HttpEntity(MediaTypes.`application/json`, args.getOrElse(JsObject()).compactPrint))

                Http().singleRequest(request).map {
                  response =>
                    response.status match {
                      case OK | Accepted =>
                        Unmarshal(response.entity).to[JsObject].map { a =>
                          logging.info(this, s"${rule.action} activated ${a.fields("activationId")}")
                        }
                      case NotFound =>
                        response.discardEntityBytes()
                        logging.info(this, s"${rule.action} failed, action not found")
                      case _ =>
                        Unmarshal(response.entity).to[String].map { error =>
                          logging.warn(this, s"${rule.action} failed due to $error")
                        }
                    }
                }
            }
          }

          onComplete(saveTriggerActivation) {
            case Success(activationId) =>
              complete(OK, activationId.toJsObject)
            case Failure(t: Throwable) =>
              logging.error(this, s"[POST] storing trigger activation failed: ${t.getMessage}")
              terminate(InternalServerError)
          }
      })
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
    getEntity(WhiskTrigger, entityStore, entityName.toDocId, Some { trigger =>
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
  override def list(user: Identity, namespace: EntityPath, excludePrivate: Boolean)(implicit transid: TransactionId) = {
    // for consistency, all the collections should support the same list API
    // but because supporting docs on actions is difficult, the API does not
    // offer an option to fetch entities with full docs yet; see comment in
    // Actions API for more.
    val docs = false
    parameter('skip ? 0, 'limit ? collection.listLimit, 'count ? false) { (skip, limit, count) =>
      listEntities {
        WhiskTrigger.listCollectionInNamespace(entityStore, namespace, skip, limit, docs) map { list =>
          val triggers = if (docs) {
            list.right.get map { WhiskTrigger.serdes.write(_) }
          } else list.left.get
          FilterEntityList.filter(triggers, excludePrivate)
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

    // feed must be specified in create, and cannot be added as a trigger update
    content.annotations flatMap { _.get(Parameters.Feed) } map { _ =>
      Future failed {
        RejectRequest(BadRequest, "A trigger feed is only permitted when the trigger is created")
      }
    } getOrElse {
      Future successful newTrigger
    }
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
    complete(OK, trigger.withoutRules)
  }
}
