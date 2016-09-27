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

import spray.json.DefaultJsonProtocol
import spray.json.DefaultJsonProtocol.StringJsonFormat
import spray.json.JsObject
import spray.json.JsString
import spray.json.pimpAny

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
    annotations: Option[Parameters] = None)

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
     * Gets the container image name for the action.
     * If the action is a black box action, return the image name. Otherwise
     * return a standard image name for running Javascript or Swift actions.
     *
     * @return container image name for action
     */
    def containerImageName(registry: String, prefix: String, tag: String) = WhiskAction.containerImageName(exec, registry, prefix, tag)

    /**
     * Gets initializer for action.
     * If the action is a black box action, return an empty initializer since
     * init on a black box container is not yet supported. Otherwise, return
     * { name, main, code, lib } required to run the action.
     */
    def containerInitializer: JsObject = {
        def getNodeInitializer(code: String, optInit: Option[String]) = {
            val init = JsObject(
                "name" -> name.toJson,
                "main" -> JsString("main"),
                "code" -> JsString(code))
            optInit map {
                lib => JsObject(init.fields + "lib" -> lib.toJson)
            } getOrElse init
        }

        exec match {
            case NodeJSExec(code, optInit)      => getNodeInitializer(code, optInit)
            case NodeJS6Exec(code, optInit)     => getNodeInitializer(code, optInit)
            case SequenceExec(code, components) => getNodeInitializer(code, None)
            case SwiftExec(code) =>
                JsObject(
                    "name" -> name.toJson,
                    "code" -> code.toJson)
            case PythonExec(code) =>
                JsObject(
                    "name" -> name.toJson,
                    "code" -> code.toJson)
            case JavaExec(jar, main) =>
                JsObject(
                    "name" -> name.toJson,
                    "jar" -> jar.toJson,
                    "main" -> main.toJson)
            case Swift3Exec(code) =>
                JsObject(
                    "name" -> name.toJson,
                    "code" -> code.toJson)
            case _: BlackBoxExec =>
                JsObject()
        }
    }

    def toJson = WhiskAction.serdes.write(this).asJsObject
}

object WhiskAction
    extends DocumentFactory[WhiskAction]
    with WhiskEntityQueries[WhiskAction]
    with DefaultJsonProtocol {

    override val collectionName = "actions"
    override implicit val serdes = jsonFormat8(WhiskAction.apply)

    def containerImageName(exec: Exec, registry: String, prefix: String, tag: String): String = {
        exec match {
            case BlackBoxExec(image) => image
            case _ =>
                val r = Option(registry).filter(_.nonEmpty).map { reg =>
                    if (reg.endsWith("/")) reg else reg + "/"
                }.getOrElse("")
                val p = Option(prefix).filter(_.nonEmpty).map(_ + "/").getOrElse("")
                r + p + exec.image + ":" + tag
        }
    }

    override val cacheEnabled = true
    override def cacheKeys(w: WhiskAction) = Set(w.docid.asDocInfo, w.docinfo)

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
     *  utility function that given a fully qualified name for an action, resolve its possible package bindings and returns
     *  the fully qualified name of the resolved action
     */
    def resolveAction(entityStore: EntityStore, fullyQualifiedName: FullyQualifiedEntityName)(
        implicit ec: ExecutionContext, transid: TransactionId): Future[FullyQualifiedEntityName] = {
        // first check that there is a package to be resolved
        val entityPath = fullyQualifiedName.path
        if (entityPath.defaultPackage) {
            // this is the default package, nothing to resolve
            Future.successful(fullyQualifiedName)
        } else {
            // there is a package to be resolved
            val pkgDocid = fullyQualifiedName.pathToDocId
            val actionName = fullyQualifiedName.name
            val wp = WhiskPackage.resolveBinding(entityStore, pkgDocid)
            wp map { resolvedPkg => FullyQualifiedEntityName(resolvedPkg.namespace.addpath(resolvedPkg.name), actionName) }
        }
    }
}

object ActionLimitsOption extends DefaultJsonProtocol {
    implicit val serdes = jsonFormat3(ActionLimitsOption.apply)
}

object WhiskActionPut extends DefaultJsonProtocol {
    implicit val serdes = jsonFormat6(WhiskActionPut.apply)
}
