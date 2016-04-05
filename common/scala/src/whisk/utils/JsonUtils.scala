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

package whisk.utils

import scala.collection.JavaConversions.asScalaSet
import spray.json.JsObject
import com.google.gson.JsonObject
import spray.json.JsNull
import spray.json.JsString
import spray.json.JsNumber
import spray.json.JsBoolean
import spray.json.pimpString
import scala.util.Try
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import spray.json.JsValue
import spray.json.JsArray
import com.google.gson.JsonPrimitive
import com.google.gson.JsonNull

/**
 * Utility functions for manipulating Json objects
 */
object JsonUtils {

    def gsonToSprayJson(r: JsonObject): JsObject = {
        val entries = r.entrySet map {
            entry => (entry.getKey, gsonToSprayJson(entry.getValue))
        }
        JsObject(entries toMap)
    }

    def gsonToSprayJson(r: JsonArray): JsArray = {
        val entries = (0 to r.size - 1) map {
            i => gsonToSprayJson(r.get(i))
        }
        JsArray(entries : _*)
    }

    def gsonToSprayJson(e: JsonElement): JsValue = {
        if (e.isJsonObject) {
            gsonToSprayJson(e.getAsJsonObject)
        } else if (e.isJsonArray) {
            gsonToSprayJson(e.getAsJsonArray)
        } else if (e.isJsonPrimitive) {
            val primitive = e.getAsJsonPrimitive
            if (primitive.isBoolean) {
                JsBoolean(primitive.getAsBoolean)
            } else if (primitive.isNumber) {
                JsNumber(primitive.getAsBigDecimal)
            } else if (primitive.isString) {
                val str = primitive.getAsString
                JsString(str)
            } else JsNull
        } else JsNull
    }

    def sprayJsonToGson(fields: Map[String, JsValue]): JsonObject = {
        val json = new JsonObject()
        fields foreach { case (k, v) => json.add(k, sprayJsonToGson(v)) }
        json
    }

    def sprayJsonToGson(values: Vector[JsValue]): JsonArray = {
        val json = new JsonArray()
        values foreach { v => json.add(sprayJsonToGson(v)) }
        json
    }

    def sprayJsonToGson(e: JsValue): JsonElement = e match {
        case JsObject(fields) => sprayJsonToGson(fields)
        case JsArray(values)  => sprayJsonToGson(values)
        case JsBoolean(b)     => new JsonPrimitive(b)
        case JsNumber(n)      => new JsonPrimitive(n)
        case JsString(s)      => new JsonPrimitive(s)
        case _                => JsonNull.INSTANCE
    }
}
