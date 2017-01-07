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

import scala.concurrent.Future
import scala.concurrent.ExecutionContext
import scala.util.{ Try, Success, Failure }

import akka.http.scaladsl.model.ContentType
import akka.http.scaladsl.model.MediaTypes

import spray.json._
import spray.json.DefaultJsonProtocol._

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64

import whisk.common.Logging
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

    /**
     * Merges parameters (usually from package) with existing action parameters.
     * Existing parameters supersede those in p.
     */
    def inherit(p: Parameters) = {
        WhiskAction(namespace, name, exec, p ++ parameters, limits, version, publish, annotations)
    }

    /**
     * Gets the container image name for the action (if one is required).
     * If the action is a black box action, return the image name. Otherwise
     * return a standard image name for running Javascript or Swift actions for example.
     *
     * @returns Some(image name) for container to run action Exec if one is required else None.
     */
    def containerImageName(registry: String, prefix: String, tag: String): Option[String] = {
        exec match {
            case e: CodeExec[_] => Some(WhiskAction.containerImageName(e, registry, prefix, tag))
            case _              => None
        }

    }

    /**
     * Gets initializer for action if it is supported. This typically includes
     * the code to execute, or a zip file containing the executable artifacts.
     * Some actions (i.e., sequences) have no initializers since they are not executed
     * explicitly inside containers.
     */
    def containerInitializer: Option[JsObject] = {
        def getNodeInitializer(code: String, binary: Boolean, main: Option[String]) = {
            JsObject(
                "name" -> name.toJson,
                "binary" -> JsBoolean(binary),
                "main" -> JsString(main.getOrElse("main")),
                "code" -> JsString(code))
        }

        exec match {
            case n: NodeJSAbstractExec => Some(getNodeInitializer(n.code, n.binary, n.main))
            case s: SwiftAbstractExec =>
                Some(JsObject(
                    "name" -> name.toJson,
                    "code" -> s.code.toJson,
                    "main" -> s.main.getOrElse("main").toJson))
            case JavaExec(jar, main) =>
                Some(JsObject(
                    "name" -> name.toJson,
                    "jar" -> jar.toJson,
                    "main" -> main.toJson))
            case PythonExec(code, main) =>
                Some(JsObject(
                    "name" -> name.toJson,
                    "code" -> code.toJson,
                    "main" -> main.getOrElse("main").toJson))
            case b @ BlackBoxExec(image, code) =>
                Some(code map {
                    c => JsObject("code" -> c.toJson, "binary" -> JsBoolean(b.binary))
                } getOrElse JsObject())
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
                WhiskAction(namespace, name, newExec, parameters, limits, version, publish, annotations)
            case _ => this
        }
    }
    def toJson = WhiskAction.serdes.write(this).asJsObject
}

object WhiskAction
    extends DocumentFactory[WhiskAction]
    with WhiskEntityQueries[WhiskAction]
    with DefaultJsonProtocol {

    val execFieldName = "exec"
    override val collectionName = "actions"
    override implicit val serdes = jsonFormat8(WhiskAction.apply)

    def containerImageName(exec: CodeExec[_], registry: String, prefix: String, tag: String): String = {
        exec match {
            case b @ BlackBoxExec(image, _) =>
                if (b.pull) {
                    image
                } else {
                    localImageName(registry, prefix, image.split("/")(1), tag)
                }

            case e =>
                localImageName(registry, prefix, e.image, tag)
        }
    }

    private def localImageName(registry: String, prefix: String, image: String, tag: String): String = {
        val r = Option(registry).filter(_.nonEmpty).map { reg =>
            if (reg.endsWith("/")) reg else reg + "/"
        }.getOrElse("")
        val p = Option(prefix).filter(_.nonEmpty).map(_ + "/").getOrElse("")
        r + p + image + ":" + tag
    }

    override val cacheEnabled = true
    override def cacheKeyForUpdate(w: WhiskAction) = w.docid.asDocInfo

    private val jarAttachmentName = "jarfile"
    private val jarContentType = ContentType.Binary(MediaTypes.`application/java-archive`)

    // Overriden to store Java `exec` fields as attachments.
    override def put[A >: WhiskAction](db: ArtifactStore[A], doc: WhiskAction)(
        implicit transid: TransactionId): Future[DocInfo] = {

        Try {
            require(db != null, "db undefined")
            require(doc != null, "doc undefined")
        } map { _ =>
            doc.exec match {
                case JavaExec(Inline(jar), main) =>
                    implicit val logger = db: Logging
                    implicit val ec = db.executionContext

                    val newDoc = doc.copy(exec = JavaExec(Attached(jarAttachmentName, jarContentType), main))
                    newDoc.revision(doc.rev)

                    val stream = new ByteArrayInputStream(Base64.getDecoder().decode(jar))

                    for (
                        i1 <- super.put(db, newDoc);
                        i2 <- attach[A](db, i1, "jarfile", jarContentType, stream)
                    ) yield i2

                case _ =>
                    super.put(db, doc)
            }
        } match {
            case Success(f) => f
            case Failure(f) => Future.failed(f)
        }
    }

    // Overriden to retrieve attached Java `exec` fields.
    override def get[A >: WhiskAction](db: ArtifactStore[A], doc: DocId, rev: DocRevision = DocRevision(), fromCache: Boolean)(
        implicit transid: TransactionId, mw: Manifest[WhiskAction]): Future[WhiskAction] = {

        implicit val ec = db.executionContext

        val fa = super.get(db, doc, rev, fromCache = fromCache)

        fa.flatMap { action =>
            action.exec match {
                case JavaExec(Attached(n, t), m) =>
                    val boas = new ByteArrayOutputStream()
                    val b64s = Base64.getEncoder().wrap(boas)

                    getAttachment[A](db, action.docinfo, jarAttachmentName, b64s).map { _ =>
                        b64s.close()
                        val encoded = new String(boas.toByteArray(), StandardCharsets.UTF_8)
                        val newAction = action.copy(exec = JavaExec(Inline(encoded), m))
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
