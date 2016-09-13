/*
 * Copyright 2015-2016 IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.core.controller.test

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import spray.http.StatusCodes.OK
import spray.http.StatusCodes.Forbidden
import spray.http.StatusCodes.MethodNotAllowed
import spray.httpx.SprayJsonSupport.sprayJsonUnmarshaller
import spray.json.DefaultJsonProtocol.RootJsObjectFormat
import spray.json.DefaultJsonProtocol.listFormat
import spray.json.JsObject
import whisk.core.controller.WhiskNamespacesApi
import whisk.core.entity.AuthKey
import whisk.core.entity.EntityPath
import whisk.core.entity.Subject
import whisk.core.entity.WhiskAuth
import spray.json.JsObject
import whisk.core.controller.Namespaces
import spray.json.JsArray

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
    val creds = WhiskAuth(Subject(), AuthKey()).toIdentity
    val namespace = EntityPath(creds.subject())

    it should "list namespaces for subject" in {
        implicit val tid = transid()
        Get(collectionPath) ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val ns = responseAs[List[EntityPath]]
            ns should be(List(EntityPath(creds.subject())))
        }
    }

    it should "list namespaces for subject with trailing /" in {
        implicit val tid = transid()
        Get(s"$collectionPath/") ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val ns = responseAs[List[EntityPath]]
            ns should be(List(EntityPath(creds.subject())))
        }
    }

    it should "get namespace entities for subject" in {
        implicit val tid = transid()
        Get(s"$collectionPath/${creds.subject()}") ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val ns = responseAs[JsObject]
            ns should be(JsObject(Namespaces.emptyNamespace map { kv => (kv._1, JsArray()) }))
        }
    }

    it should "reject get namespace entities for unauthorized subject" in {
        implicit val tid = transid()
        val anothercred = WhiskAuth(Subject(), AuthKey())
        Get(s"$collectionPath/${anothercred.subject()}") ~> sealRoute(routes(creds)) ~> check {
            status should be(Forbidden)
        }
    }

    it should "reject request with put" in {
        implicit val tid = transid()
        Put(s"$collectionPath/${creds.subject()}") ~> sealRoute(routes(creds)) ~> check {
            status should be(MethodNotAllowed)
        }
    }

    it should "reject request with post" in {
        implicit val tid = transid()
        Post(s"$collectionPath/${creds.subject()}") ~> sealRoute(routes(creds)) ~> check {
            status should be(MethodNotAllowed)
        }
    }

    it should "reject request with delete" in {
        implicit val tid = transid()
        Delete(s"$collectionPath/${creds.subject()}") ~> sealRoute(routes(creds)) ~> check {
            status should be(MethodNotAllowed)
        }
    }
}
