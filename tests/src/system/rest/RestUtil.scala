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

package system.rest

import com.jayway.restassured.RestAssured
import com.jayway.restassured.config.RestAssuredConfig
import com.jayway.restassured.config.SSLConfig
import common.WhiskProperties
import spray.json.JsValue
import spray.json.JsObject
import spray.json.pimpString
import scala.util.Try
import spray.json.JsNull

/**
 * Utilities for REST tests
 */
protected[rest] trait RestUtil {

    private val trustStorePassword = WhiskProperties.getSslCertificateChallenge

    // must force RestAssured to allow all hosts in SSL certificates ...
    RestAssured.config = new RestAssuredConfig()
        .sslConfig(new SSLConfig().keystore("keystore", trustStorePassword).allowAllHostnames());

    /**
     * @return the URL and port for the whisk service
     */
    def getServiceURL(): String = {
        val apiPort = WhiskProperties.getEdgeHostApiPort()
        val protocol = if (apiPort == 443) "https" else "http"
        protocol + "://" + WhiskProperties.getEdgeHost() + ":" + apiPort
    }

    /**
     * @return the base URL for the whisk REST API
     */
    def getBaseURL(): String = {
        getServiceURL() + "/api/v1"
    }

    /**
     * construct the Json schema for a particular model type in the swagger model,
     * and return it as a string.
     */
    def getJsonSchema(model: String): JsValue = {

        val response = RestAssured.get(getServiceURL() + "/api/v1/api-docs")

        assert(response.statusCode() == 200)

        val body = Try { response.body().asString().parseJson.asJsObject }
        val schema = body map { _.fields("definitions").asJsObject }
        val t = schema map { _.fields(model).asJsObject } getOrElse JsObject()
        val d = JsObject("definitions" -> (schema getOrElse JsObject()))
        JsObject(t.fields ++ d.fields)
    }
}
