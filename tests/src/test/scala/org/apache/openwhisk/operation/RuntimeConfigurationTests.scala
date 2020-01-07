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

package org.apache.openwhisk.operation

import akka.http.scaladsl.Http
import akka.http.scaladsl.model.headers.{Authorization, BasicHttpCredentials}
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethods, HttpRequest, StatusCodes}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import common._
import common.rest.HttpConnection
import org.apache.openwhisk.core.connector.PrewarmContainerDataList
import org.apache.openwhisk.core.connector.PrewarmContainerDataProtocol._
import org.junit.runner.RunWith
import org.scalatest.Matchers
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner
import pureconfig.loadConfigOrThrow
import spray.json._
import system.rest.RestUtil

import scala.concurrent.duration._
import scala.util.Random

@RunWith(classOf[JUnitRunner])
class RuntimeConfigurationTests
    extends TestHelpers
    with RestUtil
    with Matchers
    with ScalaFutures
    with WskActorSystem
    with StreamLogging {

  implicit val materializer = ActorMaterializer()

  val kind = "nodejs:10"
  val memory = 128
  var initialCount = new Random().nextInt(3) + 1

  def getRuntimes = {
    s"""    {
                           "runtimes": {
                             "nodejs": [{
                               "kind": "${kind}",
                               "default": true,
                               "image": {
                                 "prefix": "openwhisk",
                                 "name": "action-nodejs-v10",
                                 "tag": "nightly"
                                },
                               "deprecated": false,
                               "attached": {
                                 "attachmentName": "codefile",
                                 "attachmentType": "text/plain"
                               },
                               "stemCells": [{
                                 "initialCount": ${initialCount},
                                 "memory": "${memory} MB"
                               }]
                             }]
                           }
                         }"""
  }

  val invokerProtocol = loadConfigOrThrow[String]("whisk.invoker.protocol")
  val invokerAddress = WhiskProperties.getBaseInvokerAddress

  val controllerProtocol = loadConfigOrThrow[String]("whisk.controller.protocol")
  val controllerAddress = WhiskProperties.getBaseControllerAddress
  val whiskControllerUsername = loadConfigOrThrow[String]("whisk.controller.username")
  val whiskControllerPassword = loadConfigOrThrow[String]("whisk.controller.password")
  val whiskInvokerUsername = loadConfigOrThrow[String]("whisk.invoker.username")
  val whiskInvokerPassword = loadConfigOrThrow[String]("whisk.invoker.password")
  val controllerAuthHeader = Authorization(BasicHttpCredentials(whiskControllerUsername, whiskControllerPassword))
  val invokerAuthHeader = Authorization(BasicHttpCredentials(whiskInvokerUsername, whiskInvokerPassword))

  val getRuntimeUrl = s"${invokerProtocol}://${invokerAddress}/getRuntime"
  val invokerChangeRuntimeUrl = s"${invokerProtocol}://${invokerAddress}/config/runtime"
  val controllerChangeRuntimeUrl =
    s"${controllerProtocol}://${controllerAddress}/config/runtime"

  it should "change all managed invokers's prewarm config" in {
    //Change runtime config
    Http()
      .singleRequest(
        HttpRequest(
          method = HttpMethods.POST,
          uri = s"${controllerChangeRuntimeUrl}",
          headers = List(controllerAuthHeader),
          entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, getRuntimes)),
        connectionContext = HttpConnection.getContext(controllerProtocol))
      .map { response =>
        response.status shouldBe StatusCodes.Accepted
      }

    // Make sure previous http post call successfully
    Thread.sleep(5.seconds.toMillis)

    //Cal the prewarm container number whether right
    Http()
      .singleRequest(
        HttpRequest(method = HttpMethods.GET, uri = s"${getRuntimeUrl}", headers = List(invokerAuthHeader)),
        connectionContext = HttpConnection.getContext(invokerProtocol))
      .map { response =>
        response.status shouldBe StatusCodes.OK
        val prewarmContainerDataList =
          Unmarshal(response).to[String].futureValue.parseJson.convertTo[PrewarmContainerDataList]
        val nodejs10ContainerData = prewarmContainerDataList.items.filter { prewarmContainerData =>
          prewarmContainerData.kind == kind && prewarmContainerData.memory == memory
        }
        nodejs10ContainerData.head.number shouldBe initialCount
      }
  }
}
