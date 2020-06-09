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
import org.apache.openwhisk.core.entity.ByteSize
import org.apache.openwhisk.core.entity.size.SizeInt
import org.junit.runner.RunWith
import org.scalatest.Matchers
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner
import pureconfig.loadConfigOrThrow
import system.rest.RestUtil

@RunWith(classOf[JUnitRunner])
class UserMemoryConfigurationTests
    extends TestHelpers
    with RestUtil
    with Matchers
    with ScalaFutures
    with WskActorSystem
    with StreamLogging {

  implicit val materializer = ActorMaterializer()

  val invokerProtocol = loadConfigOrThrow[String]("whisk.invoker.protocol")
  val invokerAddress = WhiskProperties.getBaseInvokerAddress

  val controllerProtocol = loadConfigOrThrow[String]("whisk.controller.protocol")
  val controllerAddress = WhiskProperties.getBaseControllerAddress
  private val controllerUsername = loadConfigOrThrow[String]("whisk.controller.username")
  private val controllerPassword = loadConfigOrThrow[String]("whisk.controller.password")
  val controllerAuthHeader = Authorization(BasicHttpCredentials(controllerUsername, controllerPassword))
  private val invokerUsername = loadConfigOrThrow[String]("whisk.invoker.username")
  private val invokerPassword = loadConfigOrThrow[String]("whisk.invoker.password")
  val invokerAuthHeader = Authorization(BasicHttpCredentials(invokerUsername, invokerPassword))

  val getUserMemoryUrl = s"${invokerProtocol}://${invokerAddress}/config/memory"
  val controllerChangeUserMemoryUrl = s"${controllerProtocol}://${controllerAddress}/config/memory"

  it should "change invoker's user memory" in {
    Http()
      .singleRequest(
        HttpRequest(method = HttpMethods.GET, uri = s"${getUserMemoryUrl}"),
        connectionContext = HttpConnection.getContext(invokerProtocol))
      .map { response =>
        response.status shouldBe StatusCodes.OK
        val orignalUserMemory = ByteSize.fromString(Unmarshal(response).to[String].futureValue)
        val changedUserMemory = orignalUserMemory + 128.MB
        Http()
          .singleRequest(
            HttpRequest(
              method = HttpMethods.POST,
              uri = s"${controllerChangeUserMemoryUrl}",
              headers = List(controllerAuthHeader),
              entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, changedUserMemory.toString)),
            connectionContext = HttpConnection.getContext(controllerProtocol))
          .map { response =>
            response.status shouldBe StatusCodes.Accepted

            // Make sure the user memory's configuration is changed
            Thread.sleep(2000)

            Http()
              .singleRequest(
                HttpRequest(method = HttpMethods.GET, uri = s"${getUserMemoryUrl}", headers = List(invokerAuthHeader)),
                connectionContext = HttpConnection.getContext(invokerProtocol))
              .map { response =>
                response.status shouldBe StatusCodes.OK
                ByteSize
                  .fromString(Unmarshal(response).to[String].futureValue)
                  .toBytes shouldBe changedUserMemory.toBytes
              }
          }
      }
  }
}
