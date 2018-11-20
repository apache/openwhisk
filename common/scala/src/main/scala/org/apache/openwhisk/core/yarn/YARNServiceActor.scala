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

import akka.actor.{Actor, ActorSystem}
import akka.http.scaladsl.model.{HttpMethod, HttpMethods, StatusCode, StatusCodes}
import akka.stream.ActorMaterializer
import java.nio.charset.StandardCharsets
import java.security.Principal
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
import org.apache.openwhisk.core.containerpool.{Container, ContainerAddress, ContainerId}
import org.apache.openwhisk.core.entity.ExecManifest
import org.apache.openwhisk.core.entity.ExecManifest.ImageName
import org.apache.openwhisk.core.yarn.YARNServiceActor.{CreateContainer, CreateService, RemoveContainer, RemoveService}
import scala.concurrent.ExecutionContext
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
                               number_of_containers: Int,
                               launch_command: String,
                               containers: Option[List[ContainerDefinition]],
                               artifact: ArtifactDefinition,
                               resource: ResourceDefinition,
                               configuration: ConfigurationDefinition)
case class ServiceDefinition(name: String,
                             version: Option[String],
                             description: Option[String],
                             state: String,
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
  implicit val componentDefinitionFormat: RootJsonFormat[ComponentDefinition] = jsonFormat7(ComponentDefinition)
  implicit val serviceDefinitionFormat: RootJsonFormat[ServiceDefinition] = jsonFormat7(ServiceDefinition)
}
import YARNJsonProtocol._

object YARNServiceActor {
  case object CreateService
  case object RemoveService
  case class CreateContainer(actionImage: ExecManifest.ImageName)
  case class RemoveContainer(actionImage: ExecManifest.ImageName)
  val SIMPLEAUTH = "simple"
  val KERBEROSAUTH = "kerberos"
}
class YARNServiceActor(yarnConfig: YARNConfig, logging: Logging, actorSystem: ActorSystem) extends Actor {

  case class httpresponse(statusCode: StatusCode, content: String)

  val serviceStartTimeoutMS = 60000
  val containerStartTimeoutMS = 60000
  val retryWaitMS = 1000
  val runCommand = ""
  val version = "1.0.0"
  val description = "OpenWhisk Action Service"

  implicit val as: ActorSystem = actorSystem
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContext = actorSystem.dispatcher

  var containerCounts = new scala.collection.mutable.HashMap[String, Int]()
  var containerMap = new scala.collection.mutable.HashMap[String, List[ContainerDefinition]]

  def receive: PartialFunction[Any, Unit] = {

    case CreateService =>
      //Remove service if it already exists
      if (downloadServiceDefinition() != null)
        removeService()

      sender ! createService()

    case RemoveService =>
      sender ! removeService()

    case CreateContainer(imageName) =>
      sender ! createContainer(imageName)

    case RemoveContainer(imageName) =>
      sender ! removeContainer(imageName)

    case input =>
      throw new IllegalArgumentException("Unknown input: " + input)
      sender ! false
  }

  def createService(): Unit = {

    val images: Set[ImageName] = ExecManifest.runtimesManifest.runtimes.flatMap(a => a.versions.map(b => b.image))
    logging.info(this, "Creating Service with images: " + images.map(i => i.publicImageName).mkString(", "))

    val componentList = images
      .map(
        i =>
          ComponentDefinition(
            i.name.replace('.', '-'), //name must be [a-z][a-z0-9-]*
            0, //start with zero containers
            runCommand,
            Option.empty,
            ArtifactDefinition(i.publicImageName, "DOCKER"),
            ResourceDefinition(yarnConfig.cpus, yarnConfig.memory),
            ConfigurationDefinition(Map(("YARN_CONTAINER_RUNTIME_DOCKER_RUN_OVERRIDE_DISABLE", "true")))))
      .toList

    //Add kerberos def if necessary
    var kerberosDef: Option[KerberosPrincipalDefinition] = None
    if (yarnConfig.authType.equals(YARNServiceActor.KERBEROSAUTH))
      kerberosDef = Some(
        KerberosPrincipalDefinition(Some(yarnConfig.kerberosPrincipal), Some(yarnConfig.kerberosKeytab)))

    val service = ServiceDefinition(
      yarnConfig.serviceName,
      Some(version),
      Some(description),
      "STABLE",
      Some(yarnConfig.queue),
      componentList,
      kerberosDef)

    //Submit service
    val response =
      submitRequestWithAuth(HttpMethods.POST, s"${yarnConfig.masterUrl}/app/v1/services", service.toJson.compactPrint)

    //Handle response
    response match {
      case httpresponse(StatusCodes.OK, content) =>
        logging.info(this, s"Service submitted. Response: $content")

      case httpresponse(StatusCodes.Accepted, content) =>
        logging.info(this, s"Service submitted. Response: $content")

      case httpresponse(_, _) => handleYARNRESTError(response)
    }

    waitForServiceStart()
  }

  def createContainer(imageName: ExecManifest.ImageName): Container = {

    logging.info(this, s"Using YARN to create a container with image ${imageName.name}...")

    val newContainerCount = containerCounts.getOrElse(imageName.name, 0) + 1
    val body = "{\"number_of_containers\":" + newContainerCount + "}"
    val response = submitRequestWithAuth(
      HttpMethods.PUT,
      s"${yarnConfig.masterUrl}/app/v1/services/${yarnConfig.serviceName}/components/${imageName.name}",
      body)
    response match {
      case httpresponse(StatusCodes.OK, content) =>
        logging.info(this, s"Added container: ${imageName.name}. Response: $content")
        containerCounts(imageName.name) = newContainerCount

      case httpresponse(_, _) => handleYARNRESTError(response)
    }

    //Wait for container to come up
    val serviceDef = waitForContainerStability(imageName)

    //Update container list with new container details
    val updatedContainerList = serviceDef.components
      .filter(c => c.name.equals(imageName.name))
      .flatMap(c => c.containers.getOrElse(List[ContainerDefinition]()))
    logging.info(this, s"YARN service now has ${updatedContainerList.size} total containers")

    val newContainers =
      updatedContainerList.filter(a => !containerMap.getOrElse(imageName.name, List[ContainerDefinition]()).contains(a))

    //Synchronized block should prevent this from happening
    if (newContainers.length > 1)
      logging.warn(this, "More than one new container...")

    containerMap(imageName.name) = updatedContainerList
    val newContainer = newContainers.head

    val containerAddress = ContainerAddress(newContainer.ip.getOrElse("127.0.0.1")) //default port is 8080
    val containerId = ContainerId(newContainer.id)

    logging.info(this, s"New Container: ${newContainer.id}, $containerAddress")
    new YARNTask(containerId, containerAddress, ec, logging, as, imageName, yarnConfig, self)
  }

  def removeContainer(imageName: ImageName): Unit = {

    val count = containerCounts.getOrElse(imageName.name, 0)
    if (count <= 0) {
      logging.warn(this, "Already at 0 containers")
    } else {
      val body = "{\"number_of_containers\":" + (count - 1) + "}"
      val response = submitRequestWithAuth(
        HttpMethods.PUT,
        s"${yarnConfig.masterUrl}/app/v1/services/${yarnConfig.serviceName}/components/${imageName.name}",
        body)
      response match {
        case httpresponse(StatusCodes.OK, content) =>
          logging.info(this, s"Removed container: ${imageName.name}. Response: $content")
          containerCounts(imageName.name) = count - 1

        case httpresponse(_, _) => handleYARNRESTError(response)
      }
      val serviceDef = downloadServiceDefinition()

      val newContainers = serviceDef.components
        .filter(c => c.name.equals(imageName.name))
        .flatMap(c => c.containers.getOrElse(List[ContainerDefinition]()))

      containerMap(imageName.name) = newContainers
    }
  }

  def removeService(): Unit = {
    val response: httpresponse = submitRequestWithAuth(
      HttpMethods.DELETE,
      s"${yarnConfig.masterUrl}/app/v1/services/${yarnConfig.serviceName}",
      "")

    response match {
      case httpresponse(StatusCodes.OK, _) =>
        logging.info(this, "YARN service Removed")

      case httpresponse(StatusCodes.NotFound, _) =>
        logging.warn(this, "YARN service did not exist")

      case httpresponse(StatusCodes.BadRequest, _) =>
        logging.warn(this, "YARN service did not exist")

      case httpresponse(_, _) =>
        handleYARNRESTError(response)
    }
  }

  def waitForServiceStart(): Unit = {
    var started = false
    var retryCount = 0
    val maxRetryCount = serviceStartTimeoutMS / retryWaitMS
    while (!started && retryCount < maxRetryCount) {
      val serviceDef = downloadServiceDefinition()

      //Should never happen
      if (serviceDef == null)
        throw new IllegalArgumentException("Service not found?")

      serviceDef.state match {
        case "STABLE" | "STARTED" =>
          logging.info(this, "YARN service achieved stable state")
          started = true

        case state =>
          logging.info(
            this,
            s"YARN service is not in stable state yet ($retryCount/$maxRetryCount). Current state: $state")
          Thread.sleep(retryWaitMS)
      }
      retryCount += 1
    }
    if (!started)
      throw new Exception(s"After ${containerStartTimeoutMS}ms YARN service did not achieve stable state")
  }
  def waitForContainerStability(actionImage: ImageName): ServiceDefinition = {
    var stable = false
    var serviceDef: ServiceDefinition = null
    var retryCount = 0
    val maxRetryCount = containerStartTimeoutMS / retryWaitMS
    while (!stable && retryCount < maxRetryCount) {
      serviceDef = downloadServiceDefinition()
      val newContainerList = serviceDef.components
        .filter(c => c.name.equals(actionImage.name))
        .flatMap(c => c.containers.getOrElse(List[ContainerDefinition]()))

      val previousContainerList = containerMap.getOrElse(actionImage.name, List[ContainerDefinition]())

      //If container has not been created yet, or container is not in ready state yet
      if (newContainerList.size > previousContainerList.size && newContainerList.forall(c => c.state.equals("READY"))) {
        logging.info(this, s"YARN container (${actionImage.name}) achieved stable state")
        stable = true
      } else {
        logging.info(
          this,
          s"YARN container (${actionImage.name}) is not in stable state yet ($retryCount/$maxRetryCount)")
      }
      Thread.sleep(retryWaitMS)
      retryCount += 1
    }
    if (stable)
      serviceDef
    else
      throw new Exception(s"After ${containerStartTimeoutMS}ms YARN container did not achieve stable state")
  }

  def downloadServiceDefinition(): ServiceDefinition = {
    val response: httpresponse =
      submitRequestWithAuth(HttpMethods.GET, s"${yarnConfig.masterUrl}/app/v1/services/${yarnConfig.serviceName}", "")

    response match {
      case httpresponse(StatusCodes.OK, content) =>
        //logging.info(this,"Retrieved service definition from YARN")
        content.parseJson.convertTo[ServiceDefinition]

      case httpresponse(StatusCodes.NotFound, content) =>
        logging.info(this, s"Service not found. Response: $content")
        null

      case httpresponse(_, _) =>
        handleYARNRESTError(response)
        null
    }
  }

  private def submitRequestWithAuth(httpMethod: HttpMethod, URL: String, body: String): httpresponse = {

    var client: CloseableHttpClient = null
    var updatedURL = URL

    yarnConfig.authType match {
      case YARNServiceActor.SIMPLEAUTH =>
        if (URL.contains("?"))
          updatedURL = URL + "&user.name=" + System.getProperty("user.name")
        else
          updatedURL = URL + "?user.name=" + System.getProperty("user.name")

        client = HttpClientBuilder.create.build

      case YARNServiceActor.KERBEROSAUTH =>
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

        httpresponse(-1, e.getMessage)
    }
  }
  def handleYARNRESTError: PartialFunction[httpresponse, Unit] = {

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
      throw new Exception(s"Unknown response from YARN. Code: ${code.intValue()}, content: $content")
  }
}
