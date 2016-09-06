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
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import spray.http.StatusCodes.BadRequest
import spray.http.StatusCodes.Forbidden
import spray.http.StatusCodes.Conflict
import spray.http.StatusCodes.NotFound
import spray.http.StatusCodes.OK
import spray.http.StatusCodes.RequestEntityTooLarge
import spray.httpx.SprayJsonSupport.sprayJsonMarshaller
import spray.httpx.SprayJsonSupport.sprayJsonUnmarshaller
import spray.json.DefaultJsonProtocol.RootJsObjectFormat
import spray.json.DefaultJsonProtocol.listFormat
import spray.json.DefaultJsonProtocol.RootJsObjectFormat
import spray.json.JsObject
import spray.json.pimpString
import whisk.core.entity.Exec
import whisk.core.entity.Namespace
import whisk.core.entity.Parameters
import whisk.core.entity.WhiskAction
import whisk.core.entity.AuthKey
import whisk.core.entity.WhiskAuth
import whisk.core.entity.Subject
import whisk.core.entity.WhiskPackage
import whisk.core.entity.Binding
import whisk.core.controller.WhiskPackagesApi
import whisk.core.entity.WhiskPackagePut
import whisk.http.ErrorResponse
import whisk.core.entity.WhiskPackageWithActions
import spray.json.JsArray

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
class PackagesApiTests extends ControllerTestCommon with WhiskPackagesApi {

    /** Packages API tests */
    behavior of "Packages API"

    val creds = WhiskAuth(Subject(), AuthKey())
    val namespace = Namespace(creds.subject())
    val collectionPath = s"/${Namespace.DEFAULT}/${collection.path}"
    def aname = MakeName.next("packages_tests")
    val entityTooBigRejectionMessage = "request entity too large"
    val parametersLimit = Parameters.sizeLimit

    private def bindingAnnotation(binding: Binding) = {
        Parameters(WhiskPackage.bindingFieldName, Binding.serdes.write(binding))
    }

    //// GET /packages
    it should "list all packages/references" in {
        implicit val tid = transid()
        // create packages and package bindings, and confirm API lists all of them
        val providers = (1 to 4).map { i =>
            if (i % 2 == 0) {
                WhiskPackage(namespace, aname, None)
            } else {
                val binding = Some(Binding(namespace, aname))
                WhiskPackage(namespace, aname, binding)
            }
        }.toList
        providers foreach { put(entityStore, _) }
        waitOnView(entityStore, WhiskPackage, namespace, providers.length)
        whisk.utils.retry {
            Get(s"$collectionPath") ~> sealRoute(routes(creds)) ~> check {
                status should be(OK)
                val response = responseAs[List[JsObject]]
                providers.length should be(response.length)
                providers forall { p => response contains p.summaryAsJson } should be(true)
            }
        }

        val auser = WhiskAuth(Subject(), AuthKey())
        Get(s"/$namespace/${collection.path}") ~> sealRoute(routes(auser)) ~> check {
            val response = responseAs[List[JsObject]]
            response should be(List()) // cannot list packages that are private in another namespace
        }
    }

    it should "list all public packages in explicit namespace excluding bindings" in {
        implicit val tid = transid()
        // create packages and package bindings, set some public and confirm API lists only public packages
        val namespaces = Seq(namespace, Namespace(aname.toString), Namespace(aname.toString))
        val providers = Seq(
            WhiskPackage(namespaces(0), aname, None, publish = true),
            WhiskPackage(namespaces(1), aname, None, publish = true),
            WhiskPackage(namespaces(2), aname, None, publish = true))
        val references = Seq(
            WhiskPackage(namespaces(0), aname, providers(0).bind, publish = true),
            WhiskPackage(namespaces(0), aname, providers(0).bind, publish = false),
            WhiskPackage(namespaces(0), aname, providers(1).bind, publish = true),
            WhiskPackage(namespaces(0), aname, providers(1).bind, publish = false))
        (providers ++ references) foreach { put(entityStore, _) }
        waitOnView(entityStore, WhiskPackage, namespaces(1), 1)
        waitOnView(entityStore, WhiskPackage, namespaces(2), 1)
        waitOnView(entityStore, WhiskPackage, namespaces(0), 1 + 4)
        Get(s"$collectionPath") ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[List[JsObject]]
            val expected = providers.filter { _.namespace == namespace } ++ references
            response.length should be >= (expected.length)
            expected forall { p => (response contains p.summaryAsJson) } should be(true)
        }

        val auser = WhiskAuth(Subject(), AuthKey())
        Get(s"/$namespace/${collection.path}") ~> sealRoute(routes(auser)) ~> check {
            status should be(OK)
            val response = responseAs[List[JsObject]]
            val expected = providers.filter {
                p => p.namespace == namespace && p.publish
            } ++ references.filter {
                p => p.publish && p.binding == None
            }
            response.length should be >= (expected.length)
            expected forall { p => (response contains p.summaryAsJson) } should be(true)
        }
    }

    ignore should "list all public packages excluding bindings" in {
        implicit val tid = transid()
        // create packages and package bindings, set some public and confirm API lists only public packages
        val namespaces = Seq(namespace, Namespace(aname.toString), Namespace(aname.toString))
        val providers = Seq(
            WhiskPackage(namespaces(0), aname, None, publish = false),
            WhiskPackage(namespaces(1), aname, None, publish = true),
            WhiskPackage(namespaces(2), aname, None, publish = true))
        val references = Seq(
            WhiskPackage(namespaces(0), aname, providers(0).bind, publish = true),
            WhiskPackage(namespaces(0), aname, providers(0).bind, publish = false),
            WhiskPackage(namespaces(0), aname, providers(1).bind, publish = true),
            WhiskPackage(namespaces(0), aname, providers(1).bind, publish = false))
        (providers ++ references) foreach { put(entityStore, _) }
        waitOnView(entityStore, WhiskPackage, namespaces(1), 1)
        waitOnView(entityStore, WhiskPackage, namespaces(2), 1)
        waitOnView(entityStore, WhiskPackage, namespaces(0), 1 + 4)
        Get(s"$collectionPath?public=true") ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[List[JsObject]]
            val expected = providers filter { _.publish }
            response.length should be >= (expected.length)
            expected forall { p => (response contains p.summaryAsJson) && p.binding == None } should be(true)
        }
    }

    // ?public disabled
    ignore should "list all public packages including ones with same name but in different namespaces" in {
        implicit val tid = transid()
        // create packages and package bindings, set some public and confirm API lists only public packages
        val namespaces = Seq(namespace, Namespace(aname.toString), Namespace(aname.toString))
        val pkgname = aname
        val providers = Seq(
            WhiskPackage(namespaces(0), pkgname, None, publish = false),
            WhiskPackage(namespaces(1), pkgname, None, publish = true),
            WhiskPackage(namespaces(2), pkgname, None, publish = true))
        providers foreach { put(entityStore, _) }
        waitOnView(entityStore, WhiskPackage, namespaces(0), 1)
        waitOnView(entityStore, WhiskPackage, namespaces(1), 1)
        waitOnView(entityStore, WhiskPackage, namespaces(2), 1)
        Get(s"$collectionPath?public=true") ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[List[JsObject]]
            val expected = providers filter { _.publish }
            response.length should be >= (expected.length)
            expected forall { p => (response contains p.summaryAsJson) && p.binding == None } should be(true)
        }
    }

    // confirm ?public disabled
    it should "ignore ?public on list all packages" in {
        implicit val tid = transid()
        Get(s"$collectionPath?public=true") ~> sealRoute(routes(creds)) ~> check {
            implicit val tid = transid()
            // create packages and package bindings, set some public and confirm API lists only public packages
            val namespaces = Seq(namespace, Namespace(aname.toString), Namespace(aname.toString))
            val pkgname = aname
            val providers = Seq(
                WhiskPackage(namespaces(0), pkgname, None, publish = true),
                WhiskPackage(namespaces(1), pkgname, None, publish = true),
                WhiskPackage(namespaces(2), pkgname, None, publish = true))
            providers foreach { put(entityStore, _) }
            waitOnView(entityStore, WhiskPackage, namespaces(0), 1)
            waitOnView(entityStore, WhiskPackage, namespaces(1), 1)
            waitOnView(entityStore, WhiskPackage, namespaces(2), 1)
            Get(s"$collectionPath?public=true") ~> sealRoute(routes(creds)) ~> check {
                status should be(OK)
                val response = responseAs[List[JsObject]]
                val expected = providers filter { _.namespace == creds.subject.namespace }
                response.length should be >= (expected.length)
                expected forall { p => (response contains p.summaryAsJson) && p.binding == None } should be(true)
            }
        }
    }

    // ?public disabled
    ignore should "reject list all public packages with invalid parameters" in {
        implicit val tid = transid()
        Get(s"$collectionPath?public=true&docs=true") ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
        }
    }

    //// GET /packages/name
    it should "get package" in {
        implicit val tid = transid()
        val provider = WhiskPackage(namespace, aname, None)
        put(entityStore, provider)
        Get(s"$collectionPath/${provider.name}") ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[WhiskPackageWithActions]
            response should be(provider withActions ())
        }

        Get(s"$collectionPath/${provider.name}") ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[WhiskPackageWithActions]
            response should be(provider withActions ())
        }
    }

    it should "get package reference" in {
        implicit val tid = transid()
        val provider = WhiskPackage(namespace, aname, None, Parameters("a", "A") ++ Parameters("b", "B"))
        val reference = WhiskPackage(namespace, aname, provider.bind, Parameters("b", "b") ++ Parameters("c", "C"))
        put(entityStore, provider)
        put(entityStore, reference)
        Get(s"$collectionPath/${reference.name}") ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[WhiskPackageWithActions]
            response should be(reference inherit provider.parameters withActions ())
            // this is redundant in case the precedence orders on inherit are changed incorrectly
            response.wp.parameters should be(Parameters("a", "A") ++ Parameters("b", "b") ++ Parameters("c", "C"))
        }
    }

    it should "get package with its actions and feeds" in {
        implicit val tid = transid()
        val provider = WhiskPackage(namespace, aname)
        val reference = WhiskPackage(namespace, aname, provider.bind)
        val action = WhiskAction(provider.namespace.addpath(provider.name), aname, Exec.js("??"))
        val feed = WhiskAction(provider.namespace.addpath(provider.name), aname, Exec.js("??"), annotations = Parameters(Parameters.Feed, "true"))
        put(entityStore, provider)
        put(entityStore, reference)
        put(entityStore, action)
        put(entityStore, feed)

        // it should "reject get private package from other subject" in {
        val auser = WhiskAuth(Subject(), AuthKey())
        Get(s"/$namespace/${collection.path}/${provider.name}") ~> sealRoute(routes(auser)) ~> check {
            status should be(Forbidden)
        }

        Get(s"$collectionPath/${provider.name}") ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[WhiskPackageWithActions]
            response should be(provider withActions (List(action, feed)))
        }
    }

    it should "get package reference with its actions and feeds" in {
        implicit val tid = transid()
        val provider = WhiskPackage(namespace, aname)
        val reference = WhiskPackage(namespace, aname, provider.bind)
        val action = WhiskAction(provider.namespace.addpath(provider.name), aname, Exec.js("??"))
        val feed = WhiskAction(provider.namespace.addpath(provider.name), aname, Exec.js("??"), annotations = Parameters(Parameters.Feed, "true"))
        put(entityStore, provider)
        put(entityStore, reference)
        put(entityStore, action)
        put(entityStore, feed)

        // it should "reject get package reference from other subject" in {
        val auser = WhiskAuth(Subject(), AuthKey())
        Get(s"/$namespace/${collection.path}/${reference.name}") ~> sealRoute(routes(auser)) ~> check {
            status should be(Forbidden)
        }

        Get(s"$collectionPath/${reference.name}") ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[WhiskPackageWithActions]
            response should be(reference withActions (List(action, feed)))
        }
    }

    //// PUT /packages/name
    it should "create package" in {
        implicit val tid = transid()
        val provider = WhiskPackage(namespace, aname, None, annotations = Parameters("a", "b"))
        // binding annotation should be removed
        val someBindingAnnotation = Parameters(WhiskPackage.bindingFieldName, "???")
        val content = WhiskPackagePut(annotations = Some(someBindingAnnotation ++ Parameters("a", "b")))
        Put(s"$collectionPath/${provider.name}", content) ~> sealRoute(routes(creds)) ~> check {
            deletePackage(provider.docid)
            status should be(OK)
            val response = responseAs[WhiskPackage]
            response should be(provider)
        }
    }

    it should "create package reference with explicit namespace" in {
        implicit val tid = transid()
        val provider = WhiskPackage(namespace, aname)
        val reference = WhiskPackage(namespace, aname, provider.bind, annotations = bindingAnnotation(provider.bind.get) ++ Parameters("a", "b"))
        // binding annotation should be removed and set by controller
        val someBindingAnnotation = Parameters(WhiskPackage.bindingFieldName, "???")
        val content = WhiskPackagePut(reference.binding, annotations = Some(someBindingAnnotation ++ Parameters("a", "b")))
        put(entityStore, provider)

        // it should "reject create package reference in some other namespace" in {
        val auser = WhiskAuth(Subject(), AuthKey())
        Put(s"/$namespace/${collection.path}/${reference.name}", content) ~> sealRoute(routes(auser)) ~> check {
            status should be(Forbidden)
        }

        Put(s"/$namespace/${collection.path}/${reference.name}", content) ~> sealRoute(routes(creds)) ~> check {
            deletePackage(reference.docid)
            status should be(OK)
            val response = responseAs[WhiskPackage]
            response should be(reference)
        }
    }

    it should "create package reference with implicit namespace" in {
        implicit val tid = transid()
        val provider = WhiskPackage(namespace, aname)
        val reference = WhiskPackage(namespace, aname, Some(Binding(Namespace.DEFAULT, provider.name)))
        val content = WhiskPackagePut(reference.binding)
        put(entityStore, provider)
        Put(s"$collectionPath/${reference.name}", content) ~> sealRoute(routes(creds)) ~> check {
            deletePackage(reference.docid)
            status should be(OK)
            val response = responseAs[WhiskPackage]
            response should be {
                WhiskPackage(reference.namespace, reference.name, provider.bind,
                    annotations = bindingAnnotation(provider.bind.get))
            }
        }
    }

    it should "reject create package reference when referencing non-existent package" in {
        implicit val tid = transid()
        val binding = Some(Binding(namespace, aname))
        val content = WhiskPackagePut(binding)
        Put(s"$collectionPath/$aname", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
            responseAs[ErrorResponse].error should include("binding references a package that does not exist")
        }
    }

    it should "reject create package reference when referencing a non-package" in {
        implicit val tid = transid()
        val provider = WhiskPackage(namespace, aname)
        val reference = WhiskPackage(namespace, aname, provider.bind)
        val content = WhiskPackagePut(Some(Binding(reference.namespace, reference.name)))
        put(entityStore, reference)
        Put(s"$collectionPath/$aname", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
            responseAs[ErrorResponse].error should include("cannot bind to another package binding")
        }
    }

    it should "reject create package reference when annotations are too big" in {
        implicit val tid = transid()
        val keys: List[Long] = List.range(Math.pow(10, 9) toLong, (parametersLimit.toBytes / 2 / 20 + Math.pow(10, 9) + 2) toLong)
        val parameters = keys map { key =>
            Parameters(key.toString, "a" * 10)
        } reduce (_ ++ _)
        val content = s"""{"annotations":$parameters}""".parseJson.asJsObject
        Put(s"$collectionPath/${aname}", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(RequestEntityTooLarge)
            response.entity.toString should include(entityTooBigRejectionMessage)
        }
    }

    it should "reject create package reference when parameters are too big" in {
        implicit val tid = transid()
        val keys: List[Long] = List.range(Math.pow(10, 9) toLong, (parametersLimit.toBytes / 2 / 20 + Math.pow(10, 9) + 2) toLong)
        val parameters = keys map { key =>
            Parameters(key.toString, "a" * 10)
        } reduce (_ ++ _)
        val content = s"""{"parameters":$parameters}""".parseJson.asJsObject
        Put(s"$collectionPath/${aname}", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(RequestEntityTooLarge)
            response.entity.toString should include(entityTooBigRejectionMessage)
        }
    }

    it should "reject update package reference when parameters are too big" in {
        implicit val tid = transid()
        val keys: List[Long] = List.range(Math.pow(10, 9) toLong, (parametersLimit.toBytes / 2 / 20 + Math.pow(10, 9) + 2) toLong)
        val parameters = keys map { key =>
            Parameters(key.toString, "a" * 10)
        } reduce (_ ++ _)
        val provider = WhiskPackage(namespace, aname)
        val content = s"""{"parameters":$parameters}""".parseJson.asJsObject
        put(entityStore, provider)
        Put(s"$collectionPath/${aname}?overwrite=true", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(RequestEntityTooLarge)
            response.entity.toString should include(entityTooBigRejectionMessage)
        }
    }

    it should "update package" in {
        implicit val tid = transid()
        val provider = WhiskPackage(namespace, aname)
        val content = WhiskPackagePut(publish = Some(true))
        put(entityStore, provider)

        // it should "reject update package owned by different user" in {
        val auser = WhiskAuth(Subject(), AuthKey())
        Put(s"/$namespace/${collection.path}/${provider.name}?overwrite=true", content) ~> sealRoute(routes(auser)) ~> check {
            status should be(Forbidden)
        }

        Put(s"$collectionPath/${provider.name}?overwrite=true", content) ~> sealRoute(routes(creds)) ~> check {
            deletePackage(provider.docid)
            val response = responseAs[WhiskPackage]
            response should be(WhiskPackage(namespace, provider.name, None, version = provider.version.upPatch, publish = true))
        }
    }

    it should "update package reference" in {
        implicit val tid = transid()
        val provider = WhiskPackage(namespace, aname)
        val reference = WhiskPackage(namespace, aname, provider.bind, annotations = bindingAnnotation(provider.bind.get))
        // create a bogus binding annotation which should be replaced by the PUT
        val someBindingAnnotation = Some(Parameters(WhiskPackage.bindingFieldName, "???") ++ Parameters("a", "b"))
        val content = WhiskPackagePut(publish = Some(true), annotations = someBindingAnnotation)
        put(entityStore, provider)
        put(entityStore, reference)

        // it should "reject update package reference owned by different user"
        val auser = WhiskAuth(Subject(), AuthKey())
        Put(s"/$namespace/${collection.path}/${reference.name}?overwrite=true", content) ~> sealRoute(routes(auser)) ~> check {
            status should be(Forbidden)
        }

        Put(s"$collectionPath/${reference.name}?overwrite=true", content) ~> sealRoute(routes(creds)) ~> check {
            deletePackage(reference.docid)
            status should be(OK)
            val response = responseAs[WhiskPackage]
            println(responseAs[String])
            response should be {
                WhiskPackage(reference.namespace, reference.name, reference.binding,
                    version = reference.version.upPatch,
                    publish = true,
                    annotations = reference.annotations ++ Parameters("a", "b"))
            }
        }
    }

    it should "reject update package with binding" in {
        implicit val tid = transid()
        val provider = WhiskPackage(namespace, aname)
        val content = WhiskPackagePut(provider.bind)
        put(entityStore, provider)
        Put(s"$collectionPath/${provider.name}?overwrite=true", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(Conflict)
        }
    }

    it should "reject update package reference when new binding refers to non-existent package" in {
        implicit val tid = transid()
        val provider = WhiskPackage(namespace, aname)
        val reference = WhiskPackage(namespace, aname, provider.bind)
        val content = WhiskPackagePut(reference.binding)
        put(entityStore, reference)
        Put(s"$collectionPath/${reference.name}?overwrite=true", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
        }
    }

    //// DEL /packages/name
    it should "delete package" in {
        implicit val tid = transid()
        val provider = WhiskPackage(namespace, aname)
        put(entityStore, provider)

        // it should "reject deleting package owned by different user" in {
        val auser = WhiskAuth(Subject(), AuthKey())
        Get(s"/$namespace/${collection.path}/${provider.name}") ~> sealRoute(routes(auser)) ~> check {
            status should be(Forbidden)
        }

        Delete(s"$collectionPath/${provider.name}") ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[WhiskPackage]
            response should be(provider)
        }
    }

    it should "delete package reference regardless of package existence" in {
        implicit val tid = transid()
        val provider = WhiskPackage(namespace, aname)
        val reference = WhiskPackage(namespace, aname, provider.bind)
        put(entityStore, reference)

        // it should "reject deleting package reference owned by different user" in {
        val auser = WhiskAuth(Subject(), AuthKey())
        Get(s"/$namespace/${collection.path}/${reference.name}") ~> sealRoute(routes(auser)) ~> check {
            status should be(Forbidden)
        }

        Delete(s"$collectionPath/${reference.name}") ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[WhiskPackage]
            response should be(reference)
        }
    }

    it should "reject delete non-empty package" in {
        implicit val tid = transid()
        val provider = WhiskPackage(namespace, aname)
        val reference = WhiskPackage(namespace, aname, provider.bind)
        val action = WhiskAction(provider.namespace.addpath(provider.name), aname, Exec.js("??"))
        put(entityStore, provider)
        put(entityStore, reference)
        put(entityStore, action)
        whisk.utils.retry {
            Get(s"$collectionPath/${provider.name}") ~> sealRoute(routes(creds)) ~> check {
                status should be(OK)
                val response = responseAs[JsObject]
                response.fields("actions").asInstanceOf[JsArray].elements.length should be(1)
            }
        }

        Delete(s"$collectionPath/${provider.name}") ~> sealRoute(routes(creds)) ~> check {
            status should be(Conflict)
            val response = responseAs[ErrorResponse]
            response.error should include("Package not empty (contains 1 entity)")
            response.code() should be >= 1L
        }
    }

    //// invalid resource
    it should "reject invalid resource" in {
        implicit val tid = transid()
        val provider = WhiskPackage(namespace, aname)
        put(entityStore, provider)
        Get(s"$collectionPath/${provider.name}/bar") ~> sealRoute(routes(creds)) ~> check {
            status should be(NotFound)
        }
    }
}
