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
import akka.http.scaladsl.model.{HttpMethods, StatusCodes}
import org.apache.openwhisk.common.Logging
import org.apache.openwhisk.core.entity.ExecManifest.ImageName
import org.apache.openwhisk.core.yarn.YARNComponentActor.{CreateContainerAsync, RemoveContainer}
import spray.json.{JsArray, JsNumber, JsObject, JsString}

import scala.concurrent.ExecutionContext

/** Submits create and decommission commands to YARN */
object YARNComponentActor {
  case object CreateContainerAsync
  case class RemoveContainer(component_instance_name: String)
}

class YARNComponentActor(actorSystem: ActorSystem,
                         logging: Logging,
                         yarnConfig: YARNConfig,
                         serviceName: String,
                         imageName: ImageName)
    extends Actor {

  implicit val as: ActorSystem = actorSystem
  implicit val ec: ExecutionContext = actorSystem.dispatcher

  //Adding a container via the YARN REST API is actually done by flexing the component's container pool to a certain size.
  // This actor must track the current containerCount in order to make the correct scale-up request.
  var containerCount: Int = 0

  def receive: PartialFunction[Any, Unit] = {
    case CreateContainerAsync =>
      sender ! createContainerAsync

    case RemoveContainer(component_instance_name) =>
      sender ! removeContainer(component_instance_name)

    case input =>
      throw new IllegalArgumentException("Unknown input: " + input)
      sender ! false
  }

  def createContainerAsync(): Unit = {
    logging.info(this, s"Using YARN to create a container with image ${imageName.name}...")

    val body = JsObject("number_of_containers" -> JsNumber(containerCount + 1)).compactPrint
    val response = YARNRESTUtil.submitRequestWithAuth(
      yarnConfig.authType,
      HttpMethods.PUT,
      s"${yarnConfig.masterUrl}/app/v1/services/$serviceName/components/${imageName.name}",
      body)
    response match {
      case httpresponse(StatusCodes.OK, content) =>
        logging.info(this, s"Added container: ${imageName.name}. Response: $content")
        containerCount += 1

      case httpresponse(_, _) => YARNRESTUtil.handleYARNRESTError(logging)
    }
  }

  def removeContainer(component_instance_name: String): Unit = {
    logging.info(this, s"Removing ${imageName.name} container: $component_instance_name ")
    if (containerCount <= 0) {
      logging.warn(this, "Already at 0 containers")
    } else {
      val body = JsObject(
        "components" -> JsArray(
          JsObject(
            "name" -> JsString(imageName.name),
            "decommissioned_instances" -> JsArray(JsString(component_instance_name))))).compactPrint
      val response = YARNRESTUtil.submitRequestWithAuth(
        yarnConfig.authType,
        HttpMethods.PUT,
        s"${yarnConfig.masterUrl}/app/v1/services/$serviceName",
        body)
      response match {
        case httpresponse(StatusCodes.OK, content) =>
          logging.info(
            this,
            s"Successfully removed ${imageName.name} container: $component_instance_name. Response: $content")
          containerCount -= 1

        case httpresponse(_, _) => YARNRESTUtil.handleYARNRESTError(logging)
      }
    }
  }
}
