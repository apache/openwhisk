/*
 * Copyright 2015-2016 IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
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
import scala.util.{ Try, Success, Failure }

import spray.json._
import spray.json.DefaultJsonProtocol._
import whisk.common.TransactionId
import whisk.core.database.ArtifactStore
import whisk.core.database.DocumentFactory
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
case class WhiskActionPut(
    exec: Option[Exec] = None,
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
                val newExec = SequenceExec(components map {
                    c => FullyQualifiedEntityName(c.path.resolveNamespace(userNamespace), c.name)
                })
                WhiskActionPut(Some(newExec), parameters, limits, version, publish, annotations)
            case _ => this
        } getOrElse this
    }
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
case class WhiskAction(
    namespace: EntityPath,
    override val name: EntityName,
    exec: Exec,
    parameters: Parameters = Parameters(),
    limits: ActionLimits = ActionLimits(),
    version: SemVer = SemVer(),
    publish: Boolean = false,
    annotations: Parameters = Parameters())
    extends WhiskEntity(name) {

    require(exec != null, "exec undefined")
    require(limits != null, "limits undefined")

    /** @return true iff action has appropriate annotation. */
    def hasFinalParamsAnnotation = {
        annotations.asBool(WhiskAction.finalParamsAnnotationName) getOrElse false
    }

    /** @return a Set of immutable parameternames */
    def immutableParameters = if (hasFinalParamsAnnotation) {
        parameters.definedParameters
    } else Set.empty[String]

    /**
     * Merges parameters (usually from package) with existing action parameters.
     * Existing parameters supersede those in p.
     */
    def inherit(p: Parameters) = copy(parameters = p ++ parameters).revision[WhiskAction](rev)

    /**
     * Gets initializer for action if it is supported. This typically includes
     * the code to execute, or a zip file containing the executable artifacts.
     * Some actions (i.e., sequences) have no initializers since they are not executed
     * explicitly inside containers.
     */
    def containerInitializer: Option[JsObject] = {
        exec match {
            case c: CodeExec[_] =>
                val code = Option(c.codeAsJson).filter(_ != JsNull).map("code" -> _)
                val base = Map("name" -> name.toJson, "binary" -> c.binary.toJson, "main" -> c.entryPoint.getOrElse("main").toJson)
                Some(JsObject(base ++ code))
            case _ => None
        }
    }

    /**
     * Resolves sequence components if they contain default namespace.
     */
    protected[core] def resolve(userNamespace: EntityName): WhiskAction = {
        exec match {
            case SequenceExec(components) =>
                val newExec = SequenceExec(components map {
                    c => FullyQualifiedEntityName(c.path.resolveNamespace(userNamespace), c.name)
                })
                copy(exec = newExec).revision[WhiskAction](rev)
            case _ => this
        }
    }

    def toJson = WhiskAction.serdes.write(this).asJsObject

    def toExecutableWhiskAction = exec match {
        case codeExec: CodeExec[_] => Some(ExecutableWhiskAction(namespace, name, codeExec, limits, version, rev))
        case _                     => None
    }
}

/**
 * Variant of WhiskAction which only includes information necessary to be
 * executed by an Invoker.
 *
 * exec is typed to CodeExec to guarantee executability by an Invoker.
 *
 * rev is stored as part of the case-class to make action-matching as
 * narrow as possible. version is not enough, because a user might delete
 * an action and recreate it later, effectively resetting the version
 * counter and thus producing "duplicates".
 *
 * @param namespace the namespace for the action
 * @param name the name of the action
 * @param exec the action executable details
 * @param limits the limits to impose on the action
 * @param version the semantic version
 * @param rev the revision of the document
 */
case class ExecutableWhiskAction(
    namespace: EntityPath,
    name: EntityName,
    exec: CodeExec[_],
    limits: ActionLimits = ActionLimits(),
    version: SemVer = SemVer(),
    rev: DocRevision = DocRevision.empty) {

    /**
     * Gets initializer for action. This typically includes the code to execute,
     * or a zip file containing the executable artifacts.
     */
    def containerInitializer: JsObject = {
        val code = Option(exec.codeAsJson).filter(_ != JsNull).map("code" -> _)
        val base = Map("name" -> name.toJson, "binary" -> exec.binary.toJson, "main" -> exec.entryPoint.getOrElse("main").toJson)
        JsObject(base ++ code)
    }

    /**
     * The name of the entity qualified with its namespace and version for
     * creating unique keys in backend services.
     */
    final def fullyQualifiedName(withVersion: Boolean) = FullyQualifiedEntityName(namespace, name, if (withVersion) Some(version) else None)
}

object WhiskAction
    extends DocumentFactory[WhiskAction]
    with WhiskEntityQueries[WhiskAction]
    with DefaultJsonProtocol {

    val execFieldName = "exec"
    val finalParamsAnnotationName = "final"

    override val collectionName = "actions"

    override implicit val serdes = jsonFormat8(WhiskAction.apply)

    override val cacheEnabled = true
    override def cacheKeyForUpdate(w: WhiskAction) = w.docid.asDocInfo

    // overriden to store attached code
    override def put[A >: WhiskAction](db: ArtifactStore[A], doc: WhiskAction)(
        implicit transid: TransactionId): Future[DocInfo] = {

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

                    for (
                        i1 <- super.put(db, newDoc);
                        i2 <- attach[A](db, i1, manifest.attachmentName, manifest.attachmentType, stream)
                    ) yield i2

                case _ =>
                    super.put(db, doc)
            }
        } match {
            case Success(f) => f
            case Failure(f) => Future.failed(f)
        }
    }

    // overriden to retrieve attached code
    override def get[A >: WhiskAction](db: ArtifactStore[A], doc: DocId, rev: DocRevision = DocRevision.empty, fromCache: Boolean)(
        implicit transid: TransactionId, mw: Manifest[WhiskAction]): Future[WhiskAction] = {

        implicit val ec = db.executionContext

        val fa = super.get(db, doc, rev, fromCache = fromCache)

        fa.flatMap { action =>
            action.exec match {
                case exec @ CodeExecAsAttachment(_, Attached(_, _), _) =>
                    val boas = new ByteArrayOutputStream()
                    val b64s = Base64.getEncoder().wrap(boas)
                    val manifest = exec.manifest.attached.get

                    getAttachment[A](db, action.docinfo, manifest.attachmentName, b64s).map { _ =>
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
        implicit ec: ExecutionContext, transid: TransactionId): Future[FullyQualifiedEntityName] = {
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
        implicit ec: ExecutionContext, transid: TransactionId): Future[WhiskAction] = {
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

object ActionLimitsOption extends DefaultJsonProtocol {
    implicit val serdes = jsonFormat3(ActionLimitsOption.apply)
}

object WhiskActionPut extends DefaultJsonProtocol {
    implicit val serdes = jsonFormat6(WhiskActionPut.apply)
}
