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

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}
import org.apache.kafka.common.errors.RecordTooLargeException
import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.RequestContext
import akka.http.scaladsl.server.RouteResult
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.unmarshalling._
import spray.json._
import spray.json.DefaultJsonProtocol._
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.{FeatureFlags, WhiskConfig}
import org.apache.openwhisk.core.controller.RestApiCommons.{ListLimit, ListSkip}
import org.apache.openwhisk.core.controller.actions.PostActionActivation
import org.apache.openwhisk.core.database.{ActivationStore, CacheChangeNotification, NoDocumentException}
import org.apache.openwhisk.core.entitlement._
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.entity.types.EntityStore
import org.apache.openwhisk.http.ErrorResponse.terminate
import org.apache.openwhisk.http.Messages
import org.apache.openwhisk.http.Messages._
import org.apache.openwhisk.core.entitlement.Resource
import org.apache.openwhisk.core.entitlement.Collection
import org.apache.openwhisk.core.loadBalancer.LoadBalancerException
import pureconfig._
import org.apache.openwhisk.core.ConfigKeys

/**
 * A singleton object which defines the properties that must be present in a configuration
 * in order to implement the actions API.
 */
object WhiskActionsApi {
  def requiredProperties = Map(WhiskConfig.actionSequenceMaxLimit -> null)

  /**
   * Amends annotations on an action create/update with system defined values.
   * This method currently adds the following annotations:
   * 1. [[Annotations.ProvideApiKeyAnnotationName]] with the value false iff the annotation is not already defined in the action annotations
   * 2. An [[execAnnotation]] consistent with the action kind; this annotation is always added and overrides a pre-existing value
   */
  protected[core] def amendAnnotations(annotations: Parameters, exec: Exec, create: Boolean = true): Parameters = {
    val newAnnotations = if (create && FeatureFlags.requireApiKeyAnnotation) {
      // these annotations are only added on newly created actions
      // since they can break existing actions created before the
      // annotation was created
      annotations
        .get(Annotations.ProvideApiKeyAnnotationName)
        .map(_ => annotations)
        .getOrElse {
          annotations ++ Parameters(Annotations.ProvideApiKeyAnnotationName, JsFalse)
        }
    } else annotations
    newAnnotations ++ execAnnotation(exec)
  }

  /**
   * Constructs an "exec" annotation. This is redundant with the exec kind
   * information available in WhiskAction but necessary for some clients which
   * fetch action lists but cannot determine action kinds without fetching them.
   * An alternative is to include the exec in the action list "view" but this
   * will require an API change. So using an annotation instead.
   */
  private def execAnnotation(exec: Exec): Parameters = {
    Parameters(WhiskAction.execFieldName, exec.kind)
  }
}

/** A trait implementing the actions API. */
trait WhiskActionsApi extends WhiskCollectionAPI with PostActionActivation with ReferencedEntities {
  services: WhiskServices =>

  protected override val collection = Collection(Collection.ACTIONS)

  /** An actor system for timed based futures. */
  protected implicit val actorSystem: ActorSystem

  /** Database service to CRUD actions. */
  protected val entityStore: EntityStore

  /** Notification service for cache invalidation. */
  protected implicit val cacheChangeNotification: Some[CacheChangeNotification]

  /** Database service to get activations. */
  protected val activationStore: ActivationStore

  /** Config flag for Execute Only for Actions in Shared Packages */
  protected def executeOnly =
    loadConfigOrThrow[Boolean](ConfigKeys.sharedPackageExecuteOnly)

  /** Entity normalizer to JSON object. */
  import RestApiCommons.emptyEntityToJsObject

  /** JSON response formatter. */
  import RestApiCommons.jsonDefaultResponsePrinter

  /**
   * Handles operations on action resources, which encompass these cases:
   *
   * 1. ns/foo     -> subject must be authorized for one of { action(ns, *), action(ns, foo) },
   *                  resource resolves to { action(ns, foo) }
   *
   * 2. ns/bar/foo -> where bar is a package
   *                  subject must be authorized for one of { package(ns, *), package(ns, bar), action(ns.bar, foo) }
   *                  resource resolves to { action(ns.bar, foo) }
   *
   * 3. ns/baz/foo -> where baz is a binding to ns'.bar
   *                  subject must be authorized for one of { package(ns, *), package(ns, baz) }
   *                  *and* one of { package(ns', *), package(ns', bar), action(ns'.bar, foo) }
   *                  resource resolves to { action(ns'.bar, foo) }
   *
   * Note that package(ns, xyz) == action(ns.xyz, *) and if subject has rights to package(ns, xyz)
   * then they also have rights to action(ns.xyz, *) since sharing is done at the package level and
   * is not more granular; hence a check on action(ns.xyz, abc) is eschewed.
   *
   * Only list is supported for these resources:
   *
   * 4. ns/bar/    -> where bar is a package
   *                  subject must be authorized for one of { package(ns, *), package(ns, bar) }
   *                  resource resolves to { action(ns.bar, *) }
   *
   * 5. ns/baz/    -> where baz is a binding to ns'.bar
   *                  subject must be authorized for one of { package(ns, *), package(ns, baz) }
   *                  *and* one of { package(ns', *), package(ns', bar) }
   *                  resource resolves to { action(ns.bar, *) }
   */
  protected override def innerRoutes(user: Identity, ns: EntityPath)(implicit transid: TransactionId) = {
    (entityPrefix & entityOps & requestMethod) { (segment, m) =>
      entityname(segment) { outername =>
        pathEnd {
          // matched /namespace/collection/name
          // this is an action in default package, authorize and dispatch
          authorizeAndDispatch(m, user, Resource(ns, collection, Some(outername)))
        } ~ (get & pathSingleSlash) {
          // matched GET /namespace/collection/package-name/
          // list all actions in package iff subject is entitled to READ package
          val resource = Resource(ns, Collection(Collection.PACKAGES), Some(outername))
          onComplete(entitlementProvider.check(user, Privilege.READ, resource)) {
            case Success(_) => listPackageActions(user, FullyQualifiedEntityName(ns, EntityName(outername)))
            case Failure(f) => super.handleEntitlementFailure(f)
          }
        } ~ (entityPrefix & pathEnd) { segment =>
          entityname(segment) { innername =>
            // matched /namespace/collection/package-name/action-name
            // this is an action in a named package
            val packageDocId = FullyQualifiedEntityName(ns, EntityName(outername)).toDocId
            val packageResource = Resource(ns.addPath(EntityName(outername)), collection, Some(innername))

            val right = collection.determineRight(m, Some(innername))
            onComplete(entitlementProvider.check(user, right, packageResource)) {
              case Success(_) =>
                getEntity(WhiskPackage.get(entityStore, packageDocId), Some {
                  if (right == Privilege.READ || right == Privilege.ACTIVATE) { wp: WhiskPackage =>
                    val actionResource = Resource(wp.fullPath, collection, Some(innername))
                    dispatchOp(user, right, actionResource)

                  } else {
                    // these packaged action operations do not need merging with the package,
                    // but may not be permitted if this is a binding, or if the subject does
                    // not have PUT and DELETE rights to the package itself
                    (wp: WhiskPackage) =>
                      wp.binding map { _ =>
                        terminate(BadRequest, Messages.notAllowedOnBinding)
                      } getOrElse {
                        val actionResource = Resource(wp.fullPath, collection, Some(innername))
                        dispatchOp(user, right, actionResource)
                      }
                  }
                })
              case Failure(f) => super.handleEntitlementFailure(f)
            }
          }
        }
      }
    }
  }

  /**
   * Creates or updates action if it already exists. The PUT content is deserialized into a WhiskActionPut
   * which is a subset of WhiskAction (it eschews the namespace and entity name since the former is derived
   * from the authenticated user and the latter is derived from the URI). The WhiskActionPut is merged with
   * the existing WhiskAction in the datastore, overriding old values with new values that are defined.
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
      entity(as[WhiskActionPut]) { content =>
        val request = content.resolve(user.namespace)
        val checkAdditionalPrivileges = entitleReferencedEntities(user, Privilege.READ, request.exec).flatMap {
          case _ => entitlementProvider.check(user, content.exec)
        }

        onComplete(checkAdditionalPrivileges) {
          case Success(_) =>
            putEntity(WhiskAction, entityStore, entityName.toDocId, overwrite, update(user, request) _, () => {
              make(user, entityName, request)
            })
          case Failure(f) =>
            super.handleEntitlementFailure(f)
        }
      }
    }
  }

  /**
   * Invokes action if it exists. The POST content is deserialized into a Payload and posted
   * to the loadbalancer.
   *
   * Responses are one of (Code, Message)
   * - 200 Activation as JSON if blocking or just the result JSON iff '&result=true'
   * - 202 ActivationId as JSON (this is issued on non-blocking activation or blocking activation that times out)
   * - 404 Not Found
   * - 502 Bad Gateway
   * - 500 Internal Server Error
   */
  override def activate(user: Identity, entityName: FullyQualifiedEntityName, env: Option[Parameters])(
    implicit transid: TransactionId) = {
    parameter(
      'blocking ? false,
      'result ? false,
      'timeout.as[FiniteDuration] ? controllerActivationConfig.maxWaitForBlockingActivation) {
      (blocking, result, waitOverride) =>
        entity(as[Option[JsObject]]) { payload =>
          getEntity(WhiskActionMetaData.resolveActionAndMergeParameters(entityStore, entityName), Some {
            act: WhiskActionMetaData =>
              // resolve the action --- special case for sequences that may contain components with '_' as default package
              val action = act.resolve(user.namespace)
              onComplete(entitleReferencedEntitiesMetaData(user, Privilege.ACTIVATE, Some(action.exec))) {
                case Success(_) =>
                  val actionWithMergedParams = env.map(action.inherit(_)) getOrElse action

                  // incoming parameters may not override final parameters (i.e., parameters with already defined values)
                  // on an action once its parameters are resolved across package and binding
                  val allowInvoke = payload
                    .map(_.fields.keySet.forall(key => !actionWithMergedParams.immutableParameters.contains(key)))
                    .getOrElse(true)

                  if (allowInvoke) {
                    doInvoke(user, actionWithMergedParams, payload, blocking, waitOverride, result)
                  } else {
                    terminate(BadRequest, Messages.parametersNotAllowed)
                  }

                case Failure(f) =>
                  super.handleEntitlementFailure(f)
              }
          })
        }
    }
  }

  private def doInvoke(user: Identity,
                       actionWithMergedParams: WhiskActionMetaData,
                       payload: Option[JsObject],
                       blocking: Boolean,
                       waitOverride: FiniteDuration,
                       result: Boolean)(implicit transid: TransactionId): RequestContext => Future[RouteResult] = {
    val waitForResponse = if (blocking) Some(waitOverride) else None
    onComplete(invokeAction(user, actionWithMergedParams, payload, waitForResponse, cause = None)) {
      case Success(Left(activationId)) =>
        // non-blocking invoke or blocking invoke which got queued instead
        respondWithActivationIdHeader(activationId) {
          complete(Accepted, activationId.toJsObject)
        }
      case Success(Right(activation)) =>
        val response = if (result) activation.resultAsJson else activation.toExtendedJson()

        respondWithActivationIdHeader(activation.activationId) {
          if (activation.response.isSuccess) {
            complete(OK, response)
          } else if (activation.response.isApplicationError) {
            // actions that result is ApplicationError status are considered a 'success'
            // and will have an 'error' property in the result - the HTTP status is OK
            // and clients must check the response status if it exists
            // NOTE: response status will not exist in the JSON object if ?result == true
            // and instead clients must check if 'error' is in the JSON
            // PRESERVING OLD BEHAVIOR and will address defect in separate change
            complete(BadGateway, response)
          } else if (activation.response.isContainerError) {
            complete(BadGateway, response)
          } else {
            complete(InternalServerError, response)
          }
        }
      case Failure(t: RecordTooLargeException) =>
        logging.debug(this, s"[POST] action payload was too large")
        terminate(PayloadTooLarge)
      case Failure(RejectRequest(code, message)) =>
        logging.debug(this, s"[POST] action rejected with code $code: $message")
        terminate(code, message)
      case Failure(t: LoadBalancerException) =>
        logging.error(this, s"[POST] failed in loadbalancer: ${t.getMessage}")
        terminate(ServiceUnavailable)
      case Failure(t: Throwable) =>
        logging.error(this, s"[POST] action activation failed: ${t.getMessage}")
        terminate(InternalServerError)
    }
  }

  /**
   * Deletes action.
   *
   * Responses are one of (Code, Message)
   * - 200 WhiskAction as JSON
   * - 404 Not Found
   * - 409 Conflict
   * - 500 Internal Server Error
   */
  override def remove(user: Identity, entityName: FullyQualifiedEntityName)(implicit transid: TransactionId) = {
    deleteEntity(WhiskAction, entityStore, entityName.toDocId, (a: WhiskAction) => Future.successful({}))
  }

  /** Checks for package binding case. we don't want to allow get for a package binding in shared package */
  private def fetchEntity(entityName: FullyQualifiedEntityName, env: Option[Parameters], code: Boolean)(
    implicit transid: TransactionId) = {
    val resolvedPkg: Future[Either[String, FullyQualifiedEntityName]] = if (entityName.path.defaultPackage) {
      Future.successful(Right(entityName))
    } else {
      WhiskPackage.resolveBinding(entityStore, entityName.path.toDocId, mergeParameters = true).map { pkg =>
        val originalPackageLocation = pkg.fullyQualifiedName(withVersion = false).namespace
        if (executeOnly && originalPackageLocation != entityName.namespace) {
          Left(forbiddenGetActionBinding(entityName.toDocId.asString))
        } else {
          Right(entityName)
        }
      }
    }
    onComplete(resolvedPkg) {
      case Success(pkgFuture) =>
        pkgFuture match {
          case Left(f) => terminate(Forbidden, f)
          case Right(_) =>
            if (code) {
              getEntity(WhiskAction.resolveActionAndMergeParameters(entityStore, entityName), Some {
                action: WhiskAction =>
                  val mergedAction = env map {
                    action inherit _
                  } getOrElse action
                  complete(OK, mergedAction)
              })
            } else {
              getEntity(WhiskActionMetaData.resolveActionAndMergeParameters(entityStore, entityName), Some {
                action: WhiskActionMetaData =>
                  val mergedAction = env map {
                    action inherit _
                  } getOrElse action
                  complete(OK, mergedAction)
              })
            }
        }
      case Failure(t: Throwable) =>
        logging.error(this, s"[GET] package ${entityName.path.toDocId} failed: ${t.getMessage}")
        terminate(InternalServerError)
    }
  }

  /**
   * Gets action. The action name is prefixed with the namespace to create the primary index key.
   *
   * Responses are one of (Code, Message)
   * - 200 WhiskAction has JSON
   * - 404 Not Found
   * - 500 Internal Server Error
   */
  override def fetch(user: Identity, entityName: FullyQualifiedEntityName, env: Option[Parameters])(
    implicit transid: TransactionId) = {
    parameter('code ? true) { code =>
      //check if execute only is enabled, and if there is a discrepancy between the current user's namespace
      //and that of the entity we are trying to fetch
      if (executeOnly && user.namespace.name != entityName.namespace) {
        terminate(Forbidden, forbiddenGetAction(entityName.path.asString))
      } else {
        fetchEntity(entityName, env, code)
      }
    }
  }

  /**
   * Gets all actions in a path.
   *
   * Responses are one of (Code, Message)
   * - 200 [] or [WhiskAction as JSON]
   * - 500 Internal Server Error
   */
  override def list(user: Identity, namespace: EntityPath)(implicit transid: TransactionId) = {
    parameter(
      'skip.as[ListSkip] ? ListSkip(collection.defaultListSkip),
      'limit.as[ListLimit] ? ListLimit(collection.defaultListLimit),
      'count ? false) { (skip, limit, count) =>
      if (!count) {
        listEntities {
          WhiskAction.listCollectionInNamespace(entityStore, namespace, skip.n, limit.n, includeDocs = false) map {
            list =>
              list.fold((js) => js, (as) => as.map(WhiskAction.serdes.write(_)))
          }
        }
      } else {
        countEntities {
          WhiskAction.countCollectionInNamespace(entityStore, namespace, skip.n)
        }
      }
    }
  }

  /** Replaces default namespaces in a vector of components from a sequence with appropriate namespace. */
  private def resolveDefaultNamespace(components: Vector[FullyQualifiedEntityName],
                                      user: Identity): Vector[FullyQualifiedEntityName] = {
    // if components are part of the default namespace, they contain `_`; replace it!
    val resolvedComponents = components map { c =>
      FullyQualifiedEntityName(c.path.resolveNamespace(user.namespace), c.name)
    }
    resolvedComponents
  }

  /**
   * Creates a WhiskAction instance from the PUT request.
   */
  private def makeWhiskAction(content: WhiskActionPut, entityName: FullyQualifiedEntityName)(
    implicit transid: TransactionId) = {
    val exec = content.exec.get
    val limits = content.limits map { l =>
      ActionLimits(
        l.timeout getOrElse TimeLimit(),
        l.memory getOrElse MemoryLimit(),
        l.logs getOrElse LogLimit(),
        l.concurrency getOrElse ConcurrencyLimit())
    } getOrElse ActionLimits()
    // This is temporary while we are making sequencing directly supported in the controller.
    // The parameter override allows this to work with Pipecode.code. Any parameters other
    // than the action sequence itself are discarded and have no effect.
    // Note: While changing the implementation of sequences, components now store the fully qualified entity names
    // (which loses the leading "/"). Adding it back while both versions of the code are in place.
    val parameters = exec match {
      case seq: SequenceExec =>
        Parameters("_actions", JsArray(seq.components map { _.qualifiedNameWithLeadingSlash.toJson }))
      case _ => content.parameters getOrElse Parameters()
    }

    WhiskAction(
      entityName.path,
      entityName.name,
      exec,
      parameters,
      limits,
      content.version getOrElse SemVer(),
      content.publish getOrElse false,
      WhiskActionsApi.amendAnnotations(content.annotations getOrElse Parameters(), exec))
  }

  /** For a sequence action, gather referenced entities and authorize access. */
  private def entitleReferencedEntities(user: Identity, right: Privilege, exec: Option[Exec])(
    implicit transid: TransactionId) = {
    exec match {
      case Some(seq: SequenceExec) =>
        logging.debug(this, "checking if sequence components are accessible")
        entitlementProvider.check(user, right, referencedEntities(seq), noThrottle = true)
      case _ => Future.successful(true)
    }
  }

  private def entitleReferencedEntitiesMetaData(user: Identity, right: Privilege, exec: Option[ExecMetaDataBase])(
    implicit transid: TransactionId) = {
    exec match {
      case Some(seq: SequenceExecMetaData) =>
        logging.info(this, "checking if sequence components are accessible")
        entitlementProvider.check(user, right, referencedEntities(seq), noThrottle = true)
      case _ => Future.successful(true)
    }
  }

  /** Creates a WhiskAction from PUT content, generating default values where necessary. */
  private def make(user: Identity, entityName: FullyQualifiedEntityName, content: WhiskActionPut)(
    implicit transid: TransactionId) = {
    content.exec map {
      case seq: SequenceExec =>
        // check that the sequence conforms to max length and no recursion rules
        checkSequenceActionLimits(entityName, seq.components) map { _ =>
          makeWhiskAction(content.replace(seq), entityName)
        }
      case supportedExec if !supportedExec.deprecated =>
        Future successful makeWhiskAction(content, entityName)
      case deprecatedExec =>
        Future failed RejectRequest(BadRequest, runtimeDeprecated(deprecatedExec))

    } getOrElse Future.failed(RejectRequest(BadRequest, "exec undefined"))
  }

  /** Updates a WhiskAction from PUT content, merging old action where necessary. */
  private def update(user: Identity, content: WhiskActionPut)(action: WhiskAction)(implicit transid: TransactionId) = {
    content.exec map {
      case seq: SequenceExec =>
        // check that the sequence conforms to max length and no recursion rules
        checkSequenceActionLimits(FullyQualifiedEntityName(action.namespace, action.name), seq.components) map { _ =>
          updateWhiskAction(content.replace(seq), action)
        }
      case supportedExec if !supportedExec.deprecated =>
        Future successful updateWhiskAction(content, action)
      case deprecatedExec =>
        Future failed RejectRequest(BadRequest, runtimeDeprecated(deprecatedExec))
    } getOrElse {
      if (!action.exec.deprecated) {
        Future successful updateWhiskAction(content, action)
      } else {
        Future failed RejectRequest(BadRequest, runtimeDeprecated(action.exec))
      }
    }
  }

  /**
   * Updates a WhiskAction instance from the PUT request.
   */
  private def updateWhiskAction(content: WhiskActionPut, action: WhiskAction)(implicit transid: TransactionId) = {
    val limits = content.limits map { l =>
      ActionLimits(
        l.timeout getOrElse action.limits.timeout,
        l.memory getOrElse action.limits.memory,
        l.logs getOrElse action.limits.logs,
        l.concurrency getOrElse action.limits.concurrency)
    } getOrElse action.limits

    // This is temporary while we are making sequencing directly supported in the controller.
    // Actions that are updated with a sequence will have their parameter property overridden.
    // Actions that are updated with non-sequence actions will either set the parameter property according to
    // the content provided, or if that is not defined, and iff the previous version of the action was not a
    // sequence, inherit previous parameters. This is because sequence parameters are special and should not
    // leak to non-sequence actions.
    // If updating an action but not specifying a new exec type, then preserve the previous parameters if the
    // existing type of the action is a sequence (regardless of what parameters may be defined in the content)
    // otherwise, parameters are inferred from the content or previous values.
    // Note: While changing the implementation of sequences, components now store the fully qualified entity names
    // (which loses the leading "/"). Adding it back while both versions of the code are in place. This will disappear completely
    // once the version of sequences with "pipe.js" is removed.
    val parameters = content.exec map {
      case seq: SequenceExec =>
        Parameters("_actions", JsArray(seq.components map { c =>
          JsString("/" + c.toString)
        }))
      case _ =>
        content.parameters getOrElse {
          action.exec match {
            case seq: SequenceExec => Parameters()
            case _                 => action.parameters
          }
        }
    } getOrElse {
      action.exec match {
        case seq: SequenceExec => action.parameters // discard content.parameters
        case _                 => content.parameters getOrElse action.parameters
      }
    }

    val exec = content.exec getOrElse action.exec

    val newAnnotations = content.delAnnotations
      .map { annotationArray =>
        annotationArray.foldRight(action.annotations)((a: String, b: Parameters) => b - a)
      }
      .map(_ ++ content.annotations)
      .getOrElse(action.annotations ++ content.annotations)

    WhiskAction(
      action.namespace,
      action.name,
      exec,
      parameters,
      limits,
      content.version getOrElse action.version.upPatch,
      content.publish getOrElse action.publish,
      WhiskActionsApi.amendAnnotations(newAnnotations, exec, create = false))
      .revision[WhiskAction](action.docinfo.rev)
  }

  /**
   * Lists actions in package or binding. The router authorized the subject for the package
   * (if binding, then authorized subject for both the binding and the references package)
   * and iff authorized, this method is reached to lists actions.
   *
   * Note that when listing actions in a binding, the namespace on the actions will be that
   * of the referenced packaged, not the binding.
   */
  private def listPackageActions(user: Identity, pkgName: FullyQualifiedEntityName)(implicit transid: TransactionId) = {
    // get the package to determine if it is a package or reference
    // (this will set the appropriate namespace), and then list actions
    // NOTE: these fetches are redundant with those from the authorization
    // and should hit the cache to ameliorate the cost; this can be improved
    // but requires communicating back from the authorization service the
    // resolved namespace
    getEntity(WhiskPackage.get(entityStore, pkgName.toDocId), Some { (wp: WhiskPackage) =>
      val pkgns = wp.binding map { b =>
        logging.debug(this, s"list actions in package binding '${wp.name}' -> '$b'")
        b.namespace.addPath(b.name)
      } getOrElse {
        logging.debug(this, s"list actions in package '${wp.name}'")
        pkgName.path.addPath(wp.name)
      }
      // list actions in resolved namespace
      list(user, pkgns)
    })
  }

  /**
   * Checks that the sequence is not cyclic and that the number of atomic actions in the "inlined" sequence is lower than max allowed.
   *
   * @param sequenceAction is the action sequence to check
   * @param components the components of the sequence
   */
  private def checkSequenceActionLimits(
    sequenceAction: FullyQualifiedEntityName,
    components: Vector[FullyQualifiedEntityName])(implicit transid: TransactionId): Future[Unit] = {
    // first checks that current sequence length is allowed
    // then traverses all actions in the sequence, inlining any that are sequences
    val future = if (components.size > actionSequenceLimit) {
      Future.failed(TooManyActionsInSequence())
    } else if (components.size == 0) {
      Future.failed(NoComponentInSequence())
    } else {
      // resolve the action document id (if it's in a package/binding);
      // this assumes that entityStore is the same for actions and packages
      WhiskAction.resolveAction(entityStore, sequenceAction) flatMap { resolvedSeq =>
        val atomicActionCnt = countAtomicActionsAndCheckCycle(resolvedSeq, components)
        atomicActionCnt map { count =>
          logging.debug(this, s"sequence '$sequenceAction' atomic action count $count")
          if (count > actionSequenceLimit) {
            throw TooManyActionsInSequence()
          }
        }
      }
    }

    future recoverWith {
      case _: TooManyActionsInSequence => Future failed RejectRequest(BadRequest, sequenceIsTooLong)
      case _: NoComponentInSequence    => Future failed RejectRequest(BadRequest, sequenceNoComponent)
      case _: SequenceWithCycle        => Future failed RejectRequest(BadRequest, sequenceIsCyclic)
      case _: NoDocumentException      => Future failed RejectRequest(BadRequest, sequenceComponentNotFound)
    }
  }

  /**
   * Counts the number of atomic actions in a sequence and checks for potential cycles. The latter is done
   * by inlining any sequence components that are themselves sequences and checking if there if a reference to
   * the given original sequence.
   *
   * @param origSequence the original sequence that is updated/created which generated the checks
   * @param components the components of the a sequence to check if they reference the original sequence
   * @return Future with the number of atomic actions in the current sequence or an appropriate error if there is a cycle or a non-existent action reference
   */
  private def countAtomicActionsAndCheckCycle(
    origSequence: FullyQualifiedEntityName,
    components: Vector[FullyQualifiedEntityName])(implicit transid: TransactionId): Future[Int] = {
    if (components.size > actionSequenceLimit) {
      Future.failed(TooManyActionsInSequence())
    } else {
      // resolve components wrt any package bindings
      val resolvedComponentsFutures = components map { c =>
        WhiskAction.resolveAction(entityStore, c)
      }
      // traverse the sequence structure by checking each of its components and do the following:
      // 1. check whether any action (sequence or not) referred by the sequence (directly or indirectly)
      //    is the same as the original sequence (aka origSequence)
      // 2. count the atomic actions each component has (by "inlining" all sequences)
      val actionCountsFutures = resolvedComponentsFutures map {
        _ flatMap { resolvedComponent =>
          // check whether this component is the same as origSequence
          // this can happen when updating an atomic action to become a sequence
          if (origSequence == resolvedComponent) {
            Future failed SequenceWithCycle()
          } else {
            // check whether component is a sequence or an atomic action
            // if the component does not exist, the future will fail with appropriate error
            WhiskAction.get(entityStore, resolvedComponent.toDocId) flatMap { wskComponent =>
              wskComponent.exec match {
                case SequenceExec(seqComponents) =>
                  // sequence action, count the number of atomic actions in this sequence
                  countAtomicActionsAndCheckCycle(origSequence, seqComponents)
                case _ => Future successful 1 // atomic action count is one
              }
            }
          }
        }
      }
      // collapse the futures in one future
      val actionCountsFuture = Future.sequence(actionCountsFutures)
      // sum up all individual action counts per component
      val totalActionCount = actionCountsFuture map { actionCounts =>
        actionCounts.foldLeft(0)(_ + _)
      }
      totalActionCount
    }
  }

  /** Max atomic action count allowed for sequences. */
  private lazy val actionSequenceLimit = whiskConfig.actionSequenceLimit.toInt

  implicit val stringToFiniteDuration: Unmarshaller[String, FiniteDuration] = {
    Unmarshaller.strict[String, FiniteDuration] { value =>
      val max = controllerActivationConfig.maxWaitForBlockingActivation.toMillis

      Try { value.toInt } match {
        case Success(i) if i > 0 && i <= max => i.milliseconds
        case _ =>
          throw new IllegalArgumentException(
            Messages.invalidTimeout(controllerActivationConfig.maxWaitForBlockingActivation))
      }
    }
  }

  /** Custom unmarshaller for query parameters "limit" for "list" operations. */
  private implicit val stringToListLimit: Unmarshaller[String, ListLimit] = RestApiCommons.stringToListLimit(collection)

  /** Custom unmarshaller for query parameters "skip" for "list" operations. */
  private implicit val stringToListSkip: Unmarshaller[String, ListSkip] = RestApiCommons.stringToListSkip(collection)

}

private case class TooManyActionsInSequence() extends IllegalArgumentException
private case class NoComponentInSequence() extends IllegalArgumentException
private case class SequenceWithCycle() extends IllegalArgumentException
