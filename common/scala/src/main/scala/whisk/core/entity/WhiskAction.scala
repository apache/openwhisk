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

package whisk.core.entity

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

import spray.json._
import spray.json.DefaultJsonProtocol._
import whisk.common.TransactionId
import whisk.core.database.ArtifactStore
import whisk.core.database.DocumentFactory
import whisk.core.database.CacheChangeNotification
import whisk.core.entity.Attachments._
import whisk.core.entity.types.EntityStore

/**
 * ActionLimitsOption mirrors ActionLimits but makes both the timeout and memory
 * limit optional so that it is convenient to override just one limit at a time.
 */
case class ActionLimitsOption(timeout: Option[TimeLimit], memory: Option[MemoryLimit], logs: Option[LogLimit])

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
                          annotations: Option[Parameters] = None) {

  protected[core] def replace(exec: Exec) = {
    WhiskActionPut(Some(exec), parameters, limits, version, publish, annotations)
  }

  /**
   * Resolves sequence components if they contain default namespace.
   */
  protected[core] def resolve(userNamespace: EntityName): WhiskActionPut = {
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

abstract class WhiskActionLike(override val name: EntityName) extends WhiskEntity(name) {
  def exec: Exec
  def parameters: Parameters
  def limits: ActionLimits

  /** @return true iff action has appropriate annotation. */
  def hasFinalParamsAnnotation = {
    annotations.asBool(WhiskAction.finalParamsAnnotationName) getOrElse false
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
 * @param annotation the set of annotations to attribute to the action
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
                       annotations: Parameters = Parameters())
    extends WhiskActionLike(name) {

  require(exec != null, "exec undefined")
  require(limits != null, "limits undefined")

  /**
   * Merges parameters (usually from package) with existing action parameters.
   * Existing parameters supersede those in p.
   */
  def inherit(p: Parameters) = copy(parameters = p ++ parameters).revision[WhiskAction](rev)

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

  def toExecutableWhiskAction = exec match {
    case codeExec: CodeExec[_] =>
      Some(
        ExecutableWhiskAction(namespace, name, codeExec, parameters, limits, version, publish, annotations)
          .revision[ExecutableWhiskAction](rev))
    case _ =>
      None
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
                               annotations: Parameters = Parameters())
    extends WhiskActionLikeMetaData(name) {

  require(exec != null, "exec undefined")
  require(limits != null, "limits undefined")

  /**
   * Merges parameters (usually from package) with existing action parameters.
   * Existing parameters supersede those in p.
   */
  def inherit(p: Parameters) = copy(parameters = p ++ parameters).revision[WhiskActionMetaData](rev)

  /**
   * Resolves sequence components if they contain default namespace.
   */
  protected[core] def resolve(userNamespace: EntityName): WhiskActionMetaData = {
    exec match {
      case SequenceExecMetaData(components) =>
        val newExec = SequenceExecMetaData(components map { c =>
          FullyQualifiedEntityName(c.path.resolveNamespace(userNamespace), c.name)
        })
        copy(exec = newExec).revision[WhiskActionMetaData](rev)
      case _ => this
    }
  }

  def toExecutableWhiskAction = exec match {
    case execMetaData: ExecMetaData =>
      Some(
        ExecutableWhiskActionMetaData(namespace, name, execMetaData, parameters, limits, version, publish, annotations)
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
 * @param namespace the namespace for the action
 * @param name the name of the action
 * @param exec the action executable details
 * @param parameters the set of parameters to bind to the action environment
 * @param limits the limits to impose on the action
 * @param version the semantic version
 * @param publish true to share the action or false otherwise
 * @param annotation the set of annotations to attribute to the action
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
                                 annotations: Parameters = Parameters())
    extends WhiskActionLike(name) {

  require(exec != null, "exec undefined")
  require(limits != null, "limits undefined")

  /**
   * Gets initializer for action. This typically includes the code to execute,
   * or a zip file containing the executable artifacts.
   */
  def containerInitializer: JsObject = {
    val code = Option(exec.codeAsJson).filter(_ != JsNull).map("code" -> _)
    val base =
      Map("name" -> name.toJson, "binary" -> exec.binary.toJson, "main" -> exec.entryPoint.getOrElse("main").toJson)
    JsObject(base ++ code)
  }

  def toWhiskAction =
    WhiskAction(namespace, name, exec, parameters, limits, version, publish, annotations).revision[WhiskAction](rev)
}

@throws[IllegalArgumentException]
case class ExecutableWhiskActionMetaData(namespace: EntityPath,
                                         override val name: EntityName,
                                         exec: ExecMetaData,
                                         parameters: Parameters = Parameters(),
                                         limits: ActionLimits = ActionLimits(),
                                         version: SemVer = SemVer(),
                                         publish: Boolean = false,
                                         annotations: Parameters = Parameters())
    extends WhiskActionLikeMetaData(name) {

  require(exec != null, "exec undefined")
  require(limits != null, "limits undefined")

  def toWhiskAction =
    WhiskActionMetaData(namespace, name, exec, parameters, limits, version, publish, annotations)
      .revision[WhiskActionMetaData](rev)
}

object WhiskAction extends DocumentFactory[WhiskAction] with WhiskEntityQueries[WhiskAction] with DefaultJsonProtocol {

  val execFieldName = "exec"
  val finalParamsAnnotationName = "final"

  override val collectionName = "actions"

  override implicit val serdes = jsonFormat(
    WhiskAction.apply,
    "namespace",
    "name",
    "exec",
    "parameters",
    "limits",
    "version",
    "publish",
    "annotations")

  override val cacheEnabled = true

  // overriden to store attached code
  override def put[A >: WhiskAction](db: ArtifactStore[A], doc: WhiskAction)(
    implicit transid: TransactionId,
    notifier: Option[CacheChangeNotification]): Future[DocInfo] = {

    Try {
      require(db != null, "db undefined")
      require(doc != null, "doc undefined")
    } map { _ =>
      doc.exec match {
        case exec @ CodeExecAsAttachment(_, Inline(code), _) =>
          implicit val logger = db.logging
          implicit val ec = db.executionContext

          val newDoc = doc.copy(exec = exec.attach)
          newDoc.revision(doc.rev)

          val stream = new ByteArrayInputStream(Base64.getDecoder().decode(code))
          val manifest = exec.manifest.attached.get

          for (i1 <- super.put(db, newDoc);
               i2 <- attach[A](db, i1, manifest.attachmentName, manifest.attachmentType, stream)) yield i2

        case _ =>
          super.put(db, doc)
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

    val fa = super.get(db, doc, rev, fromCache)

    fa.flatMap { action =>
      action.exec match {
        case exec @ CodeExecAsAttachment(_, Attached(attachmentName, _), _) =>
          val boas = new ByteArrayOutputStream()
          val b64s = Base64.getEncoder().wrap(boas)

          getAttachment[A](db, action.docinfo, attachmentName, b64s).map { _ =>
            b64s.close()
            val newAction = action.copy(exec = exec.inline(boas.toByteArray))
            newAction.revision(action.rev)
            newAction
          }

        case _ =>
          Future.successful(action)
      }
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
        WhiskAction.get(entityStore, fqnAction.toDocId) map { _.inherit(resolvedPkg.parameters) }
      }
    }
  }
}

object WhiskActionMetaData
    extends DocumentFactory[WhiskActionMetaData]
    with WhiskEntityQueries[WhiskActionMetaData]
    with DefaultJsonProtocol {

  val execFieldName = "exec"
  val finalParamsAnnotationName = "final"

  override val collectionName = "actions"

  override implicit val serdes = jsonFormat(
    WhiskActionMetaData.apply,
    "namespace",
    "name",
    "exec",
    "parameters",
    "limits",
    "version",
    "publish",
    "annotations")

  override val cacheEnabled = true

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
          _.inherit(resolvedPkg.parameters)
        }
      }
    }
  }
}

object ActionLimitsOption extends DefaultJsonProtocol {
  implicit val serdes = jsonFormat3(ActionLimitsOption.apply)
}

object WhiskActionPut extends DefaultJsonProtocol {
  implicit val serdes = jsonFormat6(WhiskActionPut.apply)
}
