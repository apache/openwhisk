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

package org.apache.openwhisk.common

import scala.util.Try

/**
 * A set of properties which define a configuration.
 *
 * This class tries to populate the properties in the following order.  Each step may overwrite
 * properties defined earlier.
 *
 * <ol>
 * <li> first it uses default values specified in the requiredProperties map if any.
 * <li> first it looks in the system environment.  If the system environment defines a value for FOO_BAR, this
 * will be used as the value for properties foo.bar or FoO.BaR. By convention, we usually define variables as
 * all lowercase (foo.bar)
 * <li> next it reads the properties from the propertiesFile.   In this case it looks for an exact match for "foo.bar" in the
 * file
 * </ol>
 *
 * After loading the properties, this validates that all required properties are defined.
 *
 * @param requiredProperties a Map whose keys define properties that must be bound to
 * a value, and whose values are default values. A null value in the Map means there is
 * no default value specified.
 * @param optionalProperties a Set of optional properties which may or not be defined.
 * @param env an optional environment to read from (defaults to sys.env).
 */
class Config(requiredProperties: Map[String, String], optionalProperties: Set[String] = Set.empty)(
  env: Map[String, String] = sys.env)(implicit logging: Logging) {

  private val settings = getProperties().toMap.filter {
    case (k, v) =>
      requiredProperties.contains(k) ||
        (optionalProperties.contains(k) && v != null)
  }

  lazy val isValid: Boolean = Config.validateProperties(requiredProperties, settings)

  /**
   * Gets value for key if it exists else the empty string.
   * The value of the override key will instead be returned if its value is present in the map.
   *
   * @param key to lookup
   * @param overrideKey the property whose value will be returned if the map contains the override key.
   * @return value for the key or the empty string if the key does not have a value/does not exist
   */
  def apply(key: String, overrideKey: String = ""): String = {
    Try(settings(overrideKey)).orElse(Try(settings(key))).getOrElse("")
  }

  /**
   * Returns the value of a given key.
   *
   * @param key the property that has to be returned.
   */
  def getProperty(key: String): String = {
    this(key)
  }

  /**
   * Returns the value of a given key parsed as a double.
   * If parsing fails, return the default value.
   *
   * @param key the property that has to be returned.
   */
  def getAsDouble(key: String, defaultValue: Double): Double = {
    Try { getProperty(key).toDouble } getOrElse { defaultValue }
  }

  /**
   * Returns the value of a given key parsed as an integer.
   * If parsing fails, return the default value.
   *
   * @param key the property that has to be returned.
   */
  def getAsInt(key: String, defaultValue: Int): Int = {
    Try { getProperty(key).toInt } getOrElse { defaultValue }
  }

  /**
   * Returns the value of a given key parsed as a boolean.
   * If parsing fails, return the default value.
   *
   * @param key the property that has to be returned.
   */
  def getAsBoolean(key: String, defaultValue: Boolean): Boolean = {
    Try(getProperty(key).toBoolean).getOrElse(defaultValue)
  }

  /**
   * Converts the set of property to a string for debugging.
   */
  def mkString: String = settings.mkString("\n")

  /**
   * Loads the properties from the environment into a mutable map.
   *
   * @return a pair which is the Map defining the properties, and a boolean indicating whether validation succeeded.
   */
  protected def getProperties(): scala.collection.mutable.Map[String, String] = {
    val required = scala.collection.mutable.Map[String, String]() ++= requiredProperties
    Config.readPropertiesFromSystemAndEnv(required, env)

    // for optional value, assign them a default from the required properties list
    // to prevent loss of a default value on a required property that may not otherwise be defined
    val optional = scala.collection.mutable.Map[String, String]() ++= optionalProperties.map { k =>
      k -> required.getOrElse(k, null)
    }
    Config.readPropertiesFromSystemAndEnv(optional, env)

    required ++ optional
  }
}

/**
 * Singleton object which provides global methods to manage configuration.
 */
object Config {
  val prefix = "whisk-config."

  /**
   * Reads a Map of key-value pairs from the environment -- store them in the
   * mutable properties object.
   */
  def readPropertiesFromSystemAndEnv(properties: scala.collection.mutable.Map[String, String],
                                     env: Map[String, String])(implicit logging: Logging) = {
    readPropertiesFromSystem(properties)
    for (p <- properties.keys) {
      val envp = p.replace('.', '_').toUpperCase
      val envv = env.get(envp)
      if (envv.isDefined) {
        logging.info(this, s"environment set value for $p")
        properties += p -> envv.get.trim
      }
    }
  }

  def readPropertiesFromSystem(properties: scala.collection.mutable.Map[String, String])(implicit logging: Logging) = {
    for (p <- properties.keys) {
      val sysv = Option(System.getProperty(prefix + p))
      if (sysv.isDefined) {
        logging.info(this, s"system set value for $p")
        properties += p -> sysv.get.trim
      }
    }
  }

  /**
   * Checks that the properties object defines all the required properties.
   *
   * @param required a key-value map where the keys are required properties
   * @param properties a set of properties to check
   */
  def validateProperties(required: Map[String, String], properties: Map[String, String])(
    implicit logging: Logging): Boolean = {
    required.keys.forall { key =>
      val value = properties(key)
      if (value == null) logging.error(this, s"required property $key still not set")
      value != null
    }
  }
}
