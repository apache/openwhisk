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

import scala.util.Try
import spray.json.DefaultJsonProtocol._
import spray.json._
import scala.language.postfixOps
import whisk.core.entity.size.SizeInt
import whisk.core.entity.size.SizeString

/**
 * Parameters is a key-value map from parameter names to parameter values. The value of a
 * parameter is opaque, it is treated as string regardless of its actual type.
 *
 * @param key the parameter name, assured to be non-null because it is a value
 * @param value the parameter value, assured to be non-null because it is a value
 */
protected[core] class Parameters protected[entity] (private val params: Map[ParameterName, ParameterValue])
    extends AnyVal {

  /**
   * Calculates the size in Bytes of the Parameters-instance.
   *
   * @return Size of instance as ByteSize
   */
  def size =
    params
      .map {
        case (name, value) =>
          name.size + value.size
      }
      .foldLeft(0 B)(_ + _)

  protected[entity] def +(p: (ParameterName, ParameterValue)) = {
    Option(p) map { p =>
      new Parameters(params + (p._1 -> p._2))
    } getOrElse this
  }

  protected[entity] def +(p: ParameterName, v: ParameterValue) = {
    new Parameters(params + (p -> v))
  }

  /** Add parameters from p to existing map, overwriting existing values in case of overlap in keys. */
  protected[core] def ++(p: Parameters) = new Parameters(params ++ p.params)

  /** Remove parameter by name. */
  protected[core] def -(p: String) = {
    // wrap with try since parameter name may throw an exception for illegal p
    Try(new Parameters(params - new ParameterName(p))) getOrElse this
  }

  /** Gets list all defined parameters. */
  protected[core] def definedParameters: Set[String] = {
    params.keySet filter (params(_).isDefined) map (_.name)
  }

  protected[core] def toJsArray =
    JsArray(params map { p =>
      JsObject("key" -> p._1.name.toJson, "value" -> p._2.value.toJson)
    } toSeq: _*)
  protected[core] def toJsObject =
    JsObject(params map { p =>
      (p._1.name -> p._2.value.toJson)
    })
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
  protected[core] def get(p: String): Option[JsValue] = {
    params.get(new ParameterName(p)).map(_.value)
  }

  /**
   * Retrieves parameter by name if it exist. If value of parameter
   * is a boolean, return its value else false.
   */
  protected[core] def asBool(p: String): Option[Boolean] = {
    get(p) flatMap {
      case JsBoolean(b) => Some(b)
      case _            => None
    }
  }

  /**
   * Retrieves parameter by name if it exist. If value of parameter
   * is a string, return its value else none.
   */
  protected[core] def asString(p: String): Option[String] = {
    get(p) flatMap {
      case JsString(s) => Some(s)
      case _           => None
    }
  }
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

  /**
   * The size of the ParameterName entity as ByteSize.
   */
  def size = name sizeInBytes
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
protected[entity] class ParameterValue protected[entity] (private val v: JsValue) extends AnyVal {

  /** @return JsValue if defined else JsNull. */
  protected[entity] def value = Option(v) getOrElse JsNull

  /** @return true iff value is not JsNull. */
  protected[entity] def isDefined = value != JsNull

  /**
   * The size of the ParameterValue entity as ByteSize.
   */
  def size = value.toString.sizeInBytes
}

protected[core] object Parameters extends ArgNormalizer[Parameters] {

  /** Name of parameter that indicates if action is a feed. */
  protected[core] val Feed = "feed"
  protected[core] val sizeLimit = 1 MB

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
    Parameters() + (new ParameterName(ArgNormalizer.trim(p)),
    new ParameterValue(Option(v) map { _.trim.toJson } getOrElse JsNull))
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
    Parameters() + (new ParameterName(ArgNormalizer.trim(p)),
    new ParameterValue(Option(v) getOrElse JsNull))
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
    def read(value: JsValue) =
      Try {
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
