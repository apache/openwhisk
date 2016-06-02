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

import scala.util.Try
import spray.json.DefaultJsonProtocol.StringJsonFormat
import spray.json.JsArray
import spray.json.JsNull
import spray.json.JsObject
import spray.json.JsString
import spray.json.JsValue
import spray.json.DefaultJsonProtocol.JsValueFormat
import spray.json.DefaultJsonProtocol.mapFormat
import spray.json.RootJsonFormat
import spray.json.deserializationError
import spray.json.pimpAny
import scala.language.postfixOps
import scala.language.reflectiveCalls

/**
 * Parameters is a key-value map from parameter names to parameter values. The value of a
 * parameter is opaque, it is treated as string regardless of its actual type.
 *
 * @param key the parameter name, assured to be non-null because it is a value
 * @param value the parameter value, assured to be non-null because it is a value
 */
protected[core] class Parameters protected[entity] (
    private val params: Map[ParameterName, ParameterValue])
    extends AnyVal {

    protected[entity] def ++(p: (ParameterName, ParameterValue)) = {
        Option(p) map { p => new Parameters(params + (p._1 -> p._2)) } getOrElse this
    }

    protected[entity] def ++(p: ParameterName, v: ParameterValue) = {
        new Parameters(params + (p -> v))
    }

    /** Add parameters from p to existing map, overwriting existing values in case of overlap in keys. */
    protected[core] def ++(p: Parameters) = new Parameters(params ++ p.params)

    /** Remove parameter by name. */
    protected[core] def --(p: String) = {
        Try { new ParameterName(p) } map {
            param => new Parameters(params - param)
        } getOrElse this
    }

    protected[core] def toJsArray = JsArray(params map { p => JsObject("key" -> p._1().toJson, "value" -> p._2().toJson) } toSeq: _*)
    protected[core] def toJsObject = JsObject(params map { p => (p._1() -> p._2().toJson) })
    override def toString = toJsArray.compactPrint

    /**
     * Converts parameters to JSON object and merge keys with payload if defined.
     * In case of overlap, the keys in the payload supersede.
     */
    protected[core] def merge(payload: Option[JsObject]): Some[JsObject] = {
        val args = payload getOrElse JsObject()
        Some { (toJsObject.fields ++ args.fields).toJson.asJsObject }
    }

    /**
     * Retrieves parameter by name if it exists.
     */
    protected[core] def apply(p: String) = Try { params(new ParameterName(p)) } map { _() } toOption
}

/**
 * A ParameterName is a parameter name for an action or trigger to bind to its environment.
 * It wraps a normalized string as a value type.
 *
 * It is a value type (hence == is .equals, immutable and cannot be assigned null).
 * The constructor is private so that argument requirements are checked and normalized
 * before creating a new instance.
 *
 * @param name the name of the parameter (its key)
 */
protected[entity] class ParameterName protected[entity] (val name: String) extends AnyVal {
    protected[entity] def apply() = name
}

/**
 * A ParameterValue is a parameter value for an action or trigger to bind to its environment.
 * It wraps a normalized string as a value type. The string may be a JSON string. It may also
 * be undefined, such as when an action is created but the parameter value is not bound yet.
 * In general, this is an opaque value.
 *
 * It is a value type (hence == is .equals, immutable and cannot be assigned null).
 * The constructor is private so that argument requirements are checked and normalized
 * before creating a new instance.
 *
 * @param value the value of the parameter, may be null
 */
protected[entity] class ParameterValue protected[entity] (private val value: JsValue) extends AnyVal {
    protected[core] def apply() = Option(value) getOrElse JsNull
}

protected[core] object Parameters extends ArgNormalizer[Parameters] {

    /** Name of parameter that indicates if action is a feed. */
    protected[core] val Feed = "feed"

    protected[core] def apply(): Parameters = new Parameters(Map())

    /**
     * Creates a parameter tuple from a pair of strings.
     * A convenience method for tests.
     *
     * @param p the parameter name
     * @param v the parameter value
     * @return (ParameterName, ParameterValue)
     * @throws IllegalArgumentException if key is not defined
     */
    @throws[IllegalArgumentException]
    protected[core] def apply(p: String, v: String): Parameters = {
        require(p != null && p.trim.nonEmpty, "key undefined")
        Parameters() ++ {
            (new ParameterName(ArgNormalizer.trim(p)),
                new ParameterValue(Option(v) map { _.trim.toJson } getOrElse JsNull))
        }
    }

    /**
     * Creates a parameter tuple from a parameter name and JsValue.
     *
     * @param p the parameter name
     * @param v the parameter value
     * @return (ParameterName, ParameterValue)
     * @throws IllegalArgumentException if key is not defined
     */
    @throws[IllegalArgumentException]
    protected[core] def apply(p: String, v: JsValue): Parameters = {
        require(p != null && p.trim.nonEmpty, "key undefined")
        Parameters() ++ {
            (new ParameterName(ArgNormalizer.trim(p)),
                new ParameterValue(Option(v) getOrElse JsNull))
        }
    }

    override protected[core] implicit val serdes = new RootJsonFormat[Parameters] {
        def write(p: Parameters) = p.toJsArray

        /**
         * Gets parameters as a Parameters instances. The argument should be a JArray
         * [{key,value}], otherwise an IllegalParameter is thrown.
         *
         * @param parameters the JSON representation of an parameter array
         * @return Parameters instance if parameters conforms to schema
         */
        def read(value: JsValue) = Try {
            val JsArray(params) = value
            params
        } flatMap {
            read(_)
        } getOrElse deserializationError("parameters malformed!")

        /**
         * Gets parameters as a Parameters instances.
         * The argument should be a [{key,value}].
         *
         * @param parameters the JSON representation of an parameter array
         * @return Parameters instance if parameters conforms to schema
         */
        def read(params: Vector[JsValue]) = Try {
            new Parameters(params map {
                _.asJsObject.getFields("key", "value") match {
                    case Seq(JsString(k), v: JsValue) =>
                        val key = new ParameterName(k)
                        val value = new ParameterValue(v)
                        (key, value)
                }
            } toMap)
        }
    }
}
