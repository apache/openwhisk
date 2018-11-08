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

package org.apache.openwhisk.core.controller.test

import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfterEach
import org.scalatest.junit.JUnitRunner

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.model.Uri

import spray.json._
import spray.json.DefaultJsonProtocol._

import org.apache.openwhisk.core.controller.SwaggerDocs

/**
 * Tests swagger routes.
 *
 * @Idioglossia
 * "using Specification DSL to write unit tests, as in should, must, not, be"
 * "using Specs2RouteTest DSL to chain HTTP requests for unit testing, as in ~>"
 */

@RunWith(classOf[JUnitRunner])
class SwaggerRoutesTests extends ControllerTestCommon with BeforeAndAfterEach {

  behavior of "Swagger routes"

  it should "server docs" in {
    implicit val tid = transid()
    val swagger = new SwaggerDocs(Uri.Path.Empty, "infoswagger.json")
    Get("/docs") ~> Route.seal(swagger.swaggerRoutes) ~> check {
      status shouldBe PermanentRedirect
      header("location").get.value shouldBe "docs/index.html?url=/api-docs"
    }

    Get("/api-docs") ~> Route.seal(swagger.swaggerRoutes) ~> check {
      status shouldBe OK
      responseAs[JsObject].fields("swagger") shouldBe JsString("2.0")
    }
  }
}
