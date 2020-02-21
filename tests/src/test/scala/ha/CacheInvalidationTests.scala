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

package ha

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Authorization
import akka.http.scaladsl.model.headers.BasicHttpCredentials
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import common.WhiskProperties
import common.WskActorSystem
import common.WskTestHelpers
import common.rest.HttpConnection
import spray.json._
import spray.json.DefaultJsonProtocol._
import pureconfig._

@RunWith(classOf[JUnitRunner])
class CacheInvalidationTests extends FlatSpec with Matchers with WskTestHelpers with WskActorSystem {

  implicit val materializer = ActorMaterializer()

  val hosts = WhiskProperties.getProperty("controller.hosts").split(",")

  val controllerProtocol = loadConfigOrThrow[String]("whisk.controller.protocol")
  val connectionContext = HttpConnection.getContext(controllerProtocol)

  def ports(instance: Int) = WhiskProperties.getControllerBasePort + instance

  def controllerUri(instance: Int) = {
    require(instance >= 0 && instance < hosts.length, "Controller instance not known.")
    Uri()
      .withScheme(controllerProtocol)
      .withHost(hosts(instance))
      .withPort(ports(instance))
  }

  def actionPath(name: String) = Uri.Path(s"/api/v1/namespaces/_/actions/$name")

  val Array(username, password) = WhiskProperties.readAuthKey(WhiskProperties.getAuthFileForTesting).split(":")
  val authHeader = Authorization(BasicHttpCredentials(username, password))

  val timeout = 15.seconds

  def retry[T](fn: => T) = org.apache.openwhisk.utils.retry(fn, 15, Some(1.second))

  def updateAction(name: String, code: String, controllerInstance: Int = 0) = {
    val body = JsObject(
      "namespace" -> JsString("_"),
      "name" -> JsString(name),
      "exec" -> JsObject("kind" -> JsString("nodejs:default"), "code" -> JsString(code)))

    val request = Marshal(body).to[RequestEntity].flatMap { entity =>
      Http()
        .singleRequest(
          HttpRequest(
            method = HttpMethods.PUT,
            uri = controllerUri(controllerInstance)
              .withPath(actionPath(name))
              .withQuery(Uri.Query("overwrite" -> true.toString)),
            headers = List(authHeader),
            entity = entity),
          connectionContext = connectionContext)
        .flatMap { response =>
          Unmarshal(response).to[JsObject].map { resBody =>
            withClue(s"Error in Body: $resBody")(response.status shouldBe StatusCodes.OK)
            resBody
          }
        }
    }

    Await.result(request, timeout)
  }

  def getAction(name: String, controllerInstance: Int = 0, expectedCode: StatusCode = StatusCodes.OK) = {
    val request = Http()
      .singleRequest(
        HttpRequest(
          method = HttpMethods.GET,
          uri = controllerUri(controllerInstance).withPath(actionPath(name)),
          headers = List(authHeader)),
        connectionContext = connectionContext)
      .flatMap { response =>
        Unmarshal(response).to[JsObject].map { resBody =>
          withClue(s"Wrong statuscode from controller. Body is: $resBody")(response.status shouldBe expectedCode)
          resBody
        }
      }

    Await.result(request, timeout)
  }

  def deleteAction(name: String,
                   controllerInstance: Int = 0,
                   expectedCode: Option[StatusCode] = Some(StatusCodes.OK)) = {
    val request = Http()
      .singleRequest(
        HttpRequest(
          method = HttpMethods.DELETE,
          uri = controllerUri(controllerInstance).withPath(actionPath(name)),
          headers = List(authHeader)),
        connectionContext = connectionContext)
      .flatMap { response =>
        Unmarshal(response).to[JsObject].map { resBody =>
          expectedCode.map { code =>
            withClue(s"Wrong statuscode from controller. Body is: $resBody")(response.status shouldBe code)
          }
          resBody
        }
      }

    Await.result(request, timeout)
  }

  behavior of "The cache"

  if (WhiskProperties.getControllerInstances >= 2) {

    it should "be invalidated on updating an entity" in {
      val actionName = "invalidateRemoteCacheOnUpdate"

      deleteAction(actionName, 0, None)
      deleteAction(actionName, 1, None)

      try {
        // Create an action on controller0
        val createdAction = updateAction(actionName, "CODE_CODE_CODE", 0)

        // Get action from controller1
        val actionFromController1 = getAction(actionName, 1)
        createdAction shouldBe actionFromController1

        // Update the action on controller0
        val updatedAction = updateAction(actionName, "CODE_CODE", 0)

        retry({
          // Get action from controller1
          val updatedActionFromController1 = getAction(actionName, 1)
          updatedAction shouldBe updatedActionFromController1
        })
      } finally {
        deleteAction(actionName, 0, None)
      }
    }

    it should "be invalidated on deleting an entity" in {
      val actionName = "invalidateRemoteCacheOnDelete"

      deleteAction(actionName, 0, None)
      deleteAction(actionName, 1, None)

      // Create an action on controller0
      val createdAction = updateAction(actionName, "CODE_CODE_CODE", 0)
      // Get action from controller1 (Now its in the cache of controller 0 and 1)
      val actionFromController1 = getAction(actionName, 1)
      createdAction shouldBe actionFromController1

      // Delete the action on controller0 (It should be deleted automatically from the cache of controller1)
      val updatedAction = deleteAction(actionName, 0)

      retry({
        // Get action from controller1 should fail with 404
        getAction(actionName, 1, StatusCodes.NotFound)
      })
    }
  }
}
