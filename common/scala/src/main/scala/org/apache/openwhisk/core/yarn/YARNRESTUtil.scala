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

package org.apache.openwhisk.core.yarn

import java.nio.charset.StandardCharsets
import java.security.Principal

import akka.http.scaladsl.model.{HttpMethod, HttpMethods, StatusCode, StatusCodes}
import javax.security.sasl.AuthenticationException
import org.apache.commons.io.IOUtils
import org.apache.http.auth.{AuthSchemeProvider, AuthScope, Credentials}
import org.apache.http.client.CredentialsProvider
import org.apache.http.client.config.AuthSchemes
import org.apache.http.client.methods._
import org.apache.http.config.RegistryBuilder
import org.apache.http.entity.StringEntity
import org.apache.http.impl.auth.SPNegoSchemeFactory
import org.apache.http.impl.client.{CloseableHttpClient, HttpClientBuilder, HttpClients}
import org.apache.openwhisk.common.Logging
import spray.json._

case class KerberosPrincipalDefinition(principal_name: Option[String], keytab: Option[String])
case class YARNResponseDefinition(diagnostics: String)
case class ConfigurationDefinition(env: Map[String, String])
case class ArtifactDefinition(id: String, `type`: String)
case class ResourceDefinition(cpus: Int, memory: String)
case class ContainerDefinition(ip: Option[String],
                               bare_host: Option[String],
                               component_instance_name: String,
                               hostname: Option[String],
                               id: String,
                               launch_time: Long,
                               state: String)
case class ComponentDefinition(name: String,
                               number_of_containers: Option[Int],
                               launch_command: Option[String],
                               containers: Option[List[ContainerDefinition]],
                               artifact: Option[ArtifactDefinition],
                               resource: Option[ResourceDefinition],
                               configuration: Option[ConfigurationDefinition],
                               decommissioned_instances: List[String])

case class ServiceDefinition(name: Option[String],
                             version: Option[String],
                             description: Option[String],
                             state: Option[String],
                             queue: Option[String],
                             components: List[ComponentDefinition],
                             kerberos_principal: Option[KerberosPrincipalDefinition])

object YARNJsonProtocol extends DefaultJsonProtocol {
  implicit val KerberosPrincipalDefinitionFormat: RootJsonFormat[KerberosPrincipalDefinition] = jsonFormat2(
    KerberosPrincipalDefinition)
  implicit val YARNResponseDefinitionFormat: RootJsonFormat[YARNResponseDefinition] = jsonFormat1(
    YARNResponseDefinition)
  implicit val configurationDefinitionFormat: RootJsonFormat[ConfigurationDefinition] = jsonFormat1(
    ConfigurationDefinition)
  implicit val artifactDefinitionFormat: RootJsonFormat[ArtifactDefinition] = jsonFormat2(ArtifactDefinition)
  implicit val resourceDefinitionFormat: RootJsonFormat[ResourceDefinition] = jsonFormat2(ResourceDefinition)
  implicit val containerDefinitionFormat: RootJsonFormat[ContainerDefinition] = jsonFormat7(ContainerDefinition)
  implicit val componentDefinitionFormat: RootJsonFormat[ComponentDefinition] = jsonFormat8(ComponentDefinition)
  implicit val serviceDefinitionFormat: RootJsonFormat[ServiceDefinition] = jsonFormat7(ServiceDefinition)
}
import YARNJsonProtocol._

case class httpresponse(statusCode: StatusCode, content: String)

object YARNRESTUtil {
  val SIMPLEAUTH = "simple"
  val KERBEROSAUTH = "kerberos"
  def downloadServiceDefinition(authType: String, serviceName: String, masterUrl: String)(
    implicit logging: Logging): ServiceDefinition = {
    val response: httpresponse =
      YARNRESTUtil.submitRequestWithAuth(authType, HttpMethods.GET, s"$masterUrl/app/v1/services/$serviceName", "")

    response match {
      case httpresponse(StatusCodes.OK, content) =>
        content.parseJson.convertTo[ServiceDefinition]

      case httpresponse(StatusCodes.NotFound, content) =>
        logging.info(this, s"Service not found. Response: $content")
        null

      case httpresponse(_, _) =>
        handleYARNRESTError(logging)
        null
    }
  }

  def submitRequestWithAuth(authType: String, httpMethod: HttpMethod, URL: String, body: String): httpresponse = {

    var client: CloseableHttpClient = null
    var updatedURL = URL

    authType match {
      case SIMPLEAUTH =>
        if (URL.contains("?"))
          updatedURL = URL + "&user.name=" + System.getProperty("user.name")
        else
          updatedURL = URL + "?user.name=" + System.getProperty("user.name")

        client = HttpClientBuilder.create.build

      case KERBEROSAUTH =>
        //System.setProperty("sun.security.krb5.debug", "true")
        //System.setProperty("sun.security.spnego.debug", "true")
        System.setProperty("javax.security.auth.useSubjectCredsOnly", "false")

        //Null credentials result in jaas login
        val useJaaS = new CredentialsProvider {
          override def setCredentials(authscope: AuthScope, credentials: Credentials): Unit = {}

          override def getCredentials(authscope: AuthScope): Credentials = new Credentials() {
            def getPassword: String = null
            def getUserPrincipal: Principal = null
          }
          override def clear(): Unit = {}
        }

        val authSchemeRegistry = RegistryBuilder
          .create[AuthSchemeProvider]()
          .register(AuthSchemes.SPNEGO, new SPNegoSchemeFactory())
          .build()

        client = HttpClients
          .custom()
          .setDefaultAuthSchemeRegistry(authSchemeRegistry)
          .setDefaultCredentialsProvider(useJaaS)
          .build()
    }

    var request: HttpRequestBase = null
    httpMethod match {
      case HttpMethods.GET =>
        request = new HttpGet(updatedURL)

      case HttpMethods.POST =>
        request = new HttpPost(updatedURL)
        request.asInstanceOf[HttpPost].setEntity(new StringEntity(body.toString, StandardCharsets.UTF_8.toString))

      case HttpMethods.PUT =>
        request = new HttpPut(updatedURL)
        request.asInstanceOf[HttpPut].setEntity(new StringEntity(body.toString, StandardCharsets.UTF_8.toString))

      case HttpMethods.DELETE =>
        request = new HttpDelete(updatedURL)

      case _ =>
        throw new IllegalArgumentException(s"Unsupported HTTP method: $httpMethod")
    }
    request.addHeader("content-type", "application/json")

    try {
      val response = client.execute(request)
      val responseBody = IOUtils.toString(response.getEntity.getContent, StandardCharsets.UTF_8)
      val statusCode: Int = response.getStatusLine.getStatusCode

      httpresponse(statusCode, responseBody)

    } catch {
      case e: Exception =>
        e.printStackTrace()

        httpresponse(500, e.getMessage)
    }
  }
  def handleYARNRESTError(implicit logging: Logging): PartialFunction[httpresponse, Unit] = {

    case httpresponse(StatusCodes.Unauthorized, content) =>
      logging.error(
        this,
        s"Received 401 (Authentication Required) response code from YARN. Check authentication. Response: $content")
      throw new AuthenticationException(
        s"Received 401 (Authentication Required) response code from YARN. Check authentication. Response: $content")

    case httpresponse(StatusCodes.Forbidden, content) =>
      logging.error(this, s"Received 403 (Forbidden) response code from YARN. Check authentication. Response: $content")
      throw new AuthenticationException(
        s"Received 403 (Forbidden) response code from YARN. Check authentication. Response: $content")

    case httpresponse(code, content) =>
      logging.error(this, "Unknown response from YARN")
      throw new Exception(s"Unknown response from YARN. Code: ${code.intValue}, content: $content")
  }
}
