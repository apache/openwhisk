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

import scala.language.postfixOps
import scala.concurrent.duration.DurationInt

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import spray.http.StatusCodes.Accepted
import spray.http.StatusCodes.BadRequest
import spray.http.StatusCodes.Conflict
import spray.http.StatusCodes.Forbidden
import spray.http.StatusCodes.InternalServerError
import spray.http.StatusCodes.MethodNotAllowed
import spray.http.StatusCodes.NotFound
import spray.http.StatusCodes.OK
import spray.http.StatusCodes.RequestEntityTooLarge
import spray.httpx.SprayJsonSupport.sprayJsonMarshaller
import spray.httpx.SprayJsonSupport.sprayJsonUnmarshaller
import spray.json.DefaultJsonProtocol.RootJsObjectFormat
import spray.json.DefaultJsonProtocol.listFormat
import spray.json.DefaultJsonProtocol.StringJsonFormat
import spray.json.DefaultJsonProtocol.vectorFormat
import spray.json.JsObject
import spray.json.pimpAny
import spray.json.pimpString
import whisk.core.controller.WhiskActionsApi
import whisk.core.entity.ActionLimits
import whisk.core.entity.ActionLimitsOption
import whisk.core.entity.ActivationResponse
import whisk.core.entity.AuthKey
import whisk.core.entity.Exec
import whisk.core.entity.MemoryLimit
import whisk.core.entity.LogLimit
import whisk.core.entity.EntityPath
import whisk.core.entity.Parameters
import whisk.core.entity.Subject
import whisk.core.entity.TimeLimit
import whisk.core.entity.WhiskAction
import whisk.core.entity.WhiskActionPut
import whisk.core.entity.WhiskActivation
import whisk.core.entity.WhiskAuth
import java.time.Instant
import akka.event.Logging.InfoLevel
import whisk.core.entity.WhiskTrigger

/**
 * Tests Actions API.
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
class ActionsApiTests extends ControllerTestCommon with WhiskActionsApi {

    /** Actions API tests */
    behavior of "Actions API"

    val creds = WhiskAuth(Subject(), AuthKey()).toIdentity
    val namespace = EntityPath(creds.subject())
    val collectionPath = s"/${EntityPath.DEFAULT}/${collection.path}"
    def aname = MakeName.next("action_tests")
    setVerbosity(InfoLevel)
    val entityTooBigRejectionMessage = "request entity too large"
    val actionLimit = Exec.sizeLimit
    val parametersLimit = Parameters.sizeLimit

    //// GET /actions
    it should "list actions by default namespace" in {
        implicit val tid = transid()
        val actions = (1 to 2).map { i =>
            WhiskAction(namespace, aname, Exec.js("??"), Parameters("x", "b"))
        }.toList
        actions foreach { put(entityStore, _) }
        waitOnView(entityStore, WhiskAction, namespace, 2)
        Get(s"$collectionPath") ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[List[JsObject]]
            actions.length should be(response.length)
            actions forall { a => response contains a.summaryAsJson } should be(true)
        }
    }

    // ?docs disabled
    ignore should "list action by default namespace with full docs" in {
        implicit val tid = transid()
        val actions = (1 to 2).map { i =>
            WhiskAction(namespace, aname, Exec.js("??"), Parameters("x", "b"))
        }.toList
        actions foreach { put(entityStore, _) }
        waitOnView(entityStore, WhiskAction, namespace, 2)
        Get(s"$collectionPath?docs=true") ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[List[WhiskAction]]
            actions.length should be(response.length)
            actions forall { a => response contains a } should be(true)
        }
    }

    it should "list action with explicit namespace" in {
        implicit val tid = transid()
        val actions = (1 to 2).map { i =>
            WhiskAction(namespace, aname, Exec.js("??"), Parameters("x", "b"))
        }.toList
        actions foreach { put(entityStore, _) }
        waitOnView(entityStore, WhiskAction, namespace, 2)
        Get(s"/$namespace/${collection.path}") ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[List[JsObject]]
            actions.length should be(response.length)
            actions forall { a => response contains a.summaryAsJson } should be(true)
        }

        // it should "reject list action with explicit namespace not owned by subject" in {
        val auser = WhiskAuth(Subject(), AuthKey()).toIdentity
        Get(s"/$namespace/${collection.path}") ~> sealRoute(routes(auser)) ~> check {
            status should be(Forbidden)
        }
    }

    it should "list should reject request with post" in {
        implicit val tid = transid()
        Post(s"$collectionPath") ~> sealRoute(routes(creds)) ~> check {
            status should be(MethodNotAllowed)
        }
    }

    //// GET /actions/name
    it should "get action by name in default namespace" in {
        implicit val tid = transid()
        val action = WhiskAction(namespace, aname, Exec.js("??"), Parameters("x", "b"))
        put(entityStore, action)
        Get(s"$collectionPath/${action.name}") ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[WhiskAction]
            response should be(action)
        }
    }

    it should "get action by name in explicit namespace" in {
        implicit val tid = transid()
        val action = WhiskAction(namespace, aname, Exec.js("??"), Parameters("x", "b"))
        put(entityStore, action)
        Get(s"/$namespace/${collection.path}/${action.name}") ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[WhiskAction]
            response should be(action)
        }

        // it should "reject get action by name in explicit namespace not owned by subject" in
        val auser = WhiskAuth(Subject(), AuthKey()).toIdentity
        Get(s"/$namespace/${collection.path}/${action.name}") ~> sealRoute(routes(auser)) ~> check {
            status should be(Forbidden)
        }
    }

    it should "report NotFound for get non existent action" in {
        implicit val tid = transid()
        Get(s"$collectionPath/xyz") ~> sealRoute(routes(creds)) ~> check {
            status should be(NotFound)
        }
    }

    it should "report Conflict if the name was of a different type" in {
        implicit val tid = transid()
        val trigger = WhiskTrigger(namespace, aname)
        put(entityStore, trigger)
        Get(s"/$namespace/${collection.path}/${trigger.name}") ~> sealRoute(routes(creds)) ~> check {
            status should be(Conflict)
        }
    }

    //// DEL /actions/name
    it should "delete action by name" in {
        implicit val tid = transid()
        val action = WhiskAction(namespace, aname, Exec.js("??"), Parameters("x", "b"))
        put(entityStore, action)

        // it should "reject delete action by name not owned by subject" in
        val auser = WhiskAuth(Subject(), AuthKey()).toIdentity
        Get(s"/$namespace/${collection.path}/${action.name}") ~> sealRoute(routes(auser)) ~> check {
            status should be(Forbidden)
        }

        Delete(s"$collectionPath/${action.name}") ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[WhiskAction]
            response should be(action)
        }
    }

    it should "report NotFound for delete non existent action" in {
        implicit val tid = transid()
        Delete(s"$collectionPath/xyz") ~> sealRoute(routes(creds)) ~> check {
            status should be(NotFound)
        }
    }

    //// PUT /actions/name
    it should "put should reject request missing json content" in {
        implicit val tid = transid()
        Put(s"$collectionPath/xxx", "") ~> sealRoute(routes(creds)) ~> check {
            val response = responseAs[String]
            status should be(BadRequest)
        }
    }

    it should "put should reject request missing property exec" in {
        implicit val tid = transid()
        val content = """|{"name":"name","publish":true}""".stripMargin.parseJson.asJsObject
        Put(s"$collectionPath/xxx", content) ~> sealRoute(routes(creds)) ~> check {
            val response = responseAs[String]
            status should be(BadRequest)
        }
    }

    it should "put should reject request with malformed property exec" in {
        implicit val tid = transid()
        val content = """|{"name":"name",
                         |"publish":true,
                         |"exec":""}""".stripMargin.parseJson.asJsObject
        Put(s"$collectionPath/xxx", content) ~> sealRoute(routes(creds)) ~> check {
            val response = responseAs[String]
            status should be(BadRequest)
        }
    }

    it should "reject create with exec which is too big" in {
        implicit val tid = transid()
        val code = "a" * ((actionLimit.toBytes / 2L).toInt + 1)
        val content = s"""{"exec":{"kind":"python","code":"$code"}}""".stripMargin.parseJson.asJsObject
        Put(s"$collectionPath/${aname}", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(RequestEntityTooLarge)
            response.entity.toString should include(entityTooBigRejectionMessage)
        }
    }

    it should "reject update with exec which is too big" in {
        implicit val tid = transid()
        val oldCode = "function main()"
        val code = "a" * ((actionLimit.toBytes / 2L).toInt + 1)
        val action = WhiskAction(namespace, aname, Exec.js("??"))
        val content = s"""{"exec":{"kind":"python","code":"$code"}}""".stripMargin.parseJson.asJsObject
        put(entityStore, action)
        Put(s"$collectionPath/${action.name}?overwrite=true", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(RequestEntityTooLarge)
            response.entity.toString should include(entityTooBigRejectionMessage)
        }
    }

    it should "reject create with parameters which are too big" in {
        implicit val tid = transid()
        val keys: List[Long] = List.range(Math.pow(10, 9) toLong, (parametersLimit.toBytes / 2 / 20 + Math.pow(10, 9) + 2) toLong)
        val parameters = keys map { key =>
            Parameters(key.toString, "a" * 10)
        } reduce (_ ++ _)
        val content = s"""{"exec":{"kind":"nodejs","code":"??"},"parameters":$parameters}""".stripMargin.parseJson.asJsObject
        Put(s"$collectionPath/${aname}", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(RequestEntityTooLarge)
            response.entity.toString should include(entityTooBigRejectionMessage)
        }
    }

    it should "reject create with annotations which are too big" in {
        implicit val tid = transid()
        val keys: List[Long] = List.range(Math.pow(10, 9) toLong, (parametersLimit.toBytes / 2 / 20 + Math.pow(10, 9) + 2) toLong)
        val parameters = keys map { key =>
            Parameters(key.toString, "a" * 10)
        } reduce (_ ++ _)
        val content = s"""{"exec":{"kind":"nodejs","code":"??"},"annotations":$parameters}""".stripMargin.parseJson.asJsObject
        Put(s"$collectionPath/${aname}", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(RequestEntityTooLarge)
            response.entity.toString should include(entityTooBigRejectionMessage)
        }
    }

    it should "put should accept request with missing optional properties" in {
        implicit val tid = transid()
        val action = WhiskAction(namespace, aname, Exec.js("??"))
        val content = WhiskActionPut(Some(action.exec))
        Put(s"$collectionPath/${action.name}", content) ~> sealRoute(routes(creds)) ~> check {
            deleteAction(action.docid)
            status should be(OK)
            val response = responseAs[WhiskAction]
            response should be(action)
        }
    }

    private def seqParameters(seq: Vector[String]) = Parameters("_actions", seq.toJson)

    // this test is sneaky; the installation of the sequence is done directly in the db
    // and api checks are skipped
    it should "reset parameters when changing sequence action to non sequence" in {
        implicit val tid = transid()
        val sequence = Vector("x/a", "x/b")
        val action = WhiskAction(namespace, aname, Exec.sequence(sequence), seqParameters(sequence))
        val content = WhiskActionPut(Some(Exec.js("")))
        put(entityStore, action, false)

        // create an action sequence
        Put(s"$collectionPath/${action.name}?overwrite=true", content) ~> sealRoute(routes(creds)) ~> check {
            deleteAction(action.docid)
            status should be(OK)
            val response = responseAs[WhiskAction]
            response.exec.kind should be(Exec.NODEJS)
            response.parameters shouldBe Parameters()
        }
    }

    // this test is sneaky; the installation of the sequence is done directly in the db
    // and api checks are skipped
    it should "preserve new parameters when changing sequence action to non sequence" in {
        implicit val tid = transid()
        val sequence = Vector("x/a", "x/b")
        val action = WhiskAction(namespace, aname, Exec.sequence(sequence), seqParameters(sequence))
        val content = WhiskActionPut(Some(Exec.js("")), parameters = Some(Parameters("a", "A")))
        put(entityStore, action, false)

        // create an action sequence
        Put(s"$collectionPath/${action.name}?overwrite=true", content) ~> sealRoute(routes(creds)) ~> check {
            deleteAction(action.docid)
            status should be(OK)
            val response = responseAs[WhiskAction]
            response.exec.kind should be(Exec.NODEJS)
            response.parameters should be(Parameters("a", "A"))
        }
    }

    it should "put should accept request with parameters property" in {
        implicit val tid = transid()
        val action = WhiskAction(namespace, aname, Exec.js("??"), Parameters("x", "b"))
        val content = WhiskActionPut(Some(action.exec), Some(action.parameters))

        // it should "reject put action in namespace not owned by subject" in
        val auser = WhiskAuth(Subject(), AuthKey()).toIdentity
        Put(s"/$namespace/${collection.path}/${action.name}", content) ~> sealRoute(routes(auser)) ~> check {
            status should be(Forbidden)
        }

        Put(s"$collectionPath/${action.name}", content) ~> sealRoute(routes(creds)) ~> check {
            deleteAction(action.docid)
            status should be(OK)
            val response = responseAs[WhiskAction]
            response should be(action)
        }
    }

    it should "put should reject request with parameters property as jsobject" in {
        implicit val tid = transid()
        val action = WhiskAction(namespace, aname, Exec.js("??"), Parameters("x", "b"))
        val content = WhiskActionPut(Some(action.exec), Some(action.parameters))
        val params = """{ "parameters": { "a": "b" } }""".parseJson.asJsObject
        val json = JsObject(WhiskActionPut.serdes.write(content).asJsObject.fields ++ params.fields)
        Put(s"$collectionPath/${action.name}", json) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
        }
    }

    it should "put should accept request with limits property" in {
        implicit val tid = transid()
        val action = WhiskAction(namespace, aname, Exec.js("??"), Parameters("x", "b"))
        val content = WhiskActionPut(Some(action.exec), Some(action.parameters), Some(ActionLimitsOption(Some(action.limits.timeout), Some(action.limits.memory), Some(action.limits.logs))))
        Put(s"$collectionPath/${action.name}", content) ~> sealRoute(routes(creds)) ~> check {
            deleteAction(action.docid)
            status should be(OK)
            val response = responseAs[WhiskAction]
            response should be(action)
        }
    }

    it should "put and then get action from cache" in {
        val action = WhiskAction(namespace, aname, Exec.js("??"), Parameters("x", "b"))
        val content = WhiskActionPut(Some(action.exec), Some(action.parameters), Some(ActionLimitsOption(Some(action.limits.timeout), Some(action.limits.memory), Some(action.limits.logs))))
        val name = action.name

        val stream = new ByteArrayOutputStream
        val printstream = new PrintStream(stream)
        val savedstream = authStore.outputStream
        entityStore.outputStream = printstream
        try {
            // first request invalidates any previous entries and caches new result
            Put(s"$collectionPath/$name", content) ~> sealRoute(routes(creds)(transid())) ~> check {
                status should be(OK)
                val response = responseAs[WhiskAction]
                response should be(action)
            }
            stream.toString should include regex (s"caching*.*${action.docid.asDocInfo}")
            stream.reset()

            // second request should fetch from cache
            Get(s"$collectionPath/$name") ~> sealRoute(routes(creds)(transid())) ~> check {
                status should be(OK)
                val response = responseAs[WhiskAction]
                response should be(action)
            }

            stream.toString should include regex (s"serving from cache:*.*${action.docid.asDocInfo}")
            stream.reset()

            // delete should invalidate cache
            Delete(s"$collectionPath/$name") ~> sealRoute(routes(creds)(transid())) ~> check {
                status should be(OK)
                val response = responseAs[WhiskAction]
                response should be(action)
            }
            stream.toString should include regex (s"invalidating*.*${action.docid.asDocInfo}")
            stream.reset()
        } finally {
            entityStore.outputStream = savedstream
            stream.close()
            printstream.close()
        }
    }

    it should "reject put with conflict for pre-existing action" in {
        implicit val tid = transid()
        val action = WhiskAction(namespace, aname, Exec.js("??"), Parameters("x", "b"))
        val content = WhiskActionPut(Some(action.exec))
        put(entityStore, action)
        Put(s"$collectionPath/${action.name}", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(Conflict)
        }
    }

    it should "update action with a put" in {
        implicit val tid = transid()
        val action = WhiskAction(namespace, aname, Exec.js("??"), Parameters("x", "b"))
        val content = WhiskActionPut(Some(Exec.js("_")), Some(Parameters("x", "X")))
        put(entityStore, action)
        Put(s"$collectionPath/${action.name}?overwrite=true", content) ~> sealRoute(routes(creds)) ~> check {
            deleteAction(action.docid)
            status should be(OK)
            val response = responseAs[WhiskAction]
            response should be {
                WhiskAction(action.namespace, action.name, content.exec.get, content.parameters.get, version = action.version.upPatch)
            }
        }
    }

    it should "update action parameters with a put" in {
        implicit val tid = transid()
        val action = WhiskAction(namespace, aname, Exec.js("??"), Parameters("x", "b"))
        val content = WhiskActionPut(parameters = Some(Parameters("x", "X")))
        put(entityStore, action)
        Put(s"$collectionPath/${action.name}?overwrite=true", content) ~> sealRoute(routes(creds)) ~> check {
            deleteAction(action.docid)
            status should be(OK)
            val response = responseAs[WhiskAction]
            response should be {
                WhiskAction(action.namespace, action.name, action.exec, content.parameters.get, version = action.version.upPatch)
            }
        }
    }

    //// POST /actions/name
    it should "invoke an action with arguments, nonblocking" in {
        implicit val tid = transid()
        val action = WhiskAction(namespace, aname, Exec.js("??"), Parameters("x", "b"))
        val args = JsObject("xxx" -> "yyy".toJson)
        put(entityStore, action)

        // it should "reject post to action in namespace not owned by subject"
        val auser = WhiskAuth(Subject(), AuthKey()).toIdentity
        Post(s"/$namespace/${collection.path}/${action.name}", args) ~> sealRoute(routes(auser)) ~> check {
            status should be(Forbidden)
        }

        Post(s"$collectionPath/${action.name}", args) ~> sealRoute(routes(creds)) ~> check {
            status should be(Accepted)
            val response = responseAs[JsObject]
            response.fields("activationId") should not be None
        }

        // it should "ignore &result when invoking nonblocking action"
        Post(s"$collectionPath/${action.name}?result=true", args) ~> sealRoute(routes(creds)) ~> check {
            status should be(Accepted)
            val response = responseAs[JsObject]
            response.fields("activationId") should not be None
        }
    }

    it should "invoke an action, nonblocking" in {
        implicit val tid = transid()
        val action = WhiskAction(namespace, aname, Exec.js("??"))
        put(entityStore, action)
        Post(s"$collectionPath/${action.name}") ~> sealRoute(routes(creds)) ~> check {
            status should be(Accepted)
            val response = responseAs[JsObject]
            response.fields("activationId") should not be None
        }
    }

    it should "invoke an action, blocking with timeout" in {
        implicit val tid = transid()
        val action = WhiskAction(namespace, aname, Exec.js("??"), limits = ActionLimits(TimeLimit(1 second), MemoryLimit(), LogLimit()))
        put(entityStore, action)
        Post(s"$collectionPath/${action.name}?blocking=true") ~> sealRoute(routes(creds)) ~> check {
            // status shold be accepted because there is no active ack response and
            // db polling will fail since there is no record of the activation
            status should be(Accepted)
            val response = responseAs[JsObject]
            response.fields("activationId") should not be None
        }
    }

    it should "invoke an action, blocking and retrieve result via db polling" in {
        implicit val tid = transid()
        val action = WhiskAction(namespace, aname, Exec.js("??"))
        val activation = WhiskActivation(action.namespace, action.name, creds.subject, activationId.make(),
            start = Instant.now,
            end = Instant.now,
            response = ActivationResponse.success(Some(JsObject("test" -> "yes".toJson))))
        put(entityStore, action)
        // storing the activation in the db will allow the db polling to retrieve it
        // the test harness makes sure the activaiton id observed by the test matches
        // the one generated by the api handler
        put(activationStore, activation)
        try {
            Post(s"$collectionPath/${action.name}?blocking=true") ~> sealRoute(routes(creds)) ~> check {
                status should be(OK)
                val response = responseAs[JsObject]
                response should be(activation.toExtendedJson)
            }

            // repeat invoke, get only result back
            Post(s"$collectionPath/${action.name}?blocking=true&result=true") ~> sealRoute(routes(creds)) ~> check {
                status should be(OK)
                val response = responseAs[JsObject]
                response should be(activation.resultAsJson)
            }
        } finally {
            deleteActivation(activation.docid)
        }
    }

    it should "invoke an action, blocking and retrieve result via active ack" in {
        implicit val tid = transid()
        val action = WhiskAction(namespace, aname, Exec.js("??"))
        val activation = WhiskActivation(action.namespace, action.name, creds.subject, activationId.make(),
            start = Instant.now,
            end = Instant.now,
            response = ActivationResponse.success(Some(JsObject("test" -> "yes".toJson))))
        put(entityStore, action)

        try {
            // do not store the activation in the db, instead register it as the response to generate on active ack
            loadBalancer.whiskActivationStub = Some(activation)

            Post(s"$collectionPath/${action.name}?blocking=true") ~> sealRoute(routes(creds)) ~> check {
                status should be(OK)
                val response = responseAs[JsObject]
                response should be(activation.toExtendedJson)
            }

            // repeat invoke, get only result back
            Post(s"$collectionPath/${action.name}?blocking=true&result=true") ~> sealRoute(routes(creds)) ~> check {
                status should be(OK)
                val response = responseAs[JsObject]
                response should be(activation.resultAsJson)
            }
        } finally {
            loadBalancer.whiskActivationStub = None
        }
    }

    it should "invoke a blocking action and return error response when activation fails" in {
        implicit val tid = transid()
        val action = WhiskAction(namespace, aname, Exec.js("??"))
        val activation = WhiskActivation(action.namespace, action.name, creds.subject, activationId.make(),
            start = Instant.now,
            end = Instant.now,
            response = ActivationResponse.whiskError("test"))
        put(entityStore, action)
        // storing the activation in the db will allow the db polling to retrieve it
        // the test harness makes sure the activaiton id observed by the test matches
        // the one generated by the api handler
        put(activationStore, activation)
        try {
        Post(s"$collectionPath/${action.name}?blocking=true") ~> sealRoute(routes(creds)) ~> check {
            status should be(InternalServerError)
            val response = responseAs[JsObject]
            response should be(activation.toExtendedJson)
        }
        } finally {
            deleteActivation(activation.docid)
        }
    }

}
