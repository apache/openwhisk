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

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Route
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import spray.json.DefaultJsonProtocol._
import spray.json._
import org.apache.openwhisk.core.controller.WhiskPackagesApi
import org.apache.openwhisk.core.entitlement.Collection
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.http.{ErrorResponse, Messages}

import scala.language.postfixOps

/**
 * Tests Packages API.
 *
 * Unit tests of the controller service as a standalone component.
 * These tests exercise a fresh instance of the service object in memory -- these
 * tests do NOT communication with a whisk deployment.
 *
 * @Idioglossia
 * "using Specification DSL to write unit tests, as in should, must, not, be"
 * "using Specs2RouteTest DSL to chain HTTP requests for unit testing, as in ~>"
 */
@RunWith(classOf[JUnitRunner])
class PackagesApiTests extends ControllerTestCommon with WhiskPackagesApi {

  /** Packages API tests */
  behavior of "Packages API"

  val creds = WhiskAuthHelpers.newIdentity()
  val namespace = EntityPath(creds.subject.asString)
  val collectionPath = s"/${EntityPath.DEFAULT}/${collection.path}"
  def aname() = MakeName.next("packages_tests")
  val parametersLimit = Parameters.sizeLimit

  private def bindingAnnotation(binding: Binding) = {
    Parameters(WhiskPackage.bindingFieldName, Binding.serdes.write(binding))
  }

  def checkCount(path: String = collectionPath, expected: Long, user: Identity = creds) = {
    implicit val tid = transid()
    withClue(s"count did not match") {
      org.apache.openwhisk.utils.retry {
        Get(s"$path?count=true") ~> Route.seal(routes(user)) ~> check {
          status should be(OK)
          responseAs[JsObject].fields(collection.path).convertTo[Long] shouldBe (expected)
        }
      }
    }
  }

  //// GET /packages
  it should "list all packages/references" in {
    implicit val tid = transid()
    // create packages and package bindings, and confirm API lists all of them
    val providers = (1 to 4).map { i =>
      if (i % 2 == 0) {
        WhiskPackage(namespace, aname(), None)
      } else {
        val binding = Some(Binding(namespace.root, aname()))
        WhiskPackage(namespace, aname(), binding)
      }
    }.toList
    providers foreach { put(entityStore, _) }
    waitOnView(entityStore, WhiskPackage, namespace, providers.length)

    checkCount(expected = providers.length)
    org.apache.openwhisk.utils.retry {
      Get(s"$collectionPath") ~> Route.seal(routes(creds)) ~> check {
        status should be(OK)
        val response = responseAs[List[JsObject]]
        providers.length should be(response.length)
        response should contain theSameElementsAs providers.map(_.summaryAsJson)
      }
    }

    {
      val path = s"/$namespace/${collection.path}"
      val auser = WhiskAuthHelpers.newIdentity()
      checkCount(path, 0, auser)
      Get(path) ~> Route.seal(routes(auser)) ~> check {
        val response = responseAs[List[JsObject]]
        response should be(List.empty) // cannot list packages that are private in another namespace
      }
    }
  }

  it should "list all public packages in explicit namespace excluding bindings" in {
    implicit val tid = transid()
    // create packages and package bindings, set some public and confirm API lists only public packages
    val namespaces = Seq(namespace, EntityPath(aname().toString), EntityPath(aname().toString))
    val providers = Seq(
      WhiskPackage(namespaces(0), aname(), None, publish = true),
      WhiskPackage(namespaces(1), aname(), None, publish = true),
      WhiskPackage(namespaces(2), aname(), None, publish = true))
    val references = Seq(
      WhiskPackage(namespaces(0), aname(), providers(0).bind, publish = true),
      WhiskPackage(namespaces(0), aname(), providers(0).bind, publish = false),
      WhiskPackage(namespaces(0), aname(), providers(1).bind, publish = true),
      WhiskPackage(namespaces(0), aname(), providers(1).bind, publish = false))
    (providers ++ references) foreach { put(entityStore, _) }
    waitOnView(entityStore, WhiskPackage, namespaces(1), 1)
    waitOnView(entityStore, WhiskPackage, namespaces(2), 1)
    waitOnView(entityStore, WhiskPackage, namespaces(0), 1 + 4)

    {
      val expected = providers.filter(_.namespace == namespace) ++ references
      checkCount(expected = expected.length)
      Get(s"$collectionPath") ~> Route.seal(routes(creds)) ~> check {
        status should be(OK)
        val response = responseAs[List[JsObject]]
        response should have size expected.size
        response should contain theSameElementsAs expected.map(_.summaryAsJson)
      }
    }

    {
      val path = s"/$namespace/${collection.path}"
      val auser = WhiskAuthHelpers.newIdentity()
      val expected = providers.filter(p => p.namespace == namespace && p.publish) ++
        references.filter(p => p.publish && p.binding == None)

      checkCount(path, expected.length, auser)
      Get(path) ~> Route.seal(routes(auser)) ~> check {
        status should be(OK)
        val response = responseAs[List[JsObject]]
        response should have size expected.size
        response should contain theSameElementsAs expected.map(_.summaryAsJson)
      }
    }
  }

  it should "reject list when limit is greater than maximum allowed value" in {
    implicit val tid = transid()
    val exceededMaxLimit = Collection.MAX_LIST_LIMIT + 1
    val response = Get(s"$collectionPath?limit=$exceededMaxLimit") ~> Route.seal(routes(creds)) ~> check {
      status should be(BadRequest)
      responseAs[String] should include {
        Messages.listLimitOutOfRange(Collection.PACKAGES, exceededMaxLimit, Collection.MAX_LIST_LIMIT)
      }
    }
  }

  it should "reject list when limit is not an integer" in {
    implicit val tid = transid()
    val notAnInteger = "string"
    val response = Get(s"$collectionPath?limit=$notAnInteger") ~> Route.seal(routes(creds)) ~> check {
      status should be(BadRequest)
      responseAs[String] should include {
        Messages.argumentNotInteger(Collection.PACKAGES, notAnInteger)
      }
    }
  }

  it should "reject list when skip is negative" in {
    implicit val tid = transid()
    val negativeSkip = -1
    val response = Get(s"$collectionPath?skip=$negativeSkip") ~> Route.seal(routes(creds)) ~> check {
      status should be(BadRequest)
      responseAs[String] should include {
        Messages.listSkipOutOfRange(Collection.PACKAGES, negativeSkip)
      }
    }
  }

  it should "reject list when skip is not an integer" in {
    implicit val tid = transid()
    val notAnInteger = "string"
    val response = Get(s"$collectionPath?skip=$notAnInteger") ~> Route.seal(routes(creds)) ~> check {
      status should be(BadRequest)
      responseAs[String] should include {
        Messages.argumentNotInteger(Collection.PACKAGES, notAnInteger)
      }
    }
  }

  ignore should "list all public packages excluding bindings" in {
    implicit val tid = transid()
    // create packages and package bindings, set some public and confirm API lists only public packages
    val namespaces = Seq(namespace, EntityPath(aname().toString), EntityPath(aname().toString))
    val providers = Seq(
      WhiskPackage(namespaces(0), aname(), None, publish = false),
      WhiskPackage(namespaces(1), aname(), None, publish = true),
      WhiskPackage(namespaces(2), aname(), None, publish = true))
    val references = Seq(
      WhiskPackage(namespaces(0), aname(), providers(0).bind, publish = true),
      WhiskPackage(namespaces(0), aname(), providers(0).bind, publish = false),
      WhiskPackage(namespaces(0), aname(), providers(1).bind, publish = true),
      WhiskPackage(namespaces(0), aname(), providers(1).bind, publish = false))
    (providers ++ references) foreach { put(entityStore, _) }
    waitOnView(entityStore, WhiskPackage, namespaces(1), 1)
    waitOnView(entityStore, WhiskPackage, namespaces(2), 1)
    waitOnView(entityStore, WhiskPackage, namespaces(0), 1 + 4)
    Get(s"$collectionPath?public=true") ~> Route.seal(routes(creds)) ~> check {
      status should be(OK)
      val response = responseAs[List[JsObject]]
      val expected = providers filter { _.publish }
      response.length should be >= (expected.length)
      expected forall { p =>
        (response contains p.summaryAsJson) && p.binding == None
      } should be(true)
    }
  }

  // ?public disabled
  ignore should "list all public packages including ones with same name but in different namespaces" in {
    implicit val tid = transid()
    // create packages and package bindings, set some public and confirm API lists only public packages
    val namespaces = Seq(namespace, EntityPath(aname().toString), EntityPath(aname().toString))
    val pkgname = aname()
    val providers = Seq(
      WhiskPackage(namespaces(0), pkgname, None, publish = false),
      WhiskPackage(namespaces(1), pkgname, None, publish = true),
      WhiskPackage(namespaces(2), pkgname, None, publish = true))
    providers foreach { put(entityStore, _) }
    waitOnView(entityStore, WhiskPackage, namespaces(0), 1)
    waitOnView(entityStore, WhiskPackage, namespaces(1), 1)
    waitOnView(entityStore, WhiskPackage, namespaces(2), 1)
    Get(s"$collectionPath?public=true") ~> Route.seal(routes(creds)) ~> check {
      status should be(OK)
      val response = responseAs[List[JsObject]]
      val expected = providers filter { _.publish }
      response.length should be >= (expected.length)
      expected forall { p =>
        (response contains p.summaryAsJson) && p.binding == None
      } should be(true)
    }
  }

  // confirm ?public disabled
  it should "ignore ?public on list all packages" in {
    implicit val tid = transid()
    Get(s"$collectionPath?public=true") ~> Route.seal(routes(creds)) ~> check {
      implicit val tid = transid()
      // create packages and package bindings, set some public and confirm API lists only public packages
      val namespaces = Seq(namespace, EntityPath(aname().toString), EntityPath(aname().toString))
      val pkgname = aname()
      val providers = Seq(
        WhiskPackage(namespaces(0), pkgname, None, publish = true),
        WhiskPackage(namespaces(1), pkgname, None, publish = true),
        WhiskPackage(namespaces(2), pkgname, None, publish = true))
      providers foreach { put(entityStore, _) }
      waitOnView(entityStore, WhiskPackage, namespaces(0), 1)
      waitOnView(entityStore, WhiskPackage, namespaces(1), 1)
      waitOnView(entityStore, WhiskPackage, namespaces(2), 1)
      val expected = providers filter (_.namespace == creds.namespace.name.toPath)

      Get(s"$collectionPath?public=true") ~> Route.seal(routes(creds)) ~> check {
        status should be(OK)
        val response = responseAs[List[JsObject]]
        response.length should be >= (expected.length)
        expected forall { p =>
          (response contains p.summaryAsJson) && p.binding == None
        } should be(true)
      }
    }
  }

  // ?public disabled
  ignore should "reject list all public packages with invalid parameters" in {
    implicit val tid = transid()
    Get(s"$collectionPath?public=true&docs=true") ~> Route.seal(routes(creds)) ~> check {
      status should be(BadRequest)
    }
  }

  //// GET /packages/name
  it should "get package" in {
    implicit val tid = transid()
    val provider = WhiskPackage(namespace, aname(), None)
    put(entityStore, provider)
    Get(s"$collectionPath/${provider.name}") ~> Route.seal(routes(creds)) ~> check {
      status should be(OK)
      val response = responseAs[WhiskPackageWithActions]
      response should be(provider.withActions())
    }
  }

  it should "get package with updated field" in {
    implicit val tid = transid()
    val provider = WhiskPackage(namespace, aname(), None)
    put(entityStore, provider)

    // `updated` field should be compared with a document in DB
    val pkg = get(entityStore, provider.docid, WhiskPackage)

    Get(s"$collectionPath/${provider.name}") ~> Route.seal(routes(creds)) ~> check {
      status should be(OK)
      val response = responseAs[WhiskPackageWithActions]
      response should be(provider.copy(updated = pkg.updated).withActions())
    }
  }

  it should "get package reference for private package in same namespace" in {
    implicit val tid = transid()
    val provider = WhiskPackage(namespace, aname(), None, Parameters("a", "A") ++ Parameters("b", "B"))
    val reference = WhiskPackage(namespace, aname(), provider.bind, Parameters("b", "b") ++ Parameters("c", "C"))
    put(entityStore, provider)
    put(entityStore, reference)
    Get(s"$collectionPath/${reference.name}") ~> Route.seal(routes(creds)) ~> check {
      status should be(OK)
      val response = responseAs[WhiskPackageWithActions]
      response should be(reference.inherit(provider.parameters).withActions())
      // this is redundant in case the precedence orders on inherit are changed incorrectly
      response.wp.parameters should be(Parameters("a", "A") ++ Parameters("b", "b") ++ Parameters("c", "C"))
    }
  }

  it should "not get package reference for a private package in other namespace" in {
    implicit val tid = transid()
    val privateCreds = WhiskAuthHelpers.newIdentity()
    val privateNamespace = EntityPath(privateCreds.subject.asString)

    val provider = WhiskPackage(privateNamespace, aname())
    val reference = WhiskPackage(namespace, aname(), provider.bind)
    put(entityStore, provider)
    put(entityStore, reference)
    Get(s"$collectionPath/${reference.name}") ~> Route.seal(routes(creds)) ~> check {
      status should be(Forbidden)
    }
  }

  it should "get package with its actions and feeds" in {
    implicit val tid = transid()
    val provider = WhiskPackage(namespace, aname())
    val action = WhiskAction(provider.namespace.addPath(provider.name), aname(), jsDefault("??"))
    val feed = WhiskAction(
      provider.namespace.addPath(provider.name),
      aname(),
      jsDefault("??"),
      annotations = Parameters(Parameters.Feed, "true"))
    put(entityStore, provider)
    put(entityStore, action)
    put(entityStore, feed)

    // it should "reject get private package from other subject" in {
    val auser = WhiskAuthHelpers.newIdentity()
    Get(s"/$namespace/${collection.path}/${provider.name}") ~> Route.seal(routes(auser)) ~> check {
      status should be(Forbidden)
    }

    Get(s"$collectionPath/${provider.name}") ~> Route.seal(routes(creds)) ~> check {
      status should be(OK)
      val response = responseAs[WhiskPackageWithActions]
      response should be(provider withActions (List(action, feed)))
    }
  }

  it should "get package reference with its actions and feeds" in {
    implicit val tid = transid()
    val provider = WhiskPackage(namespace, aname())
    val reference = WhiskPackage(namespace, aname(), provider.bind)
    val action = WhiskAction(provider.namespace.addPath(provider.name), aname(), jsDefault("??"))
    val feed = WhiskAction(
      provider.namespace.addPath(provider.name),
      aname(),
      jsDefault("??"),
      annotations = Parameters(Parameters.Feed, "true"))

    put(entityStore, provider)
    put(entityStore, reference)
    put(entityStore, action)
    put(entityStore, feed)

    waitOnView(entityStore, WhiskAction, provider.fullPath, 2)

    // it should "reject get package reference from other subject" in {
    val auser = WhiskAuthHelpers.newIdentity()
    Get(s"/$namespace/${collection.path}/${reference.name}") ~> Route.seal(routes(auser)) ~> check {
      status should be(Forbidden)
    }

    Get(s"$collectionPath/${reference.name}") ~> Route.seal(routes(creds)) ~> check {
      status should be(OK)
      val response = responseAs[WhiskPackageWithActions]
      response should be(reference withActions List(action, feed))
    }
  }

  it should "not get package reference with its actions and feeds from private package" in {
    implicit val tid = transid()
    val privateCreds = WhiskAuthHelpers.newIdentity()
    val privateNamespace = EntityPath(privateCreds.subject.asString)
    val provider = WhiskPackage(privateNamespace, aname())
    val reference = WhiskPackage(namespace, aname(), provider.bind)
    val action = WhiskAction(provider.namespace.addPath(provider.name), aname(), jsDefault("??"))
    val feed = WhiskAction(
      provider.namespace.addPath(provider.name),
      aname(),
      jsDefault("??"),
      annotations = Parameters(Parameters.Feed, "true"))
    put(entityStore, provider)
    put(entityStore, reference)
    put(entityStore, action)
    put(entityStore, feed)

    // it should "reject get package reference from other subject" in {
    val auser = WhiskAuthHelpers.newIdentity()
    Get(s"/$namespace/${collection.path}/${reference.name}") ~> Route.seal(routes(auser)) ~> check {
      status should be(Forbidden)
    }

    Get(s"$collectionPath/${reference.name}") ~> Route.seal(routes(creds)) ~> check {
      status should be(Forbidden)
    }
  }

  //// PUT /packages/name
  it should "create package" in {
    implicit val tid = transid()
    val provider = WhiskPackage(namespace, aname(), None, annotations = Parameters("a", "b"))
    // binding annotation should be removed
    val someBindingAnnotation = Parameters(WhiskPackage.bindingFieldName, "???")
    val content = WhiskPackagePut(annotations = Some(someBindingAnnotation ++ Parameters("a", "b")))
    Put(s"$collectionPath/${provider.name}", content) ~> Route.seal(routes(creds)) ~> check {
      deletePackage(provider.docid)
      status should be(OK)
      val response = responseAs[WhiskPackage]
      checkWhiskEntityResponse(response, provider)
    }
  }

  it should "reject create/update package when package name is reserved" in {
    implicit val tid = transid()
    Set(true, false) foreach { overwrite =>
      RESERVED_NAMES foreach { reservedName =>
        val provider = WhiskPackage(namespace, EntityName(reservedName), None)
        val content = WhiskPackagePut()
        Put(s"$collectionPath/${provider.name}?overwrite=$overwrite", content) ~> Route.seal(routes(creds)) ~> check {
          status should be(BadRequest)
          responseAs[ErrorResponse].error shouldBe Messages.packageNameIsReserved(reservedName)
        }
      }
    }
  }

  it should "not allow package update of pre-existing package with a reserved" in {
    implicit val tid = transid()
    RESERVED_NAMES foreach { reservedName =>
      val provider = WhiskPackage(namespace, EntityName(reservedName), None)
      put(entityStore, provider)
      val content = WhiskPackagePut()
      Put(s"$collectionPath/${provider.name}?overwrite=true", content) ~> Route.seal(routes(creds)) ~> check {
        status should be(BadRequest)
        responseAs[ErrorResponse].error shouldBe Messages.packageNameIsReserved(reservedName)
      }
    }
  }

  it should "allow package get/delete for pre-existing package with a reserved name" in {
    implicit val tid = transid()
    RESERVED_NAMES foreach { reservedName =>
      val provider = WhiskPackage(namespace, EntityName(reservedName), None)
      put(entityStore, provider, garbageCollect = false)
      val content = WhiskPackagePut()
      Get(s"$collectionPath/${provider.name}") ~> Route.seal(routes(creds)) ~> check {
        status should be(OK)
        responseAs[WhiskPackage] shouldBe provider
      }
      Delete(s"$collectionPath/${provider.name}") ~> Route.seal(routes(creds)) ~> check {
        status should be(OK)
      }
    }
  }

  it should "create package reference with explicit namespace" in {
    implicit val tid = transid()
    val provider = WhiskPackage(namespace, aname())
    val reference = WhiskPackage(
      namespace,
      aname(),
      provider.bind,
      annotations = bindingAnnotation(provider.bind.get) ++ Parameters("a", "b"))
    // binding annotation should be removed and set by controller
    val someBindingAnnotation = Parameters(WhiskPackage.bindingFieldName, "???")
    val content = WhiskPackagePut(reference.binding, annotations = Some(someBindingAnnotation ++ Parameters("a", "b")))
    put(entityStore, provider)

    // it should "reject create package reference in some other namespace" in {
    val auser = WhiskAuthHelpers.newIdentity()
    Put(s"/$namespace/${collection.path}/${reference.name}", content) ~> Route.seal(routes(auser)) ~> check {
      status should be(Forbidden)
    }

    Put(s"/$namespace/${collection.path}/${reference.name}", content) ~> Route.seal(routes(creds)) ~> check {
      deletePackage(reference.docid)
      status should be(OK)
      val response = responseAs[WhiskPackage]
      checkWhiskEntityResponse(response, reference)
    }
  }

  it should "not create package reference from private package in another namespace" in {
    implicit val tid = transid()
    val privateCreds = WhiskAuthHelpers.newIdentity()
    val privateNamespace = EntityPath(privateCreds.subject.asString)

    val provider = WhiskPackage(privateNamespace, aname())
    val reference = WhiskPackage(namespace, aname(), provider.bind)
    // binding annotation should be removed and set by controller
    val content = WhiskPackagePut(reference.binding)
    put(entityStore, provider)

    Put(s"/$namespace/${collection.path}/${reference.name}", content) ~> Route.seal(routes(creds)) ~> check {
      status should be(Forbidden)
    }
  }

  it should "create package reference with implicit namespace" in {
    implicit val tid = transid()
    val provider = WhiskPackage(namespace, aname())
    val reference = WhiskPackage(namespace, aname(), Some(Binding(EntityPath.DEFAULT.root, provider.name)))
    val content = WhiskPackagePut(reference.binding)
    put(entityStore, provider)
    Put(s"$collectionPath/${reference.name}", content) ~> Route.seal(routes(creds)) ~> check {
      deletePackage(reference.docid)
      status should be(OK)
      val response = responseAs[WhiskPackage]
      checkWhiskEntityResponse(
        response,
        WhiskPackage(
          reference.namespace,
          reference.name,
          provider.bind,
          annotations = bindingAnnotation(provider.bind.get)))
    }
  }

  it should "reject create package reference when referencing non-existent package in same namespace" in {
    implicit val tid = transid()
    val binding = Some(Binding(namespace.root, aname()))
    val content = WhiskPackagePut(binding)
    Put(s"$collectionPath/${aname()}", content) ~> Route.seal(routes(creds)) ~> check {
      status should be(BadRequest)
      responseAs[ErrorResponse].error should include(Messages.bindingDoesNotExist)
    }
  }

  it should "reject create package reference when referencing non-existent package in another namespace" in {
    implicit val tid = transid()
    val privateCreds = WhiskAuthHelpers.newIdentity()
    val privateNamespace = EntityPath(privateCreds.subject.asString)

    val binding = Some(Binding(privateNamespace.root, aname()))
    val content = WhiskPackagePut(binding)
    Put(s"$collectionPath/${aname()}", content) ~> Route.seal(routes(creds)) ~> check {
      status should be(Forbidden)
    }
  }

  it should "reject create package reference when referencing a non-package" in {
    implicit val tid = transid()
    val provider = WhiskPackage(namespace, aname())
    val reference = WhiskPackage(namespace, aname(), provider.bind)
    val content = WhiskPackagePut(Some(Binding(reference.namespace.root, reference.name)))
    put(entityStore, provider)
    put(entityStore, reference)
    Put(s"$collectionPath/${aname()}", content) ~> Route.seal(routes(creds)) ~> check {
      status should be(BadRequest)
      responseAs[ErrorResponse].error should include(Messages.bindingCannotReferenceBinding)
    }
  }

  it should "reject create package reference when annotations are too big" in {
    implicit val tid = transid()
    val keys: List[Long] =
      List.range(Math.pow(10, 9) toLong, (parametersLimit.toBytes / 20 + Math.pow(10, 9) + 2) toLong)
    val annotations = keys map { key =>
      Parameters(key.toString, "a" * 10)
    } reduce (_ ++ _)
    val content = s"""{"annotations":$annotations}""".parseJson.asJsObject
    Put(s"$collectionPath/${aname()}", content) ~> Route.seal(routes(creds)) ~> check {
      status should be(PayloadTooLarge)
      responseAs[String] should include {
        Messages.entityTooBig(SizeError(WhiskEntity.annotationsFieldName, annotations.size, Parameters.sizeLimit))
      }
    }
  }

  it should "reject create package reference when parameters are too big" in {
    implicit val tid = transid()
    val keys: List[Long] =
      List.range(Math.pow(10, 9) toLong, (parametersLimit.toBytes / 20 + Math.pow(10, 9) + 2) toLong)
    val parameters = keys map { key =>
      Parameters(key.toString, "a" * 10)
    } reduce (_ ++ _)
    val content = s"""{"parameters":$parameters}""".parseJson.asJsObject
    Put(s"$collectionPath/${aname()}", content) ~> Route.seal(routes(creds)) ~> check {
      status should be(PayloadTooLarge)
      responseAs[String] should include {
        Messages.entityTooBig(SizeError(WhiskEntity.paramsFieldName, parameters.size, Parameters.sizeLimit))
      }
    }
  }

  it should "reject update package reference when parameters are too big" in {
    implicit val tid = transid()
    val keys: List[Long] =
      List.range(Math.pow(10, 9) toLong, (parametersLimit.toBytes / 20 + Math.pow(10, 9) + 2) toLong)
    val parameters = keys map { key =>
      Parameters(key.toString, "a" * 10)
    } reduce (_ ++ _)
    val provider = WhiskPackage(namespace, aname())
    val content = s"""{"parameters":$parameters}""".parseJson.asJsObject
    put(entityStore, provider)
    Put(s"$collectionPath/${aname()}?overwrite=true", content) ~> Route.seal(routes(creds)) ~> check {
      status should be(PayloadTooLarge)
      responseAs[String] should include {
        Messages.entityTooBig(SizeError(WhiskEntity.paramsFieldName, parameters.size, Parameters.sizeLimit))
      }
    }
  }

  it should "update package" in {
    implicit val tid = transid()
    val provider = WhiskPackage(namespace, aname())
    val content = WhiskPackagePut(publish = Some(true))
    put(entityStore, provider)

    // it should "reject update package owned by different user" in {
    val auser = WhiskAuthHelpers.newIdentity()
    Put(s"/$namespace/${collection.path}/${provider.name}?overwrite=true", content) ~> Route.seal(routes(auser)) ~> check {
      status should be(Forbidden)
    }

    Put(s"$collectionPath/${provider.name}?overwrite=true", content) ~> Route.seal(routes(creds)) ~> check {
      deletePackage(provider.docid)
      val response = responseAs[WhiskPackage]
      checkWhiskEntityResponse(
        response,
        WhiskPackage(namespace, provider.name, None, version = provider.version.upPatch, publish = true))
    }
  }

  it should "update package reference" in {
    implicit val tid = transid()
    val provider = WhiskPackage(namespace, aname())
    val reference = WhiskPackage(namespace, aname(), provider.bind, annotations = bindingAnnotation(provider.bind.get))
    // create a bogus binding annotation which should be replaced by the PUT
    val someBindingAnnotation = Some(Parameters(WhiskPackage.bindingFieldName, "???") ++ Parameters("a", "b"))
    val content = WhiskPackagePut(publish = Some(true), annotations = someBindingAnnotation)
    put(entityStore, provider)
    put(entityStore, reference)

    // it should "reject update package reference owned by different user"
    val auser = WhiskAuthHelpers.newIdentity()
    Put(s"/$namespace/${collection.path}/${reference.name}?overwrite=true", content) ~> Route.seal(routes(auser)) ~> check {
      status should be(Forbidden)
    }

    Put(s"$collectionPath/${reference.name}?overwrite=true", content) ~> Route.seal(routes(creds)) ~> check {
      deletePackage(reference.docid)
      status should be(OK)
      val response = responseAs[WhiskPackage]
      checkWhiskEntityResponse(
        response,
        WhiskPackage(
          reference.namespace,
          reference.name,
          reference.binding,
          version = reference.version.upPatch,
          publish = true,
          annotations = reference.annotations ++ Parameters("a", "b")))
    }
  }

  it should "reject update package with binding" in {
    implicit val tid = transid()
    val provider = WhiskPackage(namespace, aname())
    val content = WhiskPackagePut(provider.bind)
    put(entityStore, provider)
    Put(s"$collectionPath/${provider.name}?overwrite=true", content) ~> Route.seal(routes(creds)) ~> check {
      status should be(Conflict)
      responseAs[ErrorResponse].error should include(Messages.packageCannotBecomeBinding)
    }
  }

  it should "reject update package reference when new binding refers to non-existent package in same namespace" in {
    implicit val tid = transid()
    val provider = WhiskPackage(namespace, aname())
    val reference = WhiskPackage(namespace, aname(), provider.bind)
    val content = WhiskPackagePut(reference.binding)
    put(entityStore, reference)
    Put(s"$collectionPath/${reference.name}?overwrite=true", content) ~> Route.seal(routes(creds)) ~> check {
      status should be(BadRequest)
      responseAs[ErrorResponse].error should include(Messages.bindingDoesNotExist)
    }
  }

  it should "reject update package reference when new binding refers to non-existent package in another namespace" in {
    implicit val tid = transid()
    val privateCreds = WhiskAuthHelpers.newIdentity()
    val privateNamespace = EntityPath(privateCreds.subject.asString)

    val provider = WhiskPackage(privateNamespace, aname())
    val reference = WhiskPackage(namespace, aname(), provider.bind)
    val content = WhiskPackagePut(reference.binding)
    put(entityStore, reference)
    Put(s"$collectionPath/${reference.name}?overwrite=true", content) ~> Route.seal(routes(creds)) ~> check {
      status should be(Forbidden)
    }
  }

  it should "reject update package reference when new binding refers to itself" in {
    implicit val tid = transid()
    // create package and valid reference binding to it
    val provider = WhiskPackage(namespace, aname())
    val reference = WhiskPackage(namespace, aname(), provider.bind)
    put(entityStore, provider)
    put(entityStore, reference)
    // manipulate package reference such that it attempts to bind to itself
    val content = WhiskPackagePut(Some(Binding(namespace.root, reference.name)))
    Put(s"$collectionPath/${reference.name}?overwrite=true", content) ~> Route.seal(routes(creds)) ~> check {
      status should be(BadRequest)
      responseAs[ErrorResponse].error should include(Messages.bindingCannotReferenceBinding)
    }
    // verify that the reference is still pointing to the original provider
    Get(s"$collectionPath/${reference.name}") ~> Route.seal(routes(creds)) ~> check {
      status should be(OK)
      val response = responseAs[WhiskPackage]
      response should be(reference)
      response.binding should be(provider.bind)
    }
  }

  it should "reject update package reference when new binding refers to private package in another namespace" in {
    implicit val tid = transid()
    val privateCreds = WhiskAuthHelpers.newIdentity()
    val privateNamespace = EntityPath(privateCreds.subject.asString)

    val provider = WhiskPackage(privateNamespace, aname())
    val reference = WhiskPackage(namespace, aname(), provider.bind)
    val content = WhiskPackagePut(reference.binding)
    put(entityStore, provider)
    put(entityStore, reference)
    Put(s"$collectionPath/${reference.name}?overwrite=true", content) ~> Route.seal(routes(creds)) ~> check {
      status should be(Forbidden)
    }
  }

  //// DEL /packages/name
  it should "delete package" in {
    implicit val tid = transid()
    val provider = WhiskPackage(namespace, aname())
    put(entityStore, provider)

    // it should "reject deleting package owned by different user" in {
    val auser = WhiskAuthHelpers.newIdentity()
    Get(s"/$namespace/${collection.path}/${provider.name}") ~> Route.seal(routes(auser)) ~> check {
      status should be(Forbidden)
    }

    Delete(s"$collectionPath/${provider.name}") ~> Route.seal(routes(creds)) ~> check {
      status should be(OK)
      val response = responseAs[WhiskPackage]
      response should be(provider)
    }
  }

  it should "delete package reference regardless of package existence" in {
    implicit val tid = transid()
    val provider = WhiskPackage(namespace, aname())
    val reference = WhiskPackage(namespace, aname(), provider.bind)
    put(entityStore, reference)

    // it should "reject deleting package reference owned by different user" in {
    val auser = WhiskAuthHelpers.newIdentity()
    Get(s"/$namespace/${collection.path}/${reference.name}") ~> Route.seal(routes(auser)) ~> check {
      status should be(Forbidden)
    }

    Delete(s"$collectionPath/${reference.name}") ~> Route.seal(routes(creds)) ~> check {
      status should be(OK)
      val response = responseAs[WhiskPackage]
      response should be(reference)
    }
  }

  it should "delete package and its actions if force flag is set to true" in {
    implicit val tid = transid()
    val provider = WhiskPackage(namespace, aname())
    val action = WhiskAction(provider.namespace.addPath(provider.name), aname(), jsDefault("??"))
    put(entityStore, provider)
    put(entityStore, action)
    org.apache.openwhisk.utils.retry {
      Get(s"$collectionPath/${provider.name}") ~> Route.seal(routes(creds)) ~> check {
        status should be(OK)
        val response = responseAs[JsObject]
        response.fields("actions").asInstanceOf[JsArray].elements.length should be(1)
      }
    }

    Delete(s"$collectionPath/${provider.name}?force=true") ~> Route.seal(routes(creds)) ~> check {
      status should be(OK)
      val response = responseAs[WhiskPackage]
      response should be(provider)
    }
  }

  it should "reject delete non-empty package if force flag is not set" in {
    implicit val tid = transid()
    val provider = WhiskPackage(namespace, aname())
    val action = WhiskAction(provider.namespace.addPath(provider.name), aname(), jsDefault("??"))
    put(entityStore, provider)
    put(entityStore, action)
    org.apache.openwhisk.utils.retry {
      Get(s"$collectionPath/${provider.name}") ~> Route.seal(routes(creds)) ~> check {
        status should be(OK)
        val response = responseAs[JsObject]
        response.fields("actions").asInstanceOf[JsArray].elements.length should be(1)
      }
    }

    val exceptionString = "Package not empty (contains 1 entity). Set force param or delete package contents."
    Delete(s"$collectionPath/${provider.name}") ~> Route.seal(routes(creds)) ~> check {
      status should be(Conflict)
      val response = responseAs[ErrorResponse]
      response.error should include(exceptionString)
      response.code.id should not be empty
    }
  }

  //// invalid resource
  it should "reject invalid resource" in {
    implicit val tid = transid()
    val provider = WhiskPackage(namespace, aname())
    put(entityStore, provider)
    Get(s"$collectionPath/${provider.name}/bar") ~> Route.seal(routes(creds)) ~> check {
      status should be(NotFound)
    }
  }

  it should "return empty list for invalid namespace" in {
    implicit val tid = transid()
    val path = s"/whisk.systsdf/${collection.path}"
    Get(path) ~> Route.seal(routes(creds)) ~> check {
      status should be(OK)
      responseAs[List[JsObject]] should be(List.empty)
    }
  }

  it should "reject bind to non-package" in {
    implicit val tid = transid()
    val action = WhiskAction(namespace, aname(), jsDefault("??"))
    val reference = WhiskPackage(namespace, aname(), Some(Binding(action.namespace.root, action.name)))
    val content = WhiskPackagePut(reference.binding)

    put(entityStore, action)

    Put(s"$collectionPath/${reference.name}", content) ~> Route.seal(routes(creds)) ~> check {
      status should be(Conflict)
      responseAs[ErrorResponse].error should include(Messages.requestedBindingIsNotValid)
    }
  }

  it should "report proper error when record is corrupted on delete" in {
    implicit val tid = transid()
    val entity = BadEntity(namespace, aname())
    put(entityStore, entity)

    Delete(s"$collectionPath/${entity.name}") ~> Route.seal(routes(creds)) ~> check {
      status should be(InternalServerError)
      responseAs[ErrorResponse].error shouldBe Messages.corruptedEntity
    }
  }

  it should "report proper error when record is corrupted on get" in {
    implicit val tid = transid()
    val entity = BadEntity(namespace, aname())
    put(entityStore, entity)

    Get(s"$collectionPath/${entity.name}") ~> Route.seal(routes(creds)) ~> check {
      status should be(InternalServerError)
    }
  }

  it should "report proper error when record is corrupted on put" in {
    implicit val tid = transid()
    val entity = BadEntity(namespace, aname())
    put(entityStore, entity)

    val content = WhiskPackagePut()
    Put(s"$collectionPath/${entity.name}", content) ~> Route.seal(routes(creds)) ~> check {
      status should be(InternalServerError)
      responseAs[ErrorResponse].error shouldBe Messages.corruptedEntity
    }
  }

  var testExecuteOnly = false
  override def executeOnly = testExecuteOnly

  it should ("allow access to get of shared package binding when config option is disabled") in {
    testExecuteOnly = false
    implicit val tid = transid()
    val auser = WhiskAuthHelpers.newIdentity()
    val provider = WhiskPackage(namespace, aname(), None, Parameters("p", "P"), publish = true)
    val binding = WhiskPackage(EntityPath(auser.subject.asString), aname(), provider.bind, Parameters("b", "B"))
    put(entityStore, provider)
    put(entityStore, binding)
    Get(s"/$namespace/${collection.path}/${provider.name}") ~> Route.seal(routes(auser)) ~> check {
      status should be(OK)
    }
  }

  it should ("allow access to get of shared package when config option is disabled") in {
    testExecuteOnly = false
    implicit val tid = transid()
    val auser = WhiskAuthHelpers.newIdentity()
    val provider = WhiskPackage(namespace, aname(), None, publish = true)
    put(entityStore, provider)

    Get(s"/$namespace/${collection.path}/${provider.name}") ~> Route.seal(routes(auser)) ~> check {
      status should be(OK)
    }
  }

  it should ("deny access to get of shared package binding when config option is enabled") in {
    testExecuteOnly = true
    implicit val tid = transid()
    val auser = WhiskAuthHelpers.newIdentity()
    val provider = WhiskPackage(namespace, aname(), None, Parameters("p", "P"), publish = true)
    val binding = WhiskPackage(EntityPath(auser.subject.asString), aname(), provider.bind, Parameters("b", "B"))
    put(entityStore, provider)
    put(entityStore, binding)
    Get(s"/$namespace/${collection.path}/${provider.name}") ~> Route.seal(routes(auser)) ~> check {
      status should be(Forbidden)
    }

  }

  it should ("deny access to get of shared package when config option is enabled") in {
    testExecuteOnly = true
    implicit val tid = transid()
    val auser = WhiskAuthHelpers.newIdentity()
    val provider = WhiskPackage(namespace, aname(), None, publish = true)
    put(entityStore, provider)

    Get(s"/$namespace/${collection.path}/${provider.name}") ~> Route.seal(routes(auser)) ~> check {
      status should be(Forbidden)
    }
  }
}
