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

import java.time.Instant

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import spray.http.StatusCodes.BadRequest
import spray.http.StatusCodes.NotFound
import spray.http.StatusCodes.OK
import spray.httpx.SprayJsonSupport.sprayJsonMarshaller
import spray.httpx.SprayJsonSupport.sprayJsonUnmarshaller
import spray.json.DefaultJsonProtocol.RootJsObjectFormat
import spray.json.DefaultJsonProtocol.listFormat
import spray.json.DefaultJsonProtocol.RootJsObjectFormat
import spray.json.DefaultJsonProtocol.StringJsonFormat
import spray.json.JsObject
import spray.json.JsString
import spray.json.pimpAny
import spray.json.pimpString
import whisk.core.controller.WhiskTriggersApi
import whisk.core.entity.ActivationId
import whisk.core.entity.AuthKey
import whisk.core.entity.DocId
import whisk.core.entity.Namespace
import whisk.core.entity.Parameters
import whisk.core.entity.Subject
import whisk.core.entity.WhiskActivation
import whisk.core.entity.WhiskAuth
import whisk.core.entity.WhiskEntity
import whisk.core.entity.WhiskTrigger
import whisk.core.entity.WhiskTriggerPut

/**
 * Tests Trigger API.
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
class TriggersApiTests extends ControllerTestCommon with WhiskTriggersApi {

    /** Triggers API tests */
    behavior of "Triggers API"

    val creds = WhiskAuth(Subject(), AuthKey())
    val namespace = Namespace(creds.subject())
    val collectionPath = s"/${Namespace.DEFAULT}/${collection.path}"
    def aname = MakeName.next("triggers_tests")

    //// GET /triggers
    it should "list triggers by default namespace" in {
        implicit val tid = transid()
        val triggers = (1 to 2).map { i =>
            WhiskTrigger(namespace, aname, Parameters("x", "b"))
        }.toList
        triggers foreach { put(entityStore, _) }
        waitOnView(entityStore, WhiskTrigger, namespace, 2)
        Get(s"$collectionPath") ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[List[JsObject]]
            triggers.length should be(response.length)
            triggers forall { a => response contains a.summaryAsJson } should be(true)
        }
    }

    // ?docs disabled
    ignore should "list triggers by default namespace with full docs" in {
        implicit val tid = transid()
        val triggers = (1 to 2).map { i =>
            WhiskTrigger(namespace, aname, Parameters("x", "b"))
        }.toList
        triggers foreach { put(entityStore, _) }
        waitOnView(entityStore, WhiskTrigger, namespace, 2)
        Get(s"$collectionPath?docs=true") ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[List[WhiskTrigger]]
            triggers.length should be(response.length)
            triggers forall { a => response contains a } should be(true)
        }
    }

    //// GET /triggers/name
    it should "get trigger by name in default namespace" in {
        implicit val tid = transid()
        val trigger = WhiskTrigger(namespace, aname, Parameters("x", "b"))
        val name = trigger.name().replaceAll(" ", "%20")
        put(entityStore, trigger)
        Get(s"$collectionPath/$name") ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[WhiskTrigger]
            response should be(trigger)
        }
    }

    //// DEL /triggers/name
    it should "delete trigger by name" in {
        implicit val tid = transid()
        val trigger = WhiskTrigger(namespace, aname, Parameters("x", "b"))
        val name = trigger.name().replaceAll(" ", "%20")
        put(entityStore, trigger)
        Delete(s"$collectionPath/$name") ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[WhiskTrigger]
            response should be(trigger)
        }
    }

    //// PUT /triggers/name
    it should "put should accept request with missing optional properties" in {
        implicit val tid = transid()
        val trigger = WhiskTrigger(namespace, aname)
        val content = WhiskTriggerPut()
        Put(s"$collectionPath/${trigger.name}", content) ~> sealRoute(routes(creds)) ~> check {
            deleteTrigger(trigger.docid)
            status should be(OK)
            val response = responseAs[WhiskTrigger]
            response should be(trigger)
        }
    }

    it should "put should accept request with valid feed parameter" in {
        implicit val tid = transid()
        val trigger = WhiskTrigger(namespace, aname, annotations = Parameters(Parameters.Feed, "xyz"))
        val content = WhiskTriggerPut(annotations = Some(trigger.annotations))
        Put(s"$collectionPath/${trigger.name}", content) ~> sealRoute(routes(creds)) ~> check {
            deleteTrigger(trigger.docid)
            status should be(OK)
            val response = responseAs[WhiskTrigger]
            response should be(trigger)
        }
    }

    it should "put should reject request with undefined feed parameter" in {
        implicit val tid = transid()
        val trigger = WhiskTrigger(namespace, aname, annotations = Parameters(Parameters.Feed, ""))
        val content = WhiskTriggerPut(annotations = Some(trigger.annotations))
        Put(s"$collectionPath/${trigger.name}", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
        }
    }

    it should "put should reject request with bad feed parameters" in {
        implicit val tid = transid()
        val trigger = WhiskTrigger(namespace, aname, annotations = Parameters(Parameters.Feed, "a,b"))
        val content = WhiskTriggerPut(annotations = Some(trigger.annotations))
        Put(s"$collectionPath/${trigger.name}", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
        }
    }

    it should "put should accept update request with missing optional properties" in {
        implicit val tid = transid()
        val trigger = WhiskTrigger(namespace, aname, Parameters("x", "b"))
        val content = WhiskTriggerPut()
        put(entityStore, trigger)
        Put(s"$collectionPath/${trigger.name}?overwrite=true", content) ~> sealRoute(routes(creds)) ~> check {
            deleteTrigger(trigger.docid)
            status should be(OK)
            val response = responseAs[WhiskTrigger]
            response should be(WhiskTrigger(trigger.namespace, trigger.name, trigger.parameters, version = trigger.version.upPatch))
        }
    }

    it should "put should reject update request for trigger with existing feed" in {
        implicit val tid = transid()
        val trigger = WhiskTrigger(namespace, aname, annotations = Parameters(Parameters.Feed, "xyz"))
        val content = WhiskTriggerPut(annotations = Some(trigger.annotations))
        put(entityStore, trigger)
        Put(s"$collectionPath/${trigger.name}?overwrite=true", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
        }
    }

    it should "put should reject update request for trigger with new feed" in {
        implicit val tid = transid()
        val trigger = WhiskTrigger(namespace, aname)
        val content = WhiskTriggerPut(annotations = Some(Parameters(Parameters.Feed, "xyz")))
        put(entityStore, trigger)
        Put(s"$collectionPath/${trigger.name}?overwrite=true", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
        }
    }

    //// POST /triggers/name
    it should "fire a trigger" in {
        implicit val tid = transid()
        val trigger = WhiskTrigger(namespace, aname, Parameters("x", "b"))
        val content = JsObject("xxx" -> "yyy".toJson)
        put(entityStore, trigger)
        Post(s"$collectionPath/${trigger.name}", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[JsObject]
            val JsString(id) = response.fields("activationId")
            val activationId = ActivationId(id)
            response.fields("activationId") should not be None

            val activationDoc = DocId(WhiskEntity.qualifiedName(namespace, activationId)).asDocInfo
            val activation = get(activationStore, activationDoc, WhiskActivation, garbageCollect = false)
            del(entityStore, DocId(WhiskEntity.qualifiedName(namespace, activationId)), WhiskActivation)
            activation.end should be(Instant.EPOCH)
            activation.response.result should be(Some(content))
        }
    }

    it should "fire a trigger without args" in {
        implicit val tid = transid()
        val trigger = WhiskTrigger(namespace, aname, Parameters("x", "b"))
        put(entityStore, trigger)
        Post(s"$collectionPath/${trigger.name}") ~> sealRoute(routes(creds)) ~> check {
            val response = responseAs[JsObject]
            val JsString(id) = response.fields("activationId")
            val activationId = ActivationId(id)
            del(entityStore, DocId(WhiskEntity.qualifiedName(namespace, activationId)), WhiskActivation)
            response.fields("activationId") should not be None
        }
    }

    //// invalid resource
    it should "reject invalid resource" in {
        implicit val tid = transid()
        val trigger = WhiskTrigger(namespace, aname)
        put(entityStore, trigger)
        Get(s"$collectionPath/${trigger.name}/bar") ~> sealRoute(routes(creds)) ~> check {
            status should be(NotFound)
        }
    }
}
