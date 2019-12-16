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

package org.apache.openwhisk.core.containerpool.yarn.test

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util
import java.util.concurrent.atomic.AtomicLong

import akka.http.scaladsl.model.DateTime
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import org.apache.openwhisk.core.yarn.YARNJsonProtocol._
import org.apache.openwhisk.core.yarn.{YARNResponseDefinition, _}
import spray.json._

import scala.collection.mutable
import scala.util.Random

//Mocks the Hadoop YARN Resource Manager. Only supports simple authentication
class MockYARNRM(port: Int, delayMS: Int) {
  val services: mutable.Map[String, ServiceDefinition] = mutable.Map[String, ServiceDefinition]()
  val initCompletionTimes: mutable.Map[String, DateTime] = mutable.Map[String, DateTime]()
  val flexCompletionTimes: mutable.Map[String, mutable.Map[String, DateTime]] =
    mutable.Map[String, mutable.Map[String, DateTime]]()

  private var server = HttpServer.create(new InetSocketAddress(port), -1)
  val POST = "POST"
  val GET = "GET"
  val PUT = "PUT"
  val DELETE = "DELETE"
  private var container_instance_number = new AtomicLong(0)

  this.server
    .createContext(
      "/app/v1/services",
      (httpExchange: HttpExchange) => {
        try {
          if (getUserName(httpExchange).isEmpty) {
            writeResponse(httpExchange, 403, "Username not provided")
          } else {
            val servicePattern = "/app/v1/services/([a-z-0-9]+)".r
            val FlexUrlPattern = "/app/v1/services/([a-z-0-9]+)/components/([a-z-0-9]+)".r
            (httpExchange.getRequestMethod, httpExchange.getRequestURI.getPath) match {
              case (POST, "/app/v1/services") =>
                val body: String = scala.io.Source.fromInputStream(httpExchange.getRequestBody).mkString
                val servDef = body.parseJson.convertTo[ServiceDefinition]

                if (this.services.contains(servDef.name.get)) {
                  writeResponse(httpExchange, 400, YARNResponseDefinition("Invalid request. Service already exists"))
                } else {
                  this.services.put(servDef.name.get, servDef.copy(state = Some("ACCEPTED")))
                  initCompletionTimes.put(servDef.name.get, DateTime.now.plus(delayMS))
                  flexCompletionTimes.put(servDef.name.get, mutable.Map[String, DateTime]())
                  writeResponse(httpExchange, 200, YARNResponseDefinition("Creating Service"))
                }

              case (GET, servicePattern(serviceName)) =>
                if (!this.services.contains(serviceName)) {
                  writeResponse(httpExchange, 404, YARNResponseDefinition("Service not found"))
                } else {
                  updateServDef(serviceName)

                  writeResponse(httpExchange, 200, this.services(serviceName).toJson.compactPrint)
                }

              case (PUT, servicePattern(serviceName)) =>
                val body: String = scala.io.Source.fromInputStream(httpExchange.getRequestBody).mkString
                val incomingComponentDef = body.parseJson.convertTo[ServiceDefinition].components.head
                if (!this.services.contains(serviceName)) {
                  writeResponse(httpExchange, 404, YARNResponseDefinition("Service not found"))
                } else if (!this
                             .services(serviceName)
                             .components
                             .exists(c => c.name.equals(incomingComponentDef.name))) {
                  writeResponse(httpExchange, 404, YARNResponseDefinition("Component not found"))
                } else {
                  val containerToRemove = incomingComponentDef.decommissioned_instances.head

                  val serviceDef = this.services(serviceName)
                  val componentDef = serviceDef.components.find(c => c.name.equals(incomingComponentDef.name)).get
                  val originalSize = componentDef.number_of_containers.get
                  var containerList = componentDef.containers.getOrElse(List[ContainerDefinition]())
                  containerList = containerList.filterNot(c => c.component_instance_name.equals(containerToRemove))

                  val newComponentDef =
                    componentDef.copy(number_of_containers = Some(originalSize - 1), containers = Option(containerList))

                  val partialComponentList = serviceDef.components.filter(c => !c.name.equals(componentDef.name))

                  this.services.put(serviceName, serviceDef.copy(components = partialComponentList :+ newComponentDef))

                  writeResponse(
                    httpExchange,
                    200,
                    YARNResponseDefinition(s"Service $serviceName has successfully decommissioned instances."))
                }
              case (DELETE, servicePattern(serviceName)) =>
                if (!this.services.contains(serviceName)) {
                  writeResponse(httpExchange, 404, YARNResponseDefinition("Service not found"))
                } else {
                  this.services.remove(serviceName)
                  this.initCompletionTimes.remove(serviceName)
                  this.flexCompletionTimes.remove(serviceName)
                  writeResponse(httpExchange, 200, YARNResponseDefinition("Service deleted"))
                }

              case (PUT, FlexUrlPattern(serviceName, componentName)) =>
                val serviceDef = this.services.get(serviceName).orNull
                val body: String = scala.io.Source.fromInputStream(httpExchange.getRequestBody).mkString
                val newSize = body.parseJson.asJsObject.fields.find(field => field._1.equals("number_of_containers"))
                if (serviceDef == null || !flexCompletionTimes.contains(serviceName)) {
                  writeResponse(httpExchange, 404, YARNResponseDefinition("Service not found"))
                } else if (newSize.isEmpty) {
                  writeResponse(
                    httpExchange,
                    400,
                    YARNResponseDefinition("Invalid request. number_of_containers not specified"))
                } else {
                  val newSizeInt: Int = newSize.get._2.asInstanceOf[JsNumber].value.toInt
                  val componentDef = serviceDef.components.find(c => c.name.equals(componentName))

                  if (componentDef.isEmpty) {
                    writeResponse(
                      httpExchange,
                      400,
                      YARNResponseDefinition("Invalid request. Component does not exist"))
                  } else {
                    val originalSize = componentDef.get.number_of_containers.get

                    var containerList = componentDef.get.containers.getOrElse(List[ContainerDefinition]())
                    if (originalSize < newSizeInt) {
                      containerList = containerList :+ ContainerDefinition(
                        Some("127.0.0.1"),
                        Option(""),
                        componentName + "-" + container_instance_number.getAndIncrement(),
                        Option(""),
                        Random.alphanumeric.take(10).mkString,
                        0,
                        "INIT")
                      flexCompletionTimes.get(serviceName).orNull.put(componentName, DateTime.now.plus(delayMS))
                    } else {
                      containerList = containerList.init
                    }

                    val newComponentDef =
                      componentDef.get.copy(number_of_containers = Some(newSizeInt), containers = Option(containerList))

                    val partialComponentList = serviceDef.components.filter(c => !c.name.equals(componentName))

                    this.services
                      .put(serviceName, serviceDef.copy(components = partialComponentList :+ newComponentDef))

                    writeResponse(
                      httpExchange,
                      200,
                      YARNResponseDefinition(
                        "Updating component (" + componentName + ") size from " + originalSize + " to " + newSizeInt))
                  }
                }

              case (_, _) =>
                writeResponse(httpExchange, 404, YARNResponseDefinition("Invalid request"))
            }
          }
        } catch {
          case exception: Throwable =>
            writeResponse(httpExchange, 500, YARNResponseDefinition("Unknown error: " + exception.getMessage))
        }
      })
  this.server.setExecutor(null) // creates a default executor

  def start(): Unit = {
    this.server.start()
  }
  def stop(): Unit = {
    this.server.stop(0)
  }
  //updates component and service states based on completion-time maps
  def updateServDef(serviceName: String): Unit = {

    var tempServiceDef = this.services.get(serviceName).orNull

    if (tempServiceDef == null)
      throw new IllegalArgumentException("Invalid serviceName: " + serviceName)

    if (this.initCompletionTimes(serviceName) < DateTime.now)
      tempServiceDef = tempServiceDef.copy(state = Some("STABLE"))

    val updatedComponents = tempServiceDef.components.map(comp => {
      val updatedContainers = comp.containers
        .getOrElse(List[ContainerDefinition]())
        .map(container => {
          if (container.state.equals("INIT") && this.flexCompletionTimes
                .getOrElse(serviceName, mutable.Map[String, DateTime]())
                .getOrElse(comp.name, DateTime.MinValue) < DateTime.now) {
            val newContainer = container.copy(state = "READY")
            newContainer
          } else
            container
        })
      comp.copy(containers = Option(updatedContainers))
    })
    this.services.put(serviceName, tempServiceDef.copy(components = updatedComponents))
  }
  //Gets username from query string
  private def getUserName(httpExchange: HttpExchange): String = {
    val query = httpExchange.getRequestURI.getQuery

    val props = new util.HashMap[String, String]
    query
      .split("&")
      .foreach(param => {
        val entry = param.split("=")
        if (entry.length > 1)
          props.put(entry(0), entry(1))
        else
          props.put(entry(0), "")
      })
    props.get("user.name")
  }

  private def writeResponse(t: HttpExchange, code: Int, content: YARNResponseDefinition): Unit = {
    writeResponse(t, code, content.toJson.compactPrint)
  }
  private def writeResponse(t: HttpExchange, code: Int, content: String): Unit = {
    val bytes = content.getBytes(StandardCharsets.UTF_8)
    t.sendResponseHeaders(code, bytes.length)
    val os = t.getResponseBody
    os.write(bytes)
    os.close()
  }
}
