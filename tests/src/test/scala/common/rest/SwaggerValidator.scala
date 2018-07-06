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

package common.rest

import scala.collection.JavaConverters._

import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpResponse
import akka.util.ByteString
import com.atlassian.oai.validator.SwaggerRequestResponseValidator
import com.atlassian.oai.validator.model.SimpleRequest
import com.atlassian.oai.validator.model.SimpleResponse
import com.atlassian.oai.validator.report.ValidationReport
import com.atlassian.oai.validator.whitelist.ValidationErrorsWhitelist
import com.atlassian.oai.validator.whitelist.rule.WhitelistRules

trait SwaggerValidator {
  private val specWhitelist = ValidationErrorsWhitelist
    .create()
    .withRule(
      "Ignore action and trigger payloads",
      WhitelistRules.allOf(
        WhitelistRules.messageContains("Object instance has properties which are not allowed by the schema"),
        WhitelistRules.anyOf(
          WhitelistRules.pathContains("/web/"),
          WhitelistRules.pathContains("/actions/"),
          WhitelistRules.pathContains("/triggers/")),
        WhitelistRules.methodIs(io.swagger.models.HttpMethod.POST)))
    .withRule(
      "Ignore invalid action kinds",
      WhitelistRules.allOf(
        WhitelistRules.messageContains("kind"),
        WhitelistRules.messageContains("Instance value"),
        WhitelistRules.messageContains("not found"),
        WhitelistRules.pathContains("/actions/"),
        WhitelistRules.methodIs(io.swagger.models.HttpMethod.PUT)))
    .withRule(
      "Ignore tests that check for invalid DELETEs and PUTs on actions",
      WhitelistRules.anyOf(
        WhitelistRules.messageContains("DELETE operation not allowed on path '/api/v1/namespaces/_/actions/'"),
        WhitelistRules.messageContains("PUT operation not allowed on path '/api/v1/namespaces/_/actions/'")))

  private val specValidator = SwaggerRequestResponseValidator
    .createFor("apiv1swagger.json")
    .withWhitelist(specWhitelist)
    .build()

  /**
   * Validate a HTTP request and response against the Swagger spec. Request
   * and response bodies are passed separately so that this validation
   * does not have to consume the body content directly from the request
   * and response, which would prevent callers from later consuming it.
   *
   * @param request the HttpRequest
   * @param requestBody the request body
   * @param response the HttpResponse
   * @param responseBody the response body
   * @return The list of validation error messages, if any
   */
  def validateRequestAndResponse(request: HttpRequest,
                                 requestBody: Option[String],
                                 response: HttpResponse,
                                 responseBody: String): Seq[String] = {
    var specRequestBuilder = new SimpleRequest.Builder(request.method.value, request.uri.path.toString())
    for (header <- request.headers) {
      specRequestBuilder = specRequestBuilder.withHeader(header.name, header.value)
    }
    for ((key, value) <- request.uri.query().toMap) {
      specRequestBuilder = specRequestBuilder.withQueryParam(key, value)
    }
    if (requestBody.nonEmpty) {
      specRequestBuilder = specRequestBuilder
        .withBody(requestBody.get)
        .withHeader("content-type", request.entity.contentType.value)
    }
    val responseCopy = response.copy(entity = HttpEntity.Strict(response.entity.contentType, ByteString(responseBody)))
    var specResponseBuilder = SimpleResponse.Builder
      .status(response.status.intValue())
      .withBody(responseBody)
    for (header <- response.headers) {
      specResponseBuilder = specResponseBuilder.withHeader(header.name, header.value)
    }
    val specRequest = specRequestBuilder.build()
    val specResponse = specResponseBuilder.build()

    specValidator
      .validate(specRequest, specResponse)
      .getMessages
      .asScala
      .filter(m => m.getLevel == ValidationReport.Level.ERROR)
      .map(_.toString)
  }
}
