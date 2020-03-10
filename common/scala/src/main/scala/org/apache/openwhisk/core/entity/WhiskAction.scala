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

package org.apache.openwhisk.core.entity

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.Base64

import akka.http.scaladsl.model.ContentTypes

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}
import spray.json._
import spray.json.DefaultJsonProtocol._
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.database.ArtifactStore
import org.apache.openwhisk.core.database.DocumentFactory
import org.apache.openwhisk.core.database.CacheChangeNotification
import org.apache.openwhisk.core.entity.Attachments._
import org.apache.openwhisk.core.entity.types.EntityStore

/**
 * ActionLimitsOption mirrors ActionLimits but makes both the timeout and memory
 * limit optional so that it is convenient to override just one limit at a time.
 */
case class ActionLimitsOption(timeout: Option[TimeLimit],
                              memory: Option[MemoryLimit],
                              logs: Option[LogLimit],
                              concurrency: Option[ConcurrencyLimit])

/**
 * WhiskActionPut is a restricted WhiskAction view that eschews properties
 * that are auto-assigned or derived from URI: namespace and name. It
 * also replaces limits with an optional counterpart for convenience of
 * overriding only one value at a time.
 */
case class WhiskActionPut(exec: Option[Exec] = None,
                          parameters: Option[Parameters] = None,
                          limits: Option[ActionLimitsOption] = None,
                          version: Option[SemVer] = None,
                          publish: Option[Boolean] = None,
                          annotations: Option[Parameters] = None,
                          delAnnotations: Option[Array[String]] = None) {

  protected[core] def replace(exec: Exec) = {
    WhiskActionPut(Some(exec), parameters, limits, version, publish, annotations)
  }

  /**
   * Resolves sequence components if they contain default namespace.
   */
  protected[core] def resolve(userNamespace: Namespace): WhiskActionPut = {
    exec map {
      case SequenceExec(components) =>
        val newExec = SequenceExec(components map { c =>
          FullyQualifiedEntityName(c.path.resolveNamespace(userNamespace), c.name)
        })
        WhiskActionPut(Some(newExec), parameters, limits, version, publish, annotations)
      case _ => this
    } getOrElse this
  }
}

abstract class WhiskActionLike(override val name: EntityName) extends WhiskEntity(name, "action") {
  def exec: Exec
  def parameters: Parameters
  def limits: ActionLimits

  /** @return true iff action has appropriate annotation. */
  def hasFinalParamsAnnotation = {
    annotations.getAs[Boolean](Annotations.FinalParamsAnnotationName) getOrElse false
  }

  /** @return a Set of immutable parameternames */
  def immutableParameters =
    if (hasFinalParamsAnnotation) {
      parameters.definedParameters
    } else Set.empty[String]

  def toJson =
    JsObject(
      "namespace" -> namespace.toJson,
      "name" -> name.toJson,
      "exec" -> exec.toJson,
      "parameters" -> parameters.toJson,
      "limits" -> limits.toJson,
      "version" -> version.toJson,
      "publish" -> publish.toJson,
      "annotations" -> annotations.toJson)
}

abstract class WhiskActionLikeMetaData(override val name: EntityName) extends WhiskActionLike(name) {
  override def exec: ExecMetaDataBase
}

/**
 * A WhiskAction provides an abstraction of the meta-data
 * for a whisk action.
 *
 * The WhiskAction object is used as a helper to adapt objects between
 * the schema used by the database and the WhiskAction abstraction.
 *
 * @param namespace the namespace for the action
 * @param name the name of the action
 * @param exec the action executable details
 * @param parameters the set of parameters to bind to the action environment
 * @param limits the limits to impose on the action
 * @param version the semantic version
 * @param publish true to share the action or false otherwise
 * @param annotations the set of annotations to attribute to the action
 * @param updated the timestamp when the action is updated
 * @throws IllegalArgumentException if any argument is undefined
 */
@throws[IllegalArgumentException]
case class WhiskAction(namespace: EntityPath,
                       override val name: EntityName,
                       exec: Exec,
                       parameters: Parameters = Parameters(),
                       limits: ActionLimits = ActionLimits(),
                       version: SemVer = SemVer(),
                       publish: Boolean = false,
                       annotations: Parameters = Parameters(),
                       override val updated: Instant = WhiskEntity.currentMillis())
    extends WhiskActionLike(name) {

  require(exec != null, "exec undefined")
  require(limits != null, "limits undefined")

  /**
   * Merges parameters (usually from package) with existing action parameters.
   * Existing parameters supersede those in p.
   */
  def inherit(p: Parameters): WhiskAction = copy(parameters = p ++ parameters).revision[WhiskAction](rev)

  /**
   * Resolves sequence components if they contain default namespace.
   */
  protected[core] def resolve(userNamespace: Namespace): WhiskAction = {
    resolve(userNamespace.name)
  }

  /**
   * Resolves sequence components if they contain default namespace.
   */
  protected[core] def resolve(userNamespace: EntityName): WhiskAction = {
    exec match {
      case SequenceExec(components) =>
        val newExec = SequenceExec(components map { c =>
          FullyQualifiedEntityName(c.path.resolveNamespace(userNamespace), c.name)
        })
        copy(exec = newExec).revision[WhiskAction](rev)
      case _ => this
    }
  }

  def toExecutableWhiskAction: Option[ExecutableWhiskAction] = exec match {
    case codeExec: CodeExec[_] =>
      Some(
        ExecutableWhiskAction(namespace, name, codeExec, parameters, limits, version, publish, annotations)
          .revision[ExecutableWhiskAction](rev))
    case _ => None
  }

  /**
   * This the action summary as computed by the database view.
   * Strictly used in view testing to enforce alignment.
   */
  override def summaryAsJson: JsObject = {
    val binary = exec match {
      case c: CodeExec[_] => c.binary
      case _              => false
    }

    JsObject(
      super.summaryAsJson.fields +
        ("limits" -> limits.toJson) +
        ("exec" -> JsObject("binary" -> JsBoolean(binary))))
  }
}

@throws[IllegalArgumentException]
case class WhiskActionMetaData(namespace: EntityPath,
                               override val name: EntityName,
                               exec: ExecMetaDataBase,
                               parameters: Parameters = Parameters(),
                               limits: ActionLimits = ActionLimits(),
                               version: SemVer = SemVer(),
                               publish: Boolean = false,
                               annotations: Parameters = Parameters(),
                               override val updated: Instant = WhiskEntity.currentMillis(),
                               binding: Option[EntityPath] = None)
    extends WhiskActionLikeMetaData(name) {

  require(exec != null, "exec undefined")
  require(limits != null, "limits undefined")

  /**
   * Merges parameters (usually from package) with existing action parameters.
   * Existing parameters supersede those in p.
   */
  def inherit(p: Parameters, binding: Option[EntityPath] = None) =
    copy(parameters = p ++ parameters, binding = binding).revision[WhiskActionMetaData](rev)

  /**
   * Resolves sequence components if they contain default namespace.
   */
  protected[core] def resolve(userNamespace: Namespace): WhiskActionMetaData = {
    exec match {
      case SequenceExecMetaData(components) =>
        val newExec = SequenceExecMetaData(components map { c =>
          FullyQualifiedEntityName(c.path.resolveNamespace(userNamespace.name), c.name)
        })
        copy(exec = newExec).revision[WhiskActionMetaData](rev)
      case _ => this
    }
  }

  def toExecutableWhiskAction = exec match {
    case execMetaData: ExecMetaData =>
      Some(
        ExecutableWhiskActionMetaData(
          namespace,
          name,
          execMetaData,
          parameters,
          limits,
          version,
          publish,
          annotations,
          binding)
          .revision[ExecutableWhiskActionMetaData](rev))
    case _ =>
      None
  }
}

/**
 * Variant of WhiskAction which only includes information necessary to be
 * executed by an Invoker.
 *
 * exec is typed to CodeExec to guarantee executability by an Invoker.
 *
 * Note: Two actions are equal regardless of their DocRevision if there is one.
 * The invoker uses action equality when matching actions to warm containers.
 * That means creating an action, invoking it, then deleting/recreating/reinvoking
 * it will reuse the previous container. The delete/recreate restores the SemVer to 0.0.1.
 *
 * @param namespace the namespace for the action
 * @param name the name of the action
 * @param exec the action executable details
 * @param parameters the set of parameters to bind to the action environment
 * @param limits the limits to impose on the action
 * @param version the semantic version
 * @param publish true to share the action or false otherwise
 * @param annotations the set of annotations to attribute to the action
 * @param binding the path of the package binding if any
 * @throws IllegalArgumentException if any argument is undefined
 */
@throws[IllegalArgumentException]
case class ExecutableWhiskAction(namespace: EntityPath,
                                 override val name: EntityName,
                                 exec: CodeExec[_],
                                 parameters: Parameters = Parameters(),
                                 limits: ActionLimits = ActionLimits(),
                                 version: SemVer = SemVer(),
                                 publish: Boolean = false,
                                 annotations: Parameters = Parameters(),
                                 binding: Option[EntityPath] = None)
    extends WhiskActionLike(name) {

  require(exec != null, "exec undefined")
  require(limits != null, "limits undefined")

  /**
   * Gets initializer for action. This typically includes the code to execute,
   * or a zip file containing the executable artifacts.
   *
   * @param env optional map of properties to be exported to the environment
   */
  def containerInitializer(env: Map[String, JsValue] = Map.empty): JsObject = {
    val code = Option(exec.codeAsJson).filter(_ != JsNull).map("code" -> _)
    val envargs = if (env.nonEmpty) {
      val stringifiedEnvVars = env.map {
        case (k, v: JsString)  => (k, v)
        case (k, JsNull)       => (k, JsString.empty)
        case (k, JsBoolean(v)) => (k, JsString(v.toString))
        case (k, JsNumber(v))  => (k, JsString(v.toString))
        case (k, v)            => (k, JsString(v.compactPrint))
      }

      Some("env" -> JsObject(stringifiedEnvVars))
    } else None

    val base =
      Map("name" -> name.toJson, "binary" -> exec.binary.toJson, "main" -> exec.entryPoint.getOrElse("main").toJson)

    JsObject(base ++ envargs ++ code)
  }

  def toWhiskAction =
    WhiskAction(namespace, name, exec, parameters, limits, version, publish, annotations)
      .revision[WhiskAction](rev)
}

@throws[IllegalArgumentException]
case class ExecutableWhiskActionMetaData(namespace: EntityPath,
                                         override val name: EntityName,
                                         exec: ExecMetaData,
                                         parameters: Parameters = Parameters(),
                                         limits: ActionLimits = ActionLimits(),
                                         version: SemVer = SemVer(),
                                         publish: Boolean = false,
                                         annotations: Parameters = Parameters(),
                                         binding: Option[EntityPath] = None)
    extends WhiskActionLikeMetaData(name) {

  require(exec != null, "exec undefined")
  require(limits != null, "limits undefined")

  def toWhiskAction =
    WhiskActionMetaData(namespace, name, exec, parameters, limits, version, publish, annotations, updated)
      .revision[WhiskActionMetaData](rev)

  /**
   * Some fully qualified name only if there's a binding, else None.
   */
  def bindingFullyQualifiedName: Option[FullyQualifiedEntityName] =
    binding.map(ns => FullyQualifiedEntityName(ns, name, None))

}

object WhiskAction extends DocumentFactory[WhiskAction] with WhiskEntityQueries[WhiskAction] with DefaultJsonProtocol {
  import WhiskActivation.instantSerdes

  val execFieldName = "exec"
  val requireWhiskAuthHeader = "x-require-whisk-auth"

  override val collectionName = "actions"
  override val cacheEnabled = true

  override implicit val serdes = jsonFormat(
    WhiskAction.apply,
    "namespace",
    "name",
    "exec",
    "parameters",
    "limits",
    "version",
    "publish",
    "annotations",
    "updated")

  // overriden to store attached code
  override def put[A >: WhiskAction](db: ArtifactStore[A], doc: WhiskAction, old: Option[WhiskAction])(
    implicit transid: TransactionId,
    notifier: Option[CacheChangeNotification]): Future[DocInfo] = {

    def putWithAttachment(code: String, binary: Boolean, exec: AttachedCode) = {
      implicit val logger = db.logging
      implicit val ec = db.executionContext

      val oldAttachment = old.flatMap(getAttachment)
      val (bytes, attachmentType) = if (binary) {
        (Base64.getDecoder.decode(code), ContentTypes.`application/octet-stream`)
      } else {
        (code.getBytes(UTF_8), ContentTypes.`text/plain(UTF-8)`)
      }
      val stream = new ByteArrayInputStream(bytes)
      super.putAndAttach(
        db,
        doc
          .copy(parameters = doc.parameters.lock(ParameterEncryption.singleton.default))
          .revision[WhiskAction](doc.rev),
        attachmentUpdater,
        attachmentType,
        stream,
        oldAttachment,
        Some { a: WhiskAction =>
          a.copy(exec = exec.inline(code.getBytes(UTF_8)))
        })
    }

    Try {
      require(db != null, "db undefined")
      require(doc != null, "doc undefined")
    } map { _ =>
      doc.exec match {
        case exec @ CodeExecAsAttachment(_, Inline(code), _, binary) =>
          putWithAttachment(code, binary, exec)
        case exec @ BlackBoxExec(_, Some(Inline(code)), _, _, binary) =>
          putWithAttachment(code, binary, exec)
        case _ =>
          super.put(
            db,
            doc
              .copy(parameters = doc.parameters.lock(ParameterEncryption.singleton.default))
              .revision[WhiskAction](doc.rev),
            old)
      }
    } match {
      case Success(f) => f
      case Failure(f) => Future.failed(f)
    }
  }

  // overriden to retrieve attached code
  override def get[A >: WhiskAction](
    db: ArtifactStore[A],
    doc: DocId,
    rev: DocRevision = DocRevision.empty,
    fromCache: Boolean)(implicit transid: TransactionId, mw: Manifest[WhiskAction]): Future[WhiskAction] = {

    implicit val ec = db.executionContext

    val inlineActionCode: WhiskAction => Future[WhiskAction] = { action =>
      def getWithAttachment(attached: Attached, binary: Boolean, exec: AttachedCode) = {
        val boas = new ByteArrayOutputStream()
        val wrapped = if (binary) Base64.getEncoder().wrap(boas) else boas

        getAttachment[A](db, action, attached, wrapped, Some { a: WhiskAction =>
          wrapped.close()
          val newAction = a.copy(exec = exec.inline(boas.toByteArray))
          newAction.revision(a.rev)
          newAction
        })
      }

      action.exec match {
        case exec @ CodeExecAsAttachment(_, attached: Attached, _, binary) =>
          getWithAttachment(attached, binary, exec)
        case exec @ BlackBoxExec(_, Some(attached: Attached), _, _, binary) =>
          getWithAttachment(attached, binary, exec)
        case _ =>
          Future.successful(action)
      }
    }
    super.getWithAttachment(db, doc, rev, fromCache, attachmentHandler, inlineActionCode)
  }

  def attachmentHandler(action: WhiskAction, attached: Attached): WhiskAction = {
    def checkName(name: String) = {
      require(
        name == attached.attachmentName,
        s"Attachment name '${attached.attachmentName}' does not match the expected name '$name'")
    }
    val eu = action.exec match {
      case exec @ CodeExecAsAttachment(_, Attached(attachmentName, _, _, _), _, _) =>
        checkName(attachmentName)
        exec.attach(attached)
      case exec @ BlackBoxExec(_, Some(Attached(attachmentName, _, _, _)), _, _, _) =>
        checkName(attachmentName)
        exec.attach(attached)
      case exec => exec
    }
    action.copy(exec = eu).revision[WhiskAction](action.rev)
  }

  def attachmentUpdater(action: WhiskAction, updatedAttachment: Attached): WhiskAction = {
    action.exec match {
      case exec: AttachedCode =>
        action.copy(exec = exec.attach(updatedAttachment)).revision[WhiskAction](action.rev)
      case _ => action
    }
  }

  def getAttachment(action: WhiskAction): Option[Attached] = {
    action.exec match {
      case CodeExecAsAttachment(_, a: Attached, _, _)  => Some(a)
      case BlackBoxExec(_, Some(a: Attached), _, _, _) => Some(a)
      case _                                           => None
    }
  }

  override def del[Wsuper >: WhiskAction](db: ArtifactStore[Wsuper], doc: DocInfo)(
    implicit transid: TransactionId,
    notifier: Option[CacheChangeNotification]): Future[Boolean] = {
    Try {
      require(db != null, "db undefined")
      require(doc != null, "doc undefined")
    }.map { _ =>
      val fa = super.del(db, doc)
      implicit val ec = db.executionContext
      fa.flatMap { _ =>
        super.deleteAttachments(db, doc)
      }
    } match {
      case Success(f) => f
      case Failure(f) => Future.failed(f)
    }
  }

  /**
   * Resolves an action name if it is contained in a package.
   * Look up the package to determine if it is a binding or the actual package.
   * If it's a binding, rewrite the fully qualified name of the action using the actual package path name.
   * If it's the actual package, use its name directly as the package path name.
   */
  def resolveAction(db: EntityStore, fullyQualifiedActionName: FullyQualifiedEntityName)(
    implicit ec: ExecutionContext,
    transid: TransactionId): Future[FullyQualifiedEntityName] = {
    // first check that there is a package to be resolved
    val entityPath = fullyQualifiedActionName.path
    if (entityPath.defaultPackage) {
      // this is the default package, nothing to resolve
      Future.successful(fullyQualifiedActionName)
    } else {
      // there is a package to be resolved
      val pkgDocId = fullyQualifiedActionName.path.toDocId
      val actionName = fullyQualifiedActionName.name
      WhiskPackage.resolveBinding(db, pkgDocId) map {
        _.fullyQualifiedName(withVersion = false).add(actionName)
      }
    }
  }

  /**
   * Resolves an action name if it is contained in a package.
   * Look up the package to determine if it is a binding or the actual package.
   * If it's a binding, rewrite the fully qualified name of the action using the actual package path name.
   * If it's the actual package, use its name directly as the package path name.
   * While traversing the package bindings, merge the parameters.
   */
  def resolveActionAndMergeParameters(entityStore: EntityStore, fullyQualifiedName: FullyQualifiedEntityName)(
    implicit ec: ExecutionContext,
    transid: TransactionId): Future[WhiskAction] = {
    // first check that there is a package to be resolved
    val entityPath = fullyQualifiedName.path
    if (entityPath.defaultPackage) {
      // this is the default package, nothing to resolve
      WhiskAction.get(entityStore, fullyQualifiedName.toDocId)
    } else {
      // there is a package to be resolved
      val pkgDocid = fullyQualifiedName.path.toDocId
      val actionName = fullyQualifiedName.name
      val wp = WhiskPackage.resolveBinding(entityStore, pkgDocid, mergeParameters = true)
      wp flatMap { resolvedPkg =>
        // fully resolved name for the action
        val fqnAction = resolvedPkg.fullyQualifiedName(withVersion = false).add(actionName)
        // get the whisk action associate with it and inherit the parameters from the package/binding
        WhiskAction.get(entityStore, fqnAction.toDocId) map {
          _.inherit(resolvedPkg.parameters)
        }
      }
    }
  }
}

object WhiskActionMetaData
    extends DocumentFactory[WhiskActionMetaData]
    with WhiskEntityQueries[WhiskActionMetaData]
    with DefaultJsonProtocol {

  import WhiskActivation.instantSerdes

  override val collectionName = "actions"
  override val cacheEnabled = true

  override implicit val serdes = jsonFormat(
    WhiskActionMetaData.apply,
    "namespace",
    "name",
    "exec",
    "parameters",
    "limits",
    "version",
    "publish",
    "annotations",
    "updated",
    "binding")

  /**
   * Resolves an action name if it is contained in a package.
   * Look up the package to determine if it is a binding or the actual package.
   * If it's a binding, rewrite the fully qualified name of the action using the actual package path name.
   * If it's the actual package, use its name directly as the package path name.
   */
  def resolveAction(db: EntityStore, fullyQualifiedActionName: FullyQualifiedEntityName)(
    implicit ec: ExecutionContext,
    transid: TransactionId): Future[FullyQualifiedEntityName] = {
    // first check that there is a package to be resolved
    val entityPath = fullyQualifiedActionName.path
    if (entityPath.defaultPackage) {
      // this is the default package, nothing to resolve
      Future.successful(fullyQualifiedActionName)
    } else {
      // there is a package to be resolved
      val pkgDocId = fullyQualifiedActionName.path.toDocId
      val actionName = fullyQualifiedActionName.name
      WhiskPackage.resolveBinding(db, pkgDocId) map {
        _.fullyQualifiedName(withVersion = false).add(actionName)
      }
    }
  }

  /**
   * Resolves an action name if it is contained in a package.
   * Look up the package to determine if it is a binding or the actual package.
   * If it's a binding, rewrite the fully qualified name of the action using the actual package path name.
   * If it's the actual package, use its name directly as the package path name.
   * While traversing the package bindings, merge the parameters.
   */
  def resolveActionAndMergeParameters(entityStore: EntityStore, fullyQualifiedName: FullyQualifiedEntityName)(
    implicit ec: ExecutionContext,
    transid: TransactionId): Future[WhiskActionMetaData] = {
    // first check that there is a package to be resolved
    val entityPath = fullyQualifiedName.path
    if (entityPath.defaultPackage) {
      // this is the default package, nothing to resolve
      WhiskActionMetaData.get(entityStore, fullyQualifiedName.toDocId)
    } else {
      // there is a package to be resolved
      val pkgDocid = fullyQualifiedName.path.toDocId
      val actionName = fullyQualifiedName.name
      val wp = WhiskPackage.resolveBinding(entityStore, pkgDocid, mergeParameters = true)
      wp flatMap { resolvedPkg =>
        // fully resolved name for the action
        val fqnAction = resolvedPkg.fullyQualifiedName(withVersion = false).add(actionName)
        // get the whisk action associate with it and inherit the parameters from the package/binding
        WhiskActionMetaData.get(entityStore, fqnAction.toDocId) map {
          _.inherit(
            resolvedPkg.parameters,
            if (fullyQualifiedName.path.equals(resolvedPkg.fullPath)) None
            else Some(fullyQualifiedName.path))
        }
      }
    }
  }
}

object ActionLimitsOption extends DefaultJsonProtocol {
  implicit val serdes = jsonFormat4(ActionLimitsOption.apply)
}

object WhiskActionPut extends DefaultJsonProtocol {
  implicit val serdes = jsonFormat7(WhiskActionPut.apply)
}
