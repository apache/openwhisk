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

import scala.concurrent.duration.DurationInt
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import spray.http.StatusCodes.Accepted
import spray.http.StatusCodes.BadRequest
import spray.http.StatusCodes.Forbidden
import spray.http.StatusCodes.Conflict
import spray.http.StatusCodes.NotFound
import spray.http.StatusCodes.OK
import spray.httpx.SprayJsonSupport.sprayJsonMarshaller
import spray.httpx.SprayJsonSupport.sprayJsonUnmarshaller
import spray.json.DefaultJsonProtocol.RootJsObjectFormat
import spray.json.DefaultJsonProtocol.listFormat
import spray.json.DefaultJsonProtocol.RootJsObjectFormat
import spray.json.DefaultJsonProtocol.StringJsonFormat
import spray.json.JsObject
import spray.json.pimpAny
import whisk.core.controller.WhiskActionsApi
import whisk.core.entity.ActionLimits
import whisk.core.entity.Exec
import whisk.core.entity.EntityPath
import whisk.core.entity.Parameters
import whisk.core.entity.WhiskAction
import whisk.core.entity.WhiskActionPut
import whisk.core.entity.AuthKey
import whisk.core.entity.WhiskAuth
import whisk.core.entity.Subject
import whisk.core.entity.WhiskPackage
import whisk.core.entitlement.Resource
import whisk.core.entitlement.Privilege._
import scala.concurrent.Await
import scala.language.postfixOps

/**
 * Tests Packages API.
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
class PackageActionsApiTests extends ControllerTestCommon with WhiskActionsApi {

    /** Package Actions API tests */
    behavior of "Package Actions API"

    val creds = WhiskAuth(Subject(), AuthKey())
    val namespace = EntityPath(creds.subject())
    val collectionPath = s"/${EntityPath.DEFAULT}/${collection.path}"
    def aname = MakeName.next("package_action_tests")

    //// GET /actions/package/
    it should "list all actions in package" in {
        implicit val tid = transid()
        val provider = WhiskPackage(namespace, aname)
        val actions = (1 to 2).map { _ =>
            WhiskAction(provider.path, aname, Exec.js("??"))
        }
        put(entityStore, provider)
        actions foreach { put(entityStore, _) }
        whisk.utils.retry {
            Get(s"$collectionPath/${provider.name}/") ~> sealRoute(routes(creds)) ~> check {
                status should be(OK)
                val response = responseAs[List[JsObject]]
                actions.length should be(response.length)
                actions forall { a => response contains a.summaryAsJson } should be(true)
            }
        }
    }

    it should "list all actions in package binding" in {
        implicit val tid = transid()
        val provider = WhiskPackage(namespace, aname)
        val reference = WhiskPackage(namespace, aname, provider.bind)
        val actions = (1 to 2).map { _ =>
            WhiskAction(provider.path, aname, Exec.js("??"))
        }
        put(entityStore, provider)
        put(entityStore, reference)
        actions foreach { put(entityStore, _) }
        whisk.utils.retry {
            Get(s"$collectionPath/${reference.name}/") ~> sealRoute(routes(creds)) ~> check {
                status should be(OK)
                val response = responseAs[List[JsObject]]
                actions.length should be(response.length)
                actions forall { a => response contains a.summaryAsJson } should be(true)
            }
        }
    }

    it should "include action in package when listing all actions" in {
        implicit val tid = transid()
        val provider = WhiskPackage(namespace, aname, None)
        val action1 = WhiskAction(namespace, aname, Exec.js("??"), Parameters(), ActionLimits())
        val action2 = WhiskAction(provider.path, aname, Exec.js("??"))
        put(entityStore, provider)
        put(entityStore, action1)
        put(entityStore, action2)
        whisk.utils.retry {
            Get(s"$collectionPath") ~> sealRoute(routes(creds)) ~> check {
                status should be(OK)
                val response = responseAs[List[JsObject]]
                response.length should be(2)
                response contains action1.summaryAsJson should be(true)
                response contains action2.summaryAsJson should be(true)
            }
        }
    }

    it should "reject ambiguous list actions in package without trailing slash" in {
        implicit val tid = transid()
        val provider = WhiskPackage(namespace, aname, None)
        put(entityStore, provider)
        whisk.utils.retry {
            Get(s"$collectionPath/${provider.name}") ~> sealRoute(routes(creds)) ~> check {
                status should be(Conflict)
            }
        }
    }

    it should "reject invalid verb on get package actions" in {
        implicit val tid = transid()
        val provider = WhiskPackage(namespace, aname, None)
        put(entityStore, provider)
        Delete(s"$collectionPath/${provider.name}/") ~> sealRoute(routes(creds)) ~> check {
            status should be(NotFound)
        }
    }

    //// PUT /actions/package/name
    it should "put action in package" in {
        implicit val tid = transid()
        val provider = WhiskPackage(namespace, aname)
        val action = WhiskAction(provider.path, aname, Exec.js("??"))
        val content = WhiskActionPut(Some(action.exec))
        put(entityStore, provider)
        Put(s"$collectionPath/${provider.name}/${action.name}", content) ~> sealRoute(routes(creds)) ~> check {
            deleteAction(action.docid)
            status should be(OK)
            val response = responseAs[WhiskAction]
            response should be(action)
        }
    }

    it should "reject put action in package that does not exist" in {
        implicit val tid = transid()
        val provider = WhiskPackage(namespace, aname)
        val action = WhiskAction(provider.path, aname, Exec.js("??"))
        val content = WhiskActionPut(Some(action.exec))
        Put(s"$collectionPath/${provider.name}/${action.name}", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(NotFound)
        }
    }

    it should "reject put action in package binding" in {
        implicit val tid = transid()
        val provider = WhiskPackage(namespace, aname, None, publish = true)
        val binding = WhiskPackage(namespace, aname, provider.bind)
        val content = WhiskActionPut(Some(Exec.js("??")))
        put(entityStore, binding)
        Put(s"$collectionPath/${binding.name}/$aname", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
        }
    }

    it should "reject put action in package owned by different subject" in {
        implicit val tid = transid()
        val provider = WhiskPackage(Subject().namespace, aname, publish = true)
        val content = WhiskActionPut(Some(Exec.js("??")))
        put(entityStore, provider)
        Put(s"/${provider.namespace}/${collection.path}/${provider.name}/$aname", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(Forbidden)
        }
    }

    //// DEL /actions/package/name
    it should "delete action in package" in {
        implicit val tid = transid()
        val provider = WhiskPackage(namespace, aname)
        val action = WhiskAction(provider.path, aname, Exec.js("??"))
        put(entityStore, provider)
        put(entityStore, action)

        // it should "reject delete action in package owned by different subject" in {
        val auser = WhiskAuth(Subject(), AuthKey())
        Delete(s"/${provider.namespace}/${collection.path}/${provider.name}/${action.name}") ~> sealRoute(routes(auser)) ~> check {
            status should be(Forbidden)
        }

        Delete(s"$collectionPath/${provider.name}/${action.name}") ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[WhiskAction]
            response should be(action)
        }
    }

    it should "reject delete action in package that does not exist" in {
        implicit val tid = transid()
        val provider = WhiskPackage(namespace, aname)
        val action = WhiskAction(provider.path, aname, Exec.js("??"))
        put(entityStore, action)
        Delete(s"$collectionPath/${provider.name}/${action.name}") ~> sealRoute(routes(creds)) ~> check {
            status should be(NotFound)
        }
    }

    it should "reject delete non-existent action in package" in {
        implicit val tid = transid()
        val provider = WhiskPackage(namespace, aname)
        val action = WhiskAction(provider.path, aname, Exec.js("??"))
        put(entityStore, provider)
        Delete(s"$collectionPath/${provider.name}/${action.name}") ~> sealRoute(routes(creds)) ~> check {
            status should be(NotFound)
        }
    }

    it should "reject delete action in package binding" in {
        implicit val tid = transid()
        val provider = WhiskPackage(namespace, aname, None, publish = true)
        val binding = WhiskPackage(namespace, aname, provider.bind)
        val content = WhiskActionPut(Some(Exec.js("??")))
        put(entityStore, binding)
        Delete(s"$collectionPath/${binding.name}/$aname") ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
        }
    }

    //// GET /actions/package/name
    it should "get action in package" in {
        implicit val tid = transid()
        val provider = WhiskPackage(namespace, aname, None, Parameters("p", "P"), publish = true)
        val action = WhiskAction(provider.path, aname, Exec.js("??"), Parameters("a", "A"))
        put(entityStore, provider)
        put(entityStore, action)
        whisk.utils.retry {
            Get(s"$collectionPath/${provider.name}/${action.name}") ~> sealRoute(routes(creds)) ~> check {
                status should be(OK)
                val response = responseAs[WhiskAction]
                response should be(action inherit provider.parameters)
            }
        }
    }

    it should "get action in package binding with public package" in {
        implicit val tid = transid()
        val auser = WhiskAuth(Subject(), AuthKey())
        val provider = WhiskPackage(namespace, aname, None, publish = true)
        val binding = WhiskPackage(EntityPath(auser.subject()), aname, provider.bind, Parameters("b", "B"))
        val action = WhiskAction(provider.path, aname, Exec.js("??"))
        put(entityStore, provider)
        put(entityStore, binding)
        put(entityStore, action)
        whisk.utils.retry {
            Get(s"$collectionPath/${binding.name}/${action.name}") ~> sealRoute(routes(auser)) ~> check {
                status should be(OK)
                val response = responseAs[WhiskAction]
                response should be(action inherit (provider.parameters ++ binding.parameters))
            }
        }
    }

    it should "get action in package binding with public package with overriding parameters" in {
        implicit val tid = transid()
        val auser = WhiskAuth(Subject(), AuthKey())
        val provider = WhiskPackage(namespace, aname, None, Parameters("p", "P"), publish = true)
        val binding = WhiskPackage(EntityPath(auser.subject()), aname, provider.bind, Parameters("b", "B"))
        val action = WhiskAction(provider.path, aname, Exec.js("??"), Parameters("a", "A") ++ Parameters("b", "b"))
        put(entityStore, provider)
        put(entityStore, binding)
        put(entityStore, action)
        whisk.utils.retry {
            Get(s"$collectionPath/${binding.name}/${action.name}") ~> sealRoute(routes(auser)) ~> check {
                status should be(OK)
                val response = responseAs[WhiskAction]
                response should be(action inherit (provider.parameters ++ binding.parameters))
            }
        }
    }

    // NOTE: does not work because entitlement model does not allow for an explicit
    // check on either one or both of the binding and package
    ignore should "get action in package binding with explicit entitlement grant" in {
        implicit val tid = transid()
        val auser = WhiskAuth(Subject(), AuthKey())
        val provider = WhiskPackage(namespace, aname, None, Parameters("p", "P"), publish = false)
        val binding = WhiskPackage(EntityPath(auser.subject()), aname, provider.bind, Parameters("b", "B"))
        val action = WhiskAction(provider.path, aname, Exec.js("??"), Parameters("a", "A"))
        put(entityStore, provider)
        put(entityStore, binding)
        put(entityStore, action)
        val pkgaccess = Resource(provider.namespace, PACKAGES, Some(provider.name()))
        Await.result(entitlementService.grant(auser.subject, READ, pkgaccess), 1 second)
        Get(s"$collectionPath/${binding.name}/${action.name}") ~> sealRoute(routes(auser)) ~> check {
            status should be(OK)
            val response = responseAs[WhiskAction]
            response should be(action inherit (provider.parameters ++ binding.parameters))
        }
    }

    it should "reject get action in package that does not exist" in {
        implicit val tid = transid()
        val provider = WhiskPackage(namespace, aname)
        val action = WhiskAction(provider.path, aname, Exec.js("??"))
        put(entityStore, action)
        Get(s"$collectionPath/${provider.name}/${action.name}") ~> sealRoute(routes(creds)) ~> check {
            status should be(NotFound)
        }
    }

    it should "reject get non-existent action in package" in {
        implicit val tid = transid()
        val provider = WhiskPackage(namespace, aname)
        val action = WhiskAction(provider.path, aname, Exec.js("??"))
        put(entityStore, provider)
        Get(s"$collectionPath/${provider.name}/${action.name}") ~> sealRoute(routes(creds)) ~> check {
            status should be(NotFound)
        }
    }

    it should "reject get action in package binding that does not exist" in {
        implicit val tid = transid()
        val name = aname
        val auser = WhiskAuth(Subject(), AuthKey())
        val provider = WhiskPackage(namespace, aname, None, Parameters("p", "P"), publish = true)
        val binding = WhiskPackage(EntityPath(auser.subject()), aname, provider.bind, Parameters("b", "B"))
        val action = WhiskAction(provider.path, aname, Exec.js("??"), Parameters("a", "A"))
        put(entityStore, provider)
        put(entityStore, action)
        Get(s"$collectionPath/${binding.name}/${action.name}") ~> sealRoute(routes(auser)) ~> check {
            status should be(NotFound)
        }
    }

    it should "reject get action in package binding with package that does not exist" in {
        implicit val tid = transid()
        val name = aname
        val auser = WhiskAuth(Subject(), AuthKey())
        val provider = WhiskPackage(namespace, aname, None, Parameters("p", "P"), publish = true)
        val binding = WhiskPackage(EntityPath(auser.subject()), aname, provider.bind, Parameters("b", "B"))
        val action = WhiskAction(provider.path, aname, Exec.js("??"), Parameters("a", "A"))
        put(entityStore, binding)
        put(entityStore, action)
        Get(s"$collectionPath/${binding.name}/${action.name}") ~> sealRoute(routes(auser)) ~> check {
            status should be(Forbidden) // do not leak that package does not exist
        }
    }

    it should "reject get non-existing action in package binding" in {
        implicit val tid = transid()
        val name = aname
        val auser = WhiskAuth(Subject(), AuthKey())
        val provider = WhiskPackage(namespace, aname, None, Parameters("p", "P"), publish = true)
        val binding = WhiskPackage(EntityPath(auser.subject()), aname, provider.bind, Parameters("b", "B"))
        val action = WhiskAction(provider.path, aname, Exec.js("??"), Parameters("a", "A"))
        put(entityStore, provider)
        put(entityStore, binding)
        Get(s"$collectionPath/${binding.name}/${action.name}") ~> sealRoute(routes(auser)) ~> check {
            status should be(NotFound)
        }
    }

    it should "reject get action in package binding with private package" in {
        implicit val tid = transid()
        val auser = WhiskAuth(Subject(), AuthKey())
        val provider = WhiskPackage(namespace, aname, None, Parameters("p", "P"), publish = false)
        val binding = WhiskPackage(EntityPath(auser.subject()), aname, provider.bind, Parameters("b", "B"))
        val action = WhiskAction(provider.path, aname, Exec.js("??"), Parameters("a", "A"))
        put(entityStore, provider)
        put(entityStore, binding)
        put(entityStore, action)
        Get(s"$collectionPath/${binding.name}/${action.name}") ~> sealRoute(routes(auser)) ~> check {
            status should be(Forbidden)
        }
    }

    //// POST /actions/name
    it should "allow owner to invoke an action in package" in {
        implicit val tid = transid()
        val provider = WhiskPackage(namespace, aname)
        val action = WhiskAction(provider.path, aname, Exec.js("??"))
        val content = JsObject("xxx" -> "yyy".toJson)
        put(entityStore, provider)
        put(entityStore, action)
        Post(s"$collectionPath/${provider.name}/${action.name}", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(Accepted)
            val response = responseAs[JsObject]
            response.fields("activationId") should not be None
        }
    }

    it should "allow non-owner to invoke an action in public package" in {
        implicit val tid = transid()
        val auser = WhiskAuth(Subject(), AuthKey())
        val provider = WhiskPackage(namespace, aname, publish = true)
        val action = WhiskAction(provider.path, aname, Exec.js("??"))
        val content = JsObject("xxx" -> "yyy".toJson)
        put(entityStore, provider)
        put(entityStore, action)
        Post(s"/$namespace/${collection.path}/${provider.name}/${action.name}", content) ~> sealRoute(routes(auser)) ~> check {
            status should be(Accepted)
            val response = responseAs[JsObject]
            response.fields("activationId") should not be None
        }
    }

    it should "invoke action in package binding with public package" in {
        implicit val tid = transid()
        val auser = WhiskAuth(Subject(), AuthKey())
        val provider = WhiskPackage(namespace, aname, publish = true)
        val reference = WhiskPackage(EntityPath(auser.subject()), aname, provider.bind)
        val action = WhiskAction(provider.path, aname, Exec.js("??"))
        val content = JsObject("x" -> "x".toJson, "z" -> "Z".toJson)
        put(entityStore, provider)
        put(entityStore, reference)
        put(entityStore, action)
        Post(s"$collectionPath/${reference.name}/${action.name}", content) ~> sealRoute(routes(auser)) ~> check {
            status should be(Accepted)
            val response = responseAs[JsObject]
            response.fields("activationId") should not be None
        }
    }

    // NOTE: does not work because entitlement model does not allow for an explicit
    // check on either one or both of the binding and package
    ignore should "invoke action in package binding with explicit entitlement grant even if package is not public" in {
        implicit val tid = transid()
        val auser = WhiskAuth(Subject(), AuthKey())
        val provider = WhiskPackage(namespace, aname, publish = false)
        val reference = WhiskPackage(EntityPath(auser.subject()), aname, provider.bind)
        val action = WhiskAction(provider.path, aname, Exec.js("??"))
        val content = JsObject("x" -> "x".toJson, "z" -> "Z".toJson)
        put(entityStore, provider)
        put(entityStore, reference)
        put(entityStore, action)
        val pkgaccess = Resource(provider.namespace, PACKAGES, Some(provider.name()))
        Await.result(entitlementService.grant(auser.subject, ACTIVATE, pkgaccess), 1 second)
        Post(s"$collectionPath/${reference.name}/${action.name}", content) ~> sealRoute(routes(auser)) ~> check {
            status should be(Accepted)
            val response = responseAs[JsObject]
            response.fields("activationId") should not be None
        }
    }

    it should "reject non-owner invoking an action in private package" in {
        implicit val tid = transid()
        val auser = WhiskAuth(Subject(), AuthKey())
        val provider = WhiskPackage(namespace, aname, publish = false)
        val action = WhiskAction(provider.path, aname, Exec.js("??"))
        val content = JsObject("xxx" -> "yyy".toJson)
        put(entityStore, provider)
        put(entityStore, action)
        Post(s"/$namespace/${collection.path}/${provider.name}/${action.name}", content) ~> sealRoute(routes(auser)) ~> check {
            status should be(Forbidden)
            println(responseAs[String])
        }
    }

    it should "reject invoking an action in package that does not exist" in {
        implicit val tid = transid()
        val provider = WhiskPackage(namespace, aname, publish = false)
        val action = WhiskAction(provider.path, aname, Exec.js("??"))
        val content = JsObject("xxx" -> "yyy".toJson)
        put(entityStore, action)
        Post(s"$collectionPath/${provider.name}/${action.name}", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(NotFound)
        }
    }

    it should "reject invoking a non-existent action in package" in {
        implicit val tid = transid()
        val provider = WhiskPackage(namespace, aname, publish = false)
        val action = WhiskAction(provider.path, aname, Exec.js("??"))
        val content = JsObject("xxx" -> "yyy".toJson)
        put(entityStore, action)
        Post(s"$collectionPath/${provider.name}/${action.name}", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(NotFound)
        }
    }

    it should "reject invoke action in package binding with private package" in {
        implicit val tid = transid()
        val auser = WhiskAuth(Subject(), AuthKey())
        val provider = WhiskPackage(namespace, aname, publish = false)
        val reference = WhiskPackage(EntityPath(auser.subject()), aname, provider.bind)
        val action = WhiskAction(provider.path, aname, Exec.js("??"))
        val content = JsObject("x" -> "x".toJson, "z" -> "Z".toJson)
        put(entityStore, provider)
        put(entityStore, reference)
        put(entityStore, action)
        Post(s"$collectionPath/${reference.name}/${action.name}", content) ~> sealRoute(routes(auser)) ~> check {
            status should be(Forbidden)
        }
    }
}
