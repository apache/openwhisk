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

import scala.util.Failure
import scala.util.Success
import scala.util.Try

import spray.json.pimpString
import spray.json.DefaultJsonProtocol
import spray.json.DefaultJsonProtocol.StringJsonFormat
import spray.json.JsArray
import spray.json.JsNull
import spray.json.JsObject
import spray.json.JsString
import spray.json.JsValue
import spray.json.RootJsonFormat
import spray.json.deserializationError
import spray.json.pimpAny

import whisk.core.database.DocumentFactory

/**
 * ActionLimitsOption mirrors ActionLimits but makes both the timeout and memory
 * limit optional so that it is convenient to override just one limit at a time.
 */
case class ActionLimitsOption(timeout: Option[TimeLimit], memory: Option[MemoryLimit])

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
    namespace: Namespace,
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
    def containerImageName(registry: String = null, tag: String = "latest") = WhiskAction.containerImageName(exec, registry, tag)

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

    def containerImageName(exec: Exec, registry: String = null, tag: String = "latest"): String = {
        exec match {
            case BlackBoxExec(image) => image
            case _ =>
                Option(registry).filter { _.nonEmpty }.map { r =>
                    val prefix = if (r.endsWith("/")) r else s"$r/"
                    s"${prefix}${exec.image}:${tag}"
                } getOrElse exec.image
        }
    }

    override val cacheEnabled = true
    override def cacheKeys(w: WhiskAction) = Set(w.docid.asDocInfo, w.docinfo)
}

object ActionLimitsOption extends DefaultJsonProtocol {
    implicit val serdes = jsonFormat2(ActionLimitsOption.apply)
}

object WhiskActionPut extends DefaultJsonProtocol {
    implicit val serdes = jsonFormat6(WhiskActionPut.apply)
}
