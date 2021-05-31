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

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.http.scaladsl.model.{HttpMethods, StatusCodes}
import akka.pattern.ask
import akka.util.Timeout
import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.core.containerpool._
import org.apache.openwhisk.core.entity.ExecManifest.ImageName
import org.apache.openwhisk.core.entity.{ByteSize, ExecManifest, InvokerInstanceId}
import org.apache.openwhisk.core.yarn.YARNComponentActor.CreateContainerAsync
import org.apache.openwhisk.core.{ConfigKeys, WhiskConfig}
import pureconfig._
import pureconfig.generic.auto._
import spray.json._

import scala.collection.immutable.HashMap
import scala.concurrent.{blocking, ExecutionContext, Future}
import scala.concurrent.duration._
import YARNJsonProtocol._
import akka.stream.ActorMaterializer

case class YARNConfig(masterUrl: String,
                      yarnLinkLogMessage: Boolean,
                      serviceName: String,
                      authType: String,
                      kerberosPrincipal: String,
                      kerberosKeytab: String,
                      queue: String,
                      memory: String,
                      cpus: Int)

object YARNContainerFactoryProvider extends ContainerFactoryProvider {
  override def instance(actorSystem: ActorSystem,
                        logging: Logging,
                        config: WhiskConfig,
                        instance: InvokerInstanceId,
                        parameters: Map[String, Set[String]]): ContainerFactory =
    new YARNContainerFactory(actorSystem, logging, config, instance, parameters)
}

class YARNContainerFactory(actorSystem: ActorSystem,
                           logging: Logging,
                           config: WhiskConfig,
                           instance: InvokerInstanceId,
                           parameters: Map[String, Set[String]],
                           containerArgs: ContainerArgsConfig =
                             loadConfigOrThrow[ContainerArgsConfig](ConfigKeys.containerArgs),
                           yarnConfig: YARNConfig = loadConfigOrThrow[YARNConfig](ConfigKeys.yarn))
    extends ContainerFactory {

  val images: Set[ImageName] = ExecManifest.runtimesManifest.runtimes.flatMap(a => a.versions.map(b => b.image))

  //One actor of each type per image for parallelism
  private var yarnComponentActors: Map[ImageName, ActorRef] = HashMap[ImageName, ActorRef]()
  private var YARNContainerInfoActors: Map[ImageName, ActorRef] = HashMap[ImageName, ActorRef]()

  val serviceStartTimeoutMS = 60000
  val retryWaitMS = 1000
  val runCommand = ""
  val version = "1.0.0"
  val description = "OpenWhisk Action Service"

  //Allows for invoker HA
  val serviceName: String = yarnConfig.serviceName + "-" + instance.toInt

  val containerStartTimeoutMS = 60000

  implicit val as: ActorSystem = actorSystem
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContext = actorSystem.dispatcher

  override def init(): Unit = {
    yarnComponentActors = images
      .map(
        i =>
          (
            i,
            actorSystem.actorOf(
              Props(new YARNComponentActor(actorSystem, logging, yarnConfig, serviceName, i)),
              name = s"YARNComponentActor-${i.name}")))
      .toMap
    YARNContainerInfoActors = images
      .map(
        i =>
          (
            i,
            actorSystem.actorOf(
              Props(new YARNContainerInfoActor(actorSystem, logging, yarnConfig, serviceName, i)),
              name = s"YARNComponentInfoActor-${i.name}")))
      .toMap
    blocking {
      implicit val timeout: Timeout = Timeout(serviceStartTimeoutMS.milliseconds)

      //Remove service if it already exists
      val serviceDef =
        YARNRESTUtil.downloadServiceDefinition(yarnConfig.authType, serviceName, yarnConfig.masterUrl)(logging)

      if (serviceDef != null)
        removeService()

      createService()
    }
  }
  override def createContainer(
    unusedtid: TransactionId,
    unusedname: String,
    actionImage: ExecManifest.ImageName,
    unuseduserProvidedImage: Boolean,
    unusedmemory: ByteSize,
    unusedcpuShares: Int)(implicit config: WhiskConfig, logging: Logging): Future[Container] = {
    implicit val timeout: Timeout = Timeout(containerStartTimeoutMS.milliseconds)

    //First send the create command to YARN, then with a different actor, wait for the container to be ready
    ask(yarnComponentActors(actionImage), CreateContainerAsync).flatMap(_ =>
      ask(YARNContainerInfoActors(actionImage), GetContainerInfo(yarnComponentActors(actionImage))).mapTo[Container])
  }
  override def cleanup(): Unit = {
    removeService()
    yarnComponentActors foreach { case (k, v)     => actorSystem.stop(v) }
    YARNContainerInfoActors foreach { case (k, v) => actorSystem.stop(v) }
  }
  def createService(): Unit = {
    logging.info(this, "Creating Service with images: " + images.map(i => i.resolveImageName()).mkString(", "))

    val componentList = images
      .map(
        i =>
          ComponentDefinition(
            i.name.replace('.', '-'), //name must be [a-z][a-z0-9-]*
            Some(0), //start with zero containers
            Some(runCommand),
            Option.empty,
            Some(ArtifactDefinition(i.resolveImageName(), "DOCKER")),
            Some(ResourceDefinition(yarnConfig.cpus, yarnConfig.memory)),
            Some(ConfigurationDefinition(Map(("YARN_CONTAINER_RUNTIME_DOCKER_RUN_OVERRIDE_DISABLE", "true")))),
            List[String]()))
      .toList

    //Add kerberos def if necessary
    var kerberosDef: Option[KerberosPrincipalDefinition] = None
    if (yarnConfig.authType.equals(YARNRESTUtil.KERBEROSAUTH))
      kerberosDef = Some(
        KerberosPrincipalDefinition(Some(yarnConfig.kerberosPrincipal), Some(yarnConfig.kerberosKeytab)))

    val service = ServiceDefinition(
      Some(serviceName),
      Some(version),
      Some(description),
      Some("STABLE"),
      Some(yarnConfig.queue),
      componentList,
      kerberosDef)

    //Submit service
    val response =
      YARNRESTUtil.submitRequestWithAuth(
        yarnConfig.authType,
        HttpMethods.POST,
        s"${yarnConfig.masterUrl}/app/v1/services",
        service.toJson.compactPrint)

    //Handle response
    response match {
      case httpresponse(StatusCodes.OK, content) =>
        logging.info(this, s"Service submitted. Response: $content")

      case httpresponse(StatusCodes.Accepted, content) =>
        logging.info(this, s"Service submitted. Response: $content")

      case httpresponse(_, _) => YARNRESTUtil.handleYARNRESTError(logging)
    }

    //Wait for service start (up to serviceStartTimeoutMS milliseconds)
    var started = false
    var retryCount = 0
    val maxRetryCount = serviceStartTimeoutMS / retryWaitMS
    while (!started && retryCount < maxRetryCount) {
      val serviceDef =
        YARNRESTUtil.downloadServiceDefinition(yarnConfig.authType, serviceName, yarnConfig.masterUrl)(logging)

      if (serviceDef == null) {
        logging.info(this, "Service not found yet")
        Thread.sleep(retryWaitMS)
      } else {
        serviceDef.state.getOrElse(None) match {
          case "STABLE" | "STARTED" =>
            logging.info(this, "YARN service achieved stable state")
            started = true

          case state =>
            logging.info(
              this,
              s"YARN service is not in stable state yet ($retryCount/$maxRetryCount). Current state: $state")
            Thread.sleep(retryWaitMS)
        }
      }
      retryCount += 1
    }
    if (!started)
      throw new Exception(s"After ${serviceStartTimeoutMS}ms YARN service did not achieve stable state")
  }
  def removeService(): Unit = {
    val response: httpresponse =
      YARNRESTUtil.submitRequestWithAuth(
        yarnConfig.authType,
        HttpMethods.DELETE,
        s"${yarnConfig.masterUrl}/app/v1/services/$serviceName",
        "")

    response match {
      case httpresponse(StatusCodes.OK, _) =>
        logging.info(this, "YARN service Removed")

      case httpresponse(StatusCodes.NotFound, _) =>
        logging.warn(this, "YARN service did not exist")

      case httpresponse(StatusCodes.BadRequest, _) =>
        logging.warn(this, "YARN service did not exist")

      case httpresponse(_, _) =>
        YARNRESTUtil.handleYARNRESTError(logging)
    }
  }
}
