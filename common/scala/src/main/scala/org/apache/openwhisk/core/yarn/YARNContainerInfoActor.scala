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

import akka.actor.{Actor, ActorRef, ActorSystem}
import org.apache.openwhisk.common.Logging
import org.apache.openwhisk.core.containerpool.{ContainerAddress, ContainerId}
import org.apache.openwhisk.core.entity.ExecManifest.ImageName

import scala.collection.immutable.HashMap
import scala.concurrent.ExecutionContext

case class GetContainerInfo(yarnComponentActorRef: ActorRef)

//This actor is separate from the YARNComponentActor so that container create commands can be issued in parallel
class YARNContainerInfoActor(actorSystem: ActorSystem,
                             logging: Logging,
                             yarnConfig: YARNConfig,
                             serviceName: String,
                             imageName: ImageName)
    extends Actor {

  implicit val as: ActorSystem = actorSystem
  implicit val ec: ExecutionContext = actorSystem.dispatcher

  val containerStartTimeoutMS = 60000
  val retryWaitMS = 1000

  //Map with the definition of all active containers
  var containerDefMap: Map[String, ContainerDefinition] = new HashMap[String, ContainerDefinition]

  //Map that keeps track of which containers have been returned to the main invoker for use
  val containersAllocated = new scala.collection.mutable.HashMap[String, Boolean]

  def receive: PartialFunction[Any, Unit] = {

    case GetContainerInfo(yarnComponentActorRef) =>
      //Check if there are any left over containers from the last check
      var firstNewContainerName = containersAllocated.find { case (k, v) => !v }

      //If no containers are ready, wait for one to come up (up to containerStartTimeoutMS milliseconds)
      var retryCount = 0
      val maxRetryCount = containerStartTimeoutMS / retryWaitMS
      while (firstNewContainerName.isEmpty && retryCount < maxRetryCount) {
        //Get updated service def
        val serviceDef =
          YARNRESTUtil.downloadServiceDefinition(yarnConfig.authType, serviceName, yarnConfig.masterUrl)(logging)

        //Update container list with new container details
        if (serviceDef == null) {
          retryCount += 1
          Thread.sleep(retryWaitMS)
          logging.info(this, s"Waiting for ${imageName.name} YARN container ($retryCount/$maxRetryCount)")
        } else {
          containerDefMap = serviceDef.components
            .filter(c => c.name.equals(imageName.name))
            .flatMap(c => c.containers.getOrElse(List[ContainerDefinition]()))
            .filter(containerDef => containerDef.state.equals("READY"))
            .map(containerDef => (containerDef.component_instance_name, containerDef))
            .toMap

          //Filter map to only contain active containers
          containersAllocated.retain((k, v) => containerDefMap.contains(k))
          for (containerDef <- containerDefMap) {
            if (!containersAllocated.contains(containerDef._1))
              containersAllocated.put(containerDef._1, false)
          }

          firstNewContainerName = containersAllocated.find { case (k, v) => !v }

          //keep waiting
          if (firstNewContainerName.isEmpty) {
            retryCount += 1
            Thread.sleep(retryWaitMS)
            logging.info(this, s"Waiting for ${imageName.name} YARN container ($retryCount/$maxRetryCount)")
          }
        }
      }
      if (firstNewContainerName.isEmpty) {
        throw new Exception(s"After ${containerStartTimeoutMS}ms ${imageName.name} YARN container was not available")
      }

      //Return container
      val newContainerDef = containerDefMap(firstNewContainerName.get._1)
      containersAllocated(firstNewContainerName.get._1) = true

      val containerAddress = ContainerAddress(newContainerDef.ip.getOrElse("127.0.0.1")) //default port is 8080
      val containerId = ContainerId(newContainerDef.id)

      logging.info(this, s"New ${imageName.name} YARN Container: ${newContainerDef.id}, $containerAddress")
      sender ! new YARNTask(
        containerId,
        containerAddress,
        ec,
        logging,
        as,
        newContainerDef.component_instance_name,
        imageName,
        yarnConfig,
        yarnComponentActorRef)
    case input =>
      throw new IllegalArgumentException("Unknown input: " + input)
      sender ! None
  }
}
