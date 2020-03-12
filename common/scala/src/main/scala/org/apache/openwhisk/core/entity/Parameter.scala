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

import scala.util.{Failure, Success, Try}
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.language.postfixOps
import org.apache.openwhisk.core.entity.size.SizeInt
import org.apache.openwhisk.core.entity.size.SizeString

/**
 * Parameters is a key-value map from parameter names to parameter values. The value of a
 * parameter is opaque, it is treated as string regardless of its actual type.
 *
 * @param key the parameter name, assured to be non-null because it is a value
 * @param value the parameter value, assured to be non-null because it is a value
 */
protected[core] class Parameters protected[entity] (protected[entity] val params: Map[ParameterName, ParameterValue])
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

  /** Gets set of all defined parameters. */
  protected[core] def definedParameters: Set[String] = {
    params.keySet filter (params(_).isDefined) map (_.name)
  }

  /** Gets set of all defined parameters. */
  protected[core] def initParameters: Set[String] = {
    params.keySet filter (params(_).init) map (_.name)
  }

  /**
   * Gets map of all locked (encrypted) parameters, excluding parameters from given set.
   */
  protected[core] def lockedParameters(exclude: Set[String] = Set.empty): Map[String, String] = {
    params.collect {
      case p if p._2.encryption.isDefined && !exclude.contains(p._1.name) => (p._1.name -> p._2.encryption.get)
    }
  }

  protected[core] def toJsArray = {
    JsArray(params map { p =>
      val init = if (p._2.init) Some("init" -> JsTrue) else None
      val encrypt = p._2.encryption.map(e => ("encryption" -> JsString(e)))

      JsObject(Map("key" -> p._1.name.toJson, "value" -> p._2.value) ++ init ++ encrypt)
    } toSeq: _*)
  }

  protected[core] def toJsObject = JsObject(params.map(p => (p._1.name -> p._2.value.toJson)))

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

  /**
   * Encrypts any parameters that are not yet encoded.
   *
   * @param encoder the encoder to transform parameter values with
   * @return parameters with all values encrypted
   */
  def lock(encoder: Option[Encrypter] = None): Parameters = {
    encoder
      .map { coder =>
        new Parameters(params.map {
          case (paramName, paramValue) if paramValue.encryption.isEmpty =>
            paramName -> coder.encrypt(paramValue)
          case p => p
        })
      }
      .getOrElse(this)
  }

  /**
   * Decodes parameters. If the encryption scheme for a parameter is not recognized, it is not modified.
   *
   * @param decoder the decoder to use to transform locked values
   * @return parameters will all values decoded (where scheme is known)
   */
  def unlock(decoder: ParameterEncryption): Parameters = {
    new Parameters(params.map {
      case p @ (paramName, paramValue) =>
        paramValue.encryption
          .map(paramName -> decoder.encryptor(_).decrypt(paramValue))
          .getOrElse(p)
    })
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
 * @param encryption the name of the encryption algorithm used to store the parameter or none (plain text)
 */
protected[entity] case class ParameterValue protected[entity] (private val v: JsValue,
                                                               val init: Boolean,
                                                               val encryption: Option[String] = None) {

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

  protected[core] def apply(): Parameters = new Parameters(Map.empty)

  /**
   * Creates a parameter tuple from a pair of strings.
   * A convenience method for tests.
   *
   * @param p    the parameter name
   * @param v    the parameter value
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
   * @param p    the parameter name
   * @param v    the parameter value
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

  override protected[core] implicit val serdes = new RootJsonFormat[Parameters] {
    def write(p: Parameters) = p.toJsArray

    /**
     * Gets parameters as a Parameters instances. The argument should be a JArray
     * [{key,value}], otherwise an IllegalParameter is thrown.
     *
     * @param parameters the JSON representation of an parameter array
     * @return Parameters instance if parameters conforms to schema
     */
    def read(value: JsValue): Parameters = {
      value match {
        case JsArray(params) => read(params).getOrElse(deserializationError("parameters malformed!"))
        case _               => deserializationError("parameters malformed!")
      }
    }

    /**
     * Gets parameters as a Parameters instances.
     * The argument should be a [{key,value}].
     *
     * @param parameters the JSON representation of an parameter array
     * @return Parameters instance if parameters conforms to schema
     */
    def read(params: Vector[JsValue]) = Try {
      new Parameters(params.map {
        case o @ JsObject(fields) =>
          o.getFields("key", "value", "init", "encryption") match {
            case Seq(JsString(k), v: JsValue) if fields.contains("value") =>
              val key = new ParameterName(k)
              val value = ParameterValue(v, false)
              (key, value)
            case Seq(JsString(k), v: JsValue, JsBoolean(i)) =>
              val key = new ParameterName(k)
              val value = ParameterValue(v, i)
              (key, value)
            case Seq(JsString(k), v: JsValue, JsBoolean(i), JsString(e)) =>
              val key = new ParameterName(k)
              val value = ParameterValue(v, i, Some(e))
              (key, value)
            case Seq(JsString(k), v: JsValue, JsBoolean(i), JsNull) =>
              val key = new ParameterName(k)
              val value = ParameterValue(v, i, None)
              (key, value)
            case Seq(JsString(k), v: JsValue, JsString(e))
                if fields.contains("value") && fields.contains("encryption") =>
              val key = new ParameterName(k)
              val value = ParameterValue(v, false, Some(e))
              (key, value)
          }
        case _ => deserializationError("invalid parameter")
      }.toMap)
    }
  }
}
