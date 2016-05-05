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
import spray.json.DeserializationException

import java.util.Base64

/**
 * Exec encodes the executable details of an action. For black
 * box container, an image name is required. For Javascript and Python
 * actions, the code to execute is required. Optionally, Javascript actions
 * also permit a library to support the code in the form of a zip file.
 * For Swift actions, the source code to execute the action is required.
 * For Java actions, a base64-encoded string representing a jar file is
 * required, as well as the name of the entrypoint class.
 *
 * exec: { kind  : one of supported language runtimes
 *         code  : code to execute if kind is supported
 *         init  : optional zipfile reference when kind is "nodejs",
 *         image : container name when kind is "blackbox",
 *         jar   : a base64-encoded JAR file when kind is "java",
 *         name  : a fully-qualified class name when kind is "java" }
 */
sealed abstract class Exec(val kind: String) {
    def image: String

    def toGson = {
        val gson = new JsonObject()
        gson.add("kind", new JsonPrimitive(kind))

        this match {
            case NodeJSExec(code, init) =>
                gson.add("code", new JsonPrimitive(code))
                gson.add("init", init map { new JsonPrimitive(_) } getOrElse JsonNull.INSTANCE)

            case PythonExec(code) =>
                gson.add("code", new JsonPrimitive(code))

            case SwiftExec(code) =>
                gson.add("code", new JsonPrimitive(code))

            case Swift3Exec(code) =>
                gson.add("code", new JsonPrimitive(code))

            case JavaExec(jar, main) =>
                gson.add("jar", new JsonPrimitive(jar))
                gson.add("main", new JsonPrimitive(main))

            case BlackBoxExec(image) =>
                gson.add("image", new JsonPrimitive(image))
        }

        gson
    }

    override def toString = Exec.serdes.write(this).compactPrint
}

protected[core] case class NodeJSExec(code: String, init: Option[String]) extends Exec(Exec.NODEJS) {
    val image = "whisk/nodejsaction"
}

protected[core] case class PythonExec(code: String) extends Exec(Exec.PYTHON) {
    val image = "whisk/pythonaction"
}

protected[core] case class SwiftExec(code: String) extends Exec(Exec.SWIFT) {
    val image = "whisk/swiftaction"
}

protected[core] case class Swift3Exec(code: String) extends Exec(Exec.SWIFT3) {
    val image = "whisk/swift3action"
}

protected[core] case class JavaExec(jar: String, main: String) extends Exec(Exec.JAVA) {
    val image = "whisk/javaaction"
}

protected[core] case class BlackBoxExec(image: String) extends Exec(Exec.BLACKBOX)

protected[core] object Exec
    extends ArgNormalizer[Exec]
    with DefaultJsonProtocol {

    private lazy val b64decoder = Base64.getDecoder()

    // The possible values of the JSON 'kind' field.
    protected[core] val NODEJS   = "nodejs"
    protected[core] val PYTHON   = "python"
    protected[core] val SWIFT    = "swift"
    protected[core] val SWIFT3   = "swift:3"
    protected[core] val JAVA     = "java"
    protected[core] val BLACKBOX = "blackbox"

    protected[core] def js(code: String, init: String = null): Exec = NodeJSExec(trim(code), Option(init).map(_.trim))
    protected[core] def bb(image: String): Exec = BlackBoxExec(trim(image))
    protected[core] def swift(code: String): Exec = SwiftExec(trim(code))
    protected[core] def swift3(code: String): Exec = Swift3Exec(trim(code))
    protected[core] def java(jar: String, main: String): Exec = JavaExec(trim(jar), trim(main))

    override protected[core] implicit val serdes = new RootJsonFormat[Exec] {
        override def write(e: Exec) = e match {
            case NodeJSExec(code, None)       => JsObject("kind" -> JsString(Exec.NODEJS), "code" -> JsString(code))
            case NodeJSExec(code, Some(init)) => JsObject("kind" -> JsString(Exec.NODEJS), "code" -> JsString(code), "init" -> JsString(init))
            case PythonExec(code)             => JsObject("kind" -> JsString(Exec.PYTHON), "code" -> JsString(code))
            case SwiftExec(code)              => JsObject("kind" -> JsString(Exec.SWIFT), "code" -> JsString(code))
            case Swift3Exec(code)             => JsObject("kind" -> JsString(Exec.SWIFT3), "code" -> JsString(code))
            case JavaExec(jar, main)          => JsObject("kind" -> JsString(Exec.JAVA), "jar" -> JsString(jar), "main" -> JsString(main))
            case BlackBoxExec(image)          => JsObject("kind" -> JsString(Exec.BLACKBOX), "image" -> JsString(image))
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

                case Exec.PYTHON =>
                    val code: String = obj.getFields("code") match {
                        case Seq(JsString(c)) => c
                        case _                => throw new DeserializationException(s"'code' must be a string defined in 'exec' for '${Exec.PYTHON}' actions")
                    }
                    PythonExec(code)

                case Exec.SWIFT =>
                    val code: String = obj.getFields("code") match {
                        case Seq(JsString(c)) => c
                        case _                => throw new DeserializationException(s"'code' must be a string defined in 'exec' for '${Exec.SWIFT}' actions")
                    }
                    SwiftExec(code)

                case Exec.SWIFT3 =>
                    val code: String = obj.getFields("code") match {
                        case Seq(JsString(c)) => c
                        case _                => throw new DeserializationException(s"'code' must be a string defined in 'exec' for '${Exec.SWIFT3}' actions")
                    }
                    Swift3Exec(code)

                case Exec.JAVA =>
                    val jar: String = obj.getFields("jar") match {
                        case Seq(JsString(j)) => j //if Try(b64decoder.decode(j)).isSuccess => j
                        case _                => throw new DeserializationException(s"'jar' must be a valid base64 string in 'exec' for '${Exec.JAVA}' actions")
                    }
                    val main: String = obj.getFields("main") match {
                        case Seq(JsString(m)) => m
                        case _                => throw new DeserializationException(s"'main' must be a string defined in 'exec' for '${Exec.JAVA}' actions")
                    }
                    JavaExec(jar, main)

                case Exec.BLACKBOX =>
                    val image: String = obj.getFields("image") match {
                        case Seq(JsString(i)) => i
                        case _                => throw new DeserializationException(s"'image' must be a string defined in 'exec' for '${Exec.BLACKBOX}' actions")
                    }
                    BlackBoxExec(image)

                case _ => throw new DeserializationException(s"kind '$kind' not one of {${Exec.NODEJS}, ${Exec.PYTHON}, ${Exec.SWIFT}, ${Exec.SWIFT3}, ${Exec.JAVA}, ${Exec.BLACKBOX}}")
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
