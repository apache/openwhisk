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

import java.util.Base64

import spray.json.DefaultJsonProtocol
import spray.json.DeserializationException
import spray.json.JsArray
import spray.json.JsObject
import spray.json.JsString
import spray.json.JsValue
import spray.json.RootJsonFormat
import whisk.core.entity.ArgNormalizer.trim

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

    override def toString = Exec.serdes.write(this).compactPrint
}

protected[core] case class NodeJSExec(code: String, init: Option[String]) extends Exec(Exec.NODEJS) {
    val image = "whisk/nodejsaction"
}

protected[core] case class NodeJS6Exec(code: String, init: Option[String]) extends Exec(Exec.NODEJS6) {
    val image = "whisk/nodejs6action"
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

protected[core] case class SequenceExec(code: String, components: Vector[String]) extends Exec(Exec.SEQUENCE) {
    val image = "whisk/nodejsaction"
}

protected[core] object Exec
    extends ArgNormalizer[Exec]
    with DefaultJsonProtocol
    with DefaultRuntimeVersions {

    private lazy val b64decoder = Base64.getDecoder()

    // The possible values of the JSON 'kind' field.
    protected[core] val NODEJS   = "nodejs"
    protected[core] val NODEJS6  = "nodejs:6"
    protected[core] val PYTHON   = "python"
    protected[core] val SWIFT    = "swift"
    protected[core] val SWIFT3   = "swift:3"
    protected[core] val JAVA     = "java"
    protected[core] val BLACKBOX = "blackbox"
    protected[core] val SEQUENCE = "sequence"
    protected[core] val runtimes = Set(NODEJS, NODEJS6, PYTHON, SWIFT, SWIFT3, JAVA, BLACKBOX, SEQUENCE)

    protected[core] def js(code: String, init: String = null): Exec = NodeJSExec(trim(code), Option(init).map(_.trim))
    protected[core] def js6(code: String, init: String = null): Exec = NodeJS6Exec(trim(code), Option(init).map(_.trim))
    protected[core] def bb(image: String): Exec = BlackBoxExec(trim(image))
    protected[core] def swift(code: String): Exec = SwiftExec(trim(code))
    protected[core] def swift3(code: String): Exec = Swift3Exec(trim(code))
    protected[core] def java(jar: String, main: String): Exec = JavaExec(trim(jar), trim(main))
    protected[core] def sequence(components: Vector[String]): Exec = SequenceExec(Pipecode.code, components)

    override protected[core] implicit val serdes = new RootJsonFormat[Exec] {
        override def write(e: Exec) = e match {
            case NodeJSExec(code, None)        => JsObject("kind" -> JsString(Exec.NODEJS), "code" -> JsString(code))
            case NodeJSExec(code, Some(init))  => JsObject("kind" -> JsString(Exec.NODEJS), "code" -> JsString(code), "init" -> JsString(init))
            case SequenceExec(code, comp)      => JsObject("kind" -> JsString(Exec.SEQUENCE), "code" -> JsString(code), "components" -> JsArray(comp map { JsString(_) }))
            case NodeJS6Exec(code, None)       => JsObject("kind" -> JsString(Exec.NODEJS6), "code" -> JsString(code))
            case NodeJS6Exec(code, Some(init)) => JsObject("kind" -> JsString(Exec.NODEJS6), "code" -> JsString(code), "init" -> JsString(init))
            case PythonExec(code)              => JsObject("kind" -> JsString(Exec.PYTHON), "code" -> JsString(code))
            case SwiftExec(code)               => JsObject("kind" -> JsString(Exec.SWIFT), "code" -> JsString(code))
            case Swift3Exec(code)              => JsObject("kind" -> JsString(Exec.SWIFT3), "code" -> JsString(code))
            case JavaExec(jar, main)           => JsObject("kind" -> JsString(Exec.JAVA), "jar" -> JsString(jar), "main" -> JsString(main))
            case BlackBoxExec(image)           => JsObject("kind" -> JsString(Exec.BLACKBOX), "image" -> JsString(image))
        }

        override def read(v: JsValue) = {
            require(v != null)

            val obj = v.asJsObject

            val kindField = obj.getFields("kind") match {
                case Seq(JsString(k)) => k.trim.toLowerCase
                case _                => throw new DeserializationException("'kind' must be a string defined in 'exec'")
            }

            // map "default" virtual runtime versions to the currently blessed actual runtime version
            val kind = resolveDefaultRuntime(kindField)

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
                case Exec.SEQUENCE =>
                    val comp: Vector[String] = obj.getFields("components") match {
                        case Seq(JsArray(components)) =>
                            components map {
                                _ match {
                                    case JsString(s) => s
                                    case _           => throw new DeserializationException(s"'components' must be an array of strings")
                                }
                            }
                        case Seq(_) => throw new DeserializationException(s"'components' must be an array")
                        case _      => throw new DeserializationException(s"'components' must be defined for sequence kind")
                    }
                    SequenceExec(Pipecode.code, comp)

                case Exec.NODEJS6 =>
                    val code: String = obj.getFields("code") match {
                        case Seq(JsString(c)) => c
                        case _                => throw new DeserializationException(s"'code' must be a string defined in 'exec' for '${Exec.NODEJS6}' actions")
                    }
                    val init: Option[String] = obj.getFields("init") match {
                        case Seq(JsString(i)) => Some(i)
                        case Seq(_)           => throw new DeserializationException(s"if defined, 'init' must a string in 'exec' for '${Exec.NODEJS6}' actions")
                        case _                => None
                    }
                    NodeJS6Exec(code, init)

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

                case _ => throw new DeserializationException(s"kind '$kind' not in $runtimes")
            }
        }
    }
}
