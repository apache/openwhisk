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

package org.apache.openwhisk.core.entity

import org.apache.openwhisk.core.entity.size.{SizeInt, SizeString}
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.collection.immutable.ListMap
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

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
  def size = {
    params
      .map { case (name, value) => name.size + value.size }
      .foldLeft(0 B)(_ + _)
  }

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

  /** Add optional parameters from p to existing map, overwriting existing values in case of overlap in keys. */
  protected[core] def ++(p: Option[Parameters]): Parameters = {
    p.map(x => new Parameters(params ++ x.params)).getOrElse(this)
  }

  /** Remove parameter by name. */
  protected[core] def -(p: String): Parameters = {
    // wrap with try since parameter name may throw an exception for illegal p
    Try(new Parameters(params - new ParameterName(p))) getOrElse this
  }

  /** Gets list all defined parameters. */
  protected[core] def definedParameters: Set[String] = {
    params.keySet filter (params(_).isDefined) map (_.name)
  }

  /** Gets list all defined parameters. */
  protected[core] def initParameters: Set[String] = {
    params.keySet filter (params(_).init) map (_.name)
  }

  protected[core] def getMap = {
    params
  }
  protected[core] def toJsArray = {
    JsArray(params map { p =>
      val init = p._2.init match {
        case true => Some("init" -> p._2.init.toJson)
        case _    => None
      }
      val encrypt = p._2.encryption match {
        case (JsNull) => None
        case _        => Some("encryption" -> p._2.encryption)
      }
      // Have do use this slightly strange construction to get the json object order identical.
      JsObject(ListMap() ++ encrypt ++ init ++ Map("key" -> p._1.name.toJson, "value" -> p._2.value.toJson))
    } toSeq: _*)
  }

  protected[core] def toJsObject =
    JsObject(params.map(p => {
      val newValue =
        if (p._2.encryption == JsNull)
          p._2.value.toJson
        else
          JsObject("value" -> p._2.value.toJson, "encryption" -> p._2.encryption, "init" -> p._2.init.toJson)
      (p._1.name, newValue)
    }))

  override def toString = toJsArray.compactPrint

  /**
   * Converts parameters to JSON object and merge keys with payload if defined.
   * In case of overlap, the keys in the payload supersede.
   */
  protected[core] def merge(payload: Option[JsObject]): Some[JsObject] = {
    val args = payload getOrElse JsObject.empty
    Some { (toJsObject.fields ++ args.fields).toJson.asJsObject }
  }

  /** Retrieves parameter by name if it exists. */
  protected[core] def get(p: String): Option[JsValue] = params.get(new ParameterName(p)).map(_.value)

  /** Retrieves parameter by name if it exists. Returns that parameter if it is deserializable to {@code T} */
  protected[core] def getAs[T: JsonReader](p: String): Try[T] =
    get(p)
      .fold[Try[JsValue]](Failure(new IllegalStateException(s"key '$p' does not exist")))(Success.apply)
      .flatMap(js => Try(js.convertTo[T]))

  /**
   *  Retrieves parameter by name if it exist.
   *  @param p the parameter to check for a truthy value
   *  @param valueForNonExistent the value to return for a missing parameter (default false)
   *  @return true if parameter exists and has truthy value, otherwise returns the specified value for non-existent keys
   */
  protected[core] def isTruthy(p: String, valueForNonExistent: Boolean = false): Boolean = {
    get(p) map {
      case JsBoolean(b) => b
      case JsNumber(n)  => n != 0
      case JsString(s)  => s.nonEmpty
      case JsNull       => false
      case _            => true
    } getOrElse valueForNonExistent
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
 * The constructor is private so that argument requirements are checked and normalized
 * before creating a new instance.
 *
 * @param v the value of the parameter, may be null
 * @param init if true, this parameter value is only offered to the action during initialization
 * @param encryptionDetails the name of the encrypter used to store the parameter.
 */
protected[entity] case class ParameterValue protected[entity] (private val v: JsValue,
                                                               val init: Boolean,
                                                               val encryptionDetails: Option[JsString] = None) {

  /** @return JsValue if defined else JsNull. */
  protected[entity] def value = Option(v) getOrElse JsNull

  /** @return true iff value is not JsNull. */
  protected[entity] def isDefined = value != JsNull

  /** @return JsValue if defined else JsNull. */
  protected[entity] def encryption = encryptionDetails getOrElse JsNull

  /**
   * The size of the ParameterValue entity as ByteSize.
   */
  def size = value.toString.sizeInBytes
}

protected[core] object Parameters extends ArgNormalizer[Parameters] {

  /** Name of parameter that indicates if action is a feed. */
  protected[core] val Feed = "feed"
  protected[core] val sizeLimit = 1 MB

  protected[core] def apply(): Parameters = new Parameters(Map.empty)

  /**
   * Creates a parameter tuple from a pair of strings.
   * A convenience method for tests.
   *
   * @param p the parameter name
   * @param v the parameter value
   * @param init the parameter is for initialization
   * @return (ParameterName, ParameterValue)
   * @throws IllegalArgumentException if key is not defined
   */
  @throws[IllegalArgumentException]
  protected[core] def apply(p: String, v: String, init: Boolean = false): Parameters = {
    require(p != null && p.trim.nonEmpty, "key undefined")
    Parameters() + (new ParameterName(ArgNormalizer.trim(p)),
    ParameterValue(Option(v).map(_.trim.toJson).getOrElse(JsNull), init, None))
  }

  /**
   * Creates a parameter tuple from a parameter name and JsValue.
   *
   * @param p the parameter name
   * @param v the parameter value
   * @param init the parameter is for initialization
   * @return (ParameterName, ParameterValue)
   * @throws IllegalArgumentException if key is not defined
   */
  @throws[IllegalArgumentException]
  protected[core] def apply(p: String, v: JsValue, init: Boolean): Parameters = {
    require(p != null && p.trim.nonEmpty, "key undefined")
    Parameters() + (new ParameterName(ArgNormalizer.trim(p)),
    ParameterValue(Option(v).getOrElse(JsNull), init, None))
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
    ParameterValue(Option(v).getOrElse(JsNull), false, None))
  }

  def readMergedList(value: JsValue): Parameters =
    Try {

      val JsObject(obj) = value
      new Parameters(
        obj
          .map((tuple: (String, JsValue)) => {
            val key = new ParameterName(tuple._1)
            val paramVal: ParameterValue = tuple._2 match {
              case o: JsObject =>
                o.getFields("value", "init", "encryption") match {
                  case Seq(v: JsValue, JsBoolean(i), e: JsString) =>
                    ParameterValue(v, i, Some(e))
                  case _ => ParameterValue(o, false, None)
                }
              case v: JsValue => ParameterValue(v, false, None)
            }
            (key, paramVal)
          })
          .toMap)
    } getOrElse deserializationError(
      "parameters malformed, could not get a JsObject from: " + (if (value != null) value.toString() else ""))

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
      } getOrElse {
        Try {
          var converted = new ListMap[ParameterName, ParameterValue]()
          val JsObject(o) = value
          o.foreach(i =>
            i._2.asJsObject.getFields("value", "init", "encryption") match {
              case Seq(v: JsValue, JsBoolean(init), e: JsValue) if e != JsNull =>
                val key = new ParameterName(i._1)
                val value = ParameterValue(v, init, Some(JsString(e.convertTo[String])))
                converted = converted + (key -> value)
              case Seq(v: JsValue, JsBoolean(init), e: JsValue) =>
                val key = new ParameterName(i._1)
                val value = ParameterValue(v, init, None)
                converted = converted + (key -> value)
          })
          if (converted.size == 0) {
            deserializationError("parameters malformed no parameters available: " + value.toString())
          } else {
            new Parameters(converted)
          }
        } getOrElse deserializationError(
          "parameters malformed could not read directly: " + (if (value != null) value.toString() else ""))
      }

    /**
     * Gets parameters as a Parameters instances.
     * The argument should be a [{key,value}].
     *
     * @param parameters the JSON representation of an parameter array
     * @return Parameters instance if parameters conforms to schema
     */
    def read(params: Vector[JsValue]) = Try {
      new Parameters(
        params
          .map(i => {
            i.asJsObject.getFields("key", "value", "init", "encryption") match {
              case Seq(JsString(k), v: JsValue) =>
                val key = new ParameterName(k)
                val value = ParameterValue(v, false)
                (key, value)
              case Seq(JsString(k), v: JsValue, JsBoolean(i), e: JsString) =>
                val key = new ParameterName(k)
                val value = ParameterValue(v, i, Some(e))
                (key, value)
              case Seq(JsString(k), v: JsValue, JsBoolean(i)) =>
                val key = new ParameterName(k)
                val value = ParameterValue(v, i)
                (key, value)
              case Seq(JsString(k), v: JsValue, e: JsString) if (i.asJsObject.fields.contains("encryption")) =>
                val key = new ParameterName(k)
                val value = ParameterValue(v, false, Some(e))
                (key, value)
            }
          })
          .toMap)
    }
  }
}
