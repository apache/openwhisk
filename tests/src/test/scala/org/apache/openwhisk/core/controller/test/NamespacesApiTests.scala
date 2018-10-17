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
import org.scalatest.junit.JUnitRunner

import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.model.StatusCodes.NotFound
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.sprayJsonUnmarshaller
import akka.http.scaladsl.server.Route

import spray.json.DefaultJsonProtocol._

import org.apache.openwhisk.core.controller.WhiskNamespacesApi
import org.apache.openwhisk.core.entity.EntityPath

/**
 * Tests Namespaces API.
 *
 * Unit tests of the controller service as a standalone component.
 * These tests exercise a fresh instance of the service object in memory -- these
 * tests do NOT communication with a whisk deployment.
 *
 *
 * @Idioglossia
 * "using Specification DSL to write unit tests, as in should, must, not, be"
 * "using Specs2RouteTest DSL to chain HTTP requests for unit testing, as in ~>"
 */
@RunWith(classOf[JUnitRunner])
class NamespacesApiTests extends ControllerTestCommon with WhiskNamespacesApi {

  /** Triggers API tests */
  behavior of "Namespaces API"

  val collectionPath = s"/${collection.path}"
  val creds = WhiskAuthHelpers.newIdentity()
  val namespace = EntityPath(creds.subject.asString)

  it should "list namespaces for subject" in {
    implicit val tid = transid()
    Seq("", "/").foreach { p =>
      Get(collectionPath + p) ~> Route.seal(routes(creds)) ~> check {
        status should be(OK)
        val ns = responseAs[List[EntityPath]]
        ns should be(List(EntityPath(creds.subject.asString)))
      }
    }
  }

  it should "reject request for unsupported method" in {
    implicit val tid = transid()
    Seq(Get, Put, Post, Delete).foreach { m =>
      m(s"$collectionPath/${creds.subject}") ~> Route.seal(routes(creds)) ~> check {
        status should be(NotFound)
      }
    }
  }
}
