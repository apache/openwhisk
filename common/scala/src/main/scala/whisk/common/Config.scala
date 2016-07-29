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

package whisk.common

import java.io.File

import scala.collection.Map
import scala.io.Source

import spray.json.JsArray
import spray.json.JsBoolean
import spray.json.JsNumber
import spray.json.JsObject
import spray.json.JsString

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
 * a value, and whose values are default values.   A null value in the Map means there is
 * no default value specified, so it must appear in the properties file
 *
 * @param propertiesFile a file which defines the properties
 */
class Config(requiredProperties: Map[String, String]) {

    def isValid: Boolean = valid

    /**
     * Gets value for key if it exists else the empty string.
     *
     * @param key to lookup
     * @return value for the key or the empty string if the key does not have a value/does not exist
     */
    def apply(key: String): String = try {
        settings(key)
    } catch {
        case _: Throwable => ""
    }

    /**
     * Returns the value of a given key.
     *
     * @param key the property that has to be returned.
     */
    def getProperty(key: String): String = {
        this(key)
    }

    /*
     * Converts the set of property to a string for debugging.
     */
    def mkString(): String = settings.mkString("\n")

    /**
     * Loads the properties as specified above.
     *
     * @return a pair which is the Map defining the properties, and a boolean indicating whether validation succeeded.
     */
    protected def getProperties: (Map[String, String], Boolean) = {
        val properties = scala.collection.mutable.Map[String, String]() ++= requiredProperties
        Config.readPropertiesFromEnvironment(properties)
        (properties.toMap, Config.validateProperties(requiredProperties, properties))
    }

    private val (settings, valid) = getProperties
}

/**
 * Singleton object which provides global methods to manage configuration.
 */
object Config extends Logging {
    /**
     * Reads a Map of key-value pairs from the environment (sys.env) -- store them in the
     * mutable properties object.
     */
    def readPropertiesFromEnvironment(properties: Settings) = {
        for (p <- properties.keys) {
            val envp = p.replace('.', '_').toUpperCase
            val envv = sys.env.get(envp)
            if (envv.isDefined) {
                info(this, s"environment set value for $p")
                properties += p -> envv.get
            }
        }
    }

    /**
     * Checks that the properties object defines all the required properties.
     *
     * @param required a key-value map where the keys are required properties
     * @param properties a set of properties to check
     */
    def validateProperties(required: Map[String, String], properties: Settings): Boolean = {
        required.keys.forall { key =>
            val value = properties(key)
            if (value == null) error(this, s"required property $key still not set")
            value != null
        }
    }

    type Settings = scala.collection.mutable.Map[String, String]
}
