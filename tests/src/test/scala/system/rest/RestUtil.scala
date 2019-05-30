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

package system.rest

import akka.http.scaladsl.model.Uri

import scala.util.Try
import io.restassured.RestAssured
import io.restassured.config.RestAssuredConfig
import io.restassured.config.SSLConfig
import common.WhiskProperties
import spray.json._

/**
 * Utilities for REST tests
 */
trait RestUtil {

  private val skipKeyStore = WhiskProperties.isSSLCheckRelaxed
  private val trustStorePassword = WhiskProperties.getSslCertificateChallenge

  // force RestAssured to allow all hosts in SSL certificates
  val sslconfig = {
    val inner = new SSLConfig().allowAllHostnames()
    val config = if (!skipKeyStore && trustStorePassword != null) {
      inner.keyStore("keystore", trustStorePassword)
      inner.trustStore("keystore", trustStorePassword)
    } else {
      inner.relaxedHTTPSValidation()
    }
    new RestAssuredConfig().sslConfig(config)
  }

  /**
   * @return the URL for the whisk service as a hostname (this is the edge/router as a hostname)
   */
  def getServiceApiHost(subdomain: String, withProtocol: Boolean): String = {
    WhiskProperties.getApiHostForClient(subdomain, withProtocol)
  }

  /**
   * @return the URL and port for the whisk service using the main router or the edge router ip address
   */
  def getServiceURL(): String = {
    val host = WhiskProperties.getEdgeHost
    val uri = Uri(host)
    //Ensure that port is explicitly include in the returned URL
    val absolute = if (uri.isAbsolute) {
      uri.withPort(uri.effectivePort)
    } else {
      val apiPort = WhiskProperties.getEdgeHostApiPort
      val protocol = if (apiPort == 443) "https" else "http"
      Uri.from(scheme = protocol, host = host, port = apiPort)
    }
    absolute.toString()
  }

  /**
   * @return the base URL for the whisk REST API
   */
  def getBaseURL(path: String = "/api/v1"): String = {
    getServiceURL() + path
  }

  /**
   * construct the Json schema for a particular model type in the swagger model,
   * and return it as a string.
   */
  def getJsonSchema(model: String, path: String = "/api/v1"): JsValue = {
    val response = RestAssured
      .given()
      .config(sslconfig)
      .get(getServiceURL() + s"${if (path.endsWith("/")) path else path + "/"}api-docs")

    assert(response.statusCode() == 200)

    val body = Try { response.body().asString().parseJson.asJsObject }
    val schema = body map { _.fields("definitions").asJsObject }
    val t = schema map { _.fields(model).asJsObject } getOrElse JsObject.empty
    val d = JsObject("definitions" -> (schema getOrElse JsObject.empty))
    JsObject(t.fields ++ d.fields)
  }
}
