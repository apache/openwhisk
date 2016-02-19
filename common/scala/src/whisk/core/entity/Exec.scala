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

import scala.collection.JavaConversions.asScalaSet
import spray.json.DefaultJsonProtocol
import spray.json.RootJsonFormat
import spray.json.{ JsValue, JsObject, JsString }
import spray.json.DeserializationException
import whisk.core.entity.ArgNormalizer.trim
import spray.json.pimpString
import com.google.gson.JsonObject
import com.google.gson.JsonNull
import com.google.gson.JsonPrimitive
import scala.util.Try

/**
 * Exec encodes the executable details of an action. For black
 * box container, an image name is required. For Javascript actions,
 * the code to execute is required. Optionally, Javascript actions
 * also permit a library to support the code in the form of a zip file.
 * For Swift actions, the source code to execute the action is required.
 *
 * exec: { kind  : one of "nodejs", "blackbox", "swift",
 *         code  : code to execute if kind is "nodejs" or "swift",
 *         init  : optional zipfile reference when kind is "nodejs",
 *         image : container name when kind is "blackbox" }
 */
sealed abstract class Exec(val kind: String) {
    def image: String

    def toGson = {
        val gson = new JsonObject()
        gson.add("kind", new JsonPrimitive(kind))

        this match {
            case NodeJSExec(code, init) =>
                gson.add("code", new JsonPrimitive(code))
                gson.add("init", init map { new JsonPrimitive(_) } getOrElse new JsonNull())

            case BlackBoxExec(image) =>
                gson.add("image", new JsonPrimitive(image))

            case SwiftExec(code) =>
                gson.add("code", new JsonPrimitive(code))
        }

        gson
    }

    override def toString = Exec.serdes.write(this).compactPrint
}

protected[core] case class NodeJSExec(code: String, init: Option[String]) extends Exec(Exec.NODEJS) {
    val image = "whisk/nodejsaction"
}

protected[core] case class BlackBoxExec(image: String) extends Exec(Exec.BLACKBOX)

protected[core] case class SwiftExec(code: String) extends Exec(Exec.SWIFT) {
    val image = "whisk/swiftaction"
}

protected[core] object Exec
    extends ArgNormalizer[Exec]
    with DefaultJsonProtocol {

    // The possible values of the JSON 'kind' field.
    protected[core] val NODEJS = "nodejs"
    protected[core] val BLACKBOX = "blackbox"
    protected[core] val SWIFT = "swift"

    protected[core] def js(code: String, init: String = null): Exec = NodeJSExec(trim(code), Option(init).map(_.trim))
    protected[core] def bb(image: String): Exec = BlackBoxExec(trim(image))
    protected[core] def swift(code: String): Exec = SwiftExec(trim(code))

    override protected[core] implicit val serdes = new RootJsonFormat[Exec] {
        override def write(e: Exec) = e match {
            case NodeJSExec(code, None)       => JsObject("kind" -> JsString(Exec.NODEJS), "code" -> JsString(code))
            case NodeJSExec(code, Some(init)) => JsObject("kind" -> JsString(Exec.NODEJS), "code" -> JsString(code), "init" -> JsString(init))
            case BlackBoxExec(image)          => JsObject("kind" -> JsString(Exec.BLACKBOX), "image" -> JsString(image))
            case SwiftExec(code)              => JsObject("kind" -> JsString(Exec.SWIFT), "code" -> JsString(code))
        }

        override def read(v: JsValue) = {
            require(v != null)

            val obj = v.asJsObject

            val kind = obj.getFields("kind") match {
                case Seq(JsString(k)) => k
                case _                => throw new DeserializationException("'kind' must be a string defined in 'exec'")
            }

            kind match {
                case Exec.NODEJS =>
                    val code: String = obj.getFields("code") match {
                        case Seq(JsString(c)) => c
                        case _                => throw new DeserializationException(s"'code' must be a string defined in 'exec' for '${Exec.NODEJS}' actions")
                    }
                    val init: Option[String] = obj.getFields("init") match {
                        case Seq(JsString(i)) => Some(i)
                        case Seq(_)           => throw new DeserializationException(s"if defined, 'init' must a string in 'exec' for '${Exec.NODEJS}' actions")
                        case _                => None
                    }
                    NodeJSExec(code, init)

                case "blackbox" =>
                    val image: String = obj.getFields("image") match {
                        case Seq(JsString(i)) => i
                        case _                => throw new DeserializationException(s"'image' must be a string defined in 'exec' for '${Exec.BLACKBOX}' actions")
                    }
                    BlackBoxExec(image)

                case "swift" =>
                    val code: String = obj.getFields("code") match {
                        case Seq(JsString(c)) => c
                        case _                => throw new DeserializationException(s"'code' must be a string defined in 'exec' for '${Exec.SWIFT}' actions")
                    }
                    SwiftExec(code)

                case _ => throw new DeserializationException(s"'kind' must be one of {${Exec.NODEJS},${Exec.BLACKBOX},${Exec.SWIFT}")
            }
        }
    }

    @throws[IllegalArgumentException]
    protected[entity] def apply(gson: JsonObject): Exec = {
        val convert = Try { whisk.utils.JsonUtils.gsonToSprayJson(gson) }
        require(convert.isSuccess, "exec malformed")
        serdes.read(convert.get)
    }
}
