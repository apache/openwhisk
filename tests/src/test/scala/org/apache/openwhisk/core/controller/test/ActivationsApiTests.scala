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

import java.time.{Clock, Instant}

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Route
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import spray.json.DefaultJsonProtocol._
import spray.json._
import org.apache.openwhisk.core.controller.WhiskActivationsApi
import org.apache.openwhisk.core.entitlement.Collection
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.entity.size._
import org.apache.openwhisk.http.{ErrorResponse, Messages}
import org.apache.openwhisk.core.database.UserContext

/**
 * Tests Activations API.
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
class ActivationsApiTests extends ControllerTestCommon with WhiskActivationsApi {

  /** Activations API tests */
  behavior of "Activations API"

  val creds = WhiskAuthHelpers.newIdentity()
  val context = UserContext(creds)
  val namespace = EntityPath(creds.subject.asString)
  val collectionPath = s"/${EntityPath.DEFAULT}/${collection.path}"

  def aname() = MakeName.next("activations_tests")

  def checkCount(filter: String, expected: Int, user: Identity = creds) = {
    implicit val tid = transid()
    withClue(s"count did not match for filter: $filter") {
      org.apache.openwhisk.utils.retry {
        Get(s"$collectionPath?count=true&$filter") ~> Route.seal(routes(user)) ~> check {
          status should be(OK)
          responseAs[JsObject] shouldBe JsObject(collection.path -> JsNumber(expected))
        }
      }
    }
  }

  //// GET /activations
  it should "get summary activation by namespace" in {
    implicit val tid = transid()
    // create two sets of activation records, and check that only one set is served back
    val creds1 = WhiskAuthHelpers.newAuth()
    val notExpectedActivations = (1 to 2).map { i =>
      WhiskActivation(
        EntityPath(creds1.subject.asString),
        aname(),
        creds1.subject,
        ActivationId.generate(),
        start = Instant.now,
        end = Instant.now)
    }
    val actionName = aname()
    val activations = (1 to 2).map { i =>
      WhiskActivation(
        namespace,
        actionName,
        creds.subject,
        ActivationId.generate(),
        start = Instant.now,
        end = Instant.now)
    }.toList
    try {
      (notExpectedActivations ++ activations).foreach(storeActivation(_, false, false, context))
      waitOnListActivationsInNamespace(namespace, 2, context)

      org.apache.openwhisk.utils.retry {
        Get(s"$collectionPath") ~> Route.seal(routes(creds)) ~> check {
          status should be(OK)
          val response = responseAs[List[JsObject]]
          activations.length should be(response.length)
          response should contain theSameElementsAs activations.map(_.summaryAsJson)
          response forall { a =>
            a.getFields("for") match {
              case Seq(JsString(n)) => n == actionName.asString
              case _                => false
            }
          }
        }
      }

      // it should "list activations with explicit namespace owned by subject" in {
      org.apache.openwhisk.utils.retry {
        Get(s"/$namespace/${collection.path}") ~> Route.seal(routes(creds)) ~> check {
          status should be(OK)
          val response = responseAs[List[JsObject]]
          activations.length should be(response.length)
          response should contain theSameElementsAs activations.map(_.summaryAsJson)
          response forall { a =>
            a.getFields("for") match {
              case Seq(JsString(n)) => n == actionName.asString
              case _                => false
            }
          }
        }
      }

      // it should "reject list activations with explicit namespace not owned by subject" in {
      val auser = WhiskAuthHelpers.newIdentity()
      Get(s"/$namespace/${collection.path}") ~> Route.seal(routes(auser)) ~> check {
        status should be(Forbidden)
      }

    } finally {
      (notExpectedActivations ++ activations).foreach(activation =>
        deleteActivation(ActivationId(activation.docid.asString), context))
    }
  }

  //// GET /activations?docs=true
  it should "return empty list when no activations exist" in {
    implicit val tid = transid()
    org.apache.openwhisk.utils
      .retry { // retry because view will be stale from previous test and result in null doc fields
        Get(s"$collectionPath?docs=true") ~> Route.seal(routes(creds)) ~> check {
          status should be(OK)
          responseAs[List[JsObject]] shouldBe 'empty
        }
      }
  }

  it should "get full activation by namespace" in {
    implicit val tid = transid()
    // create two sets of activation records, and check that only one set is served back
    val creds1 = WhiskAuthHelpers.newAuth()
    val notExpectedActivations = (1 to 2).map { i =>
      WhiskActivation(
        EntityPath(creds1.subject.asString),
        aname(),
        creds1.subject,
        ActivationId.generate(),
        start = Instant.now,
        end = Instant.now)
    }
    val actionName = aname()
    val activations = (1 to 2).map { i =>
      WhiskActivation(
        namespace,
        actionName,
        creds.subject,
        ActivationId.generate(),
        start = Instant.now,
        end = Instant.now,
        response = ActivationResponse.success(Some(JsNumber(5))))
    }.toList

    try {
      (notExpectedActivations ++ activations).foreach(storeActivation(_, false, false, context))
      waitOnListActivationsInNamespace(namespace, 2, context)
      checkCount("", 2)

      org.apache.openwhisk.utils.retry {
        Get(s"$collectionPath?docs=true") ~> Route.seal(routes(creds)) ~> check {
          status should be(OK)
          val response = responseAs[List[JsObject]]
          activations.length should be(response.length)
          response should contain theSameElementsAs activations.map(_.toExtendedJson())
        }
      }
    } finally {
      (notExpectedActivations ++ activations).foreach(activation =>
        deleteActivation(ActivationId(activation.docid.asString), context))
    }
  }

  //// GET /activations?docs=true&since=xxx&upto=yyy
  it should "get full activation by namespace within a date range" in {
    implicit val tid = transid()
    // create two sets of activation records, and check that only one set is served back
    val creds1 = WhiskAuthHelpers.newAuth()
    val notExpectedActivations = (1 to 2).map { i =>
      WhiskActivation(
        EntityPath(creds1.subject.asString),
        aname(),
        creds1.subject,
        ActivationId.generate(),
        start = Instant.now,
        end = Instant.now)
    }

    val actionName = aname()
    val now = Instant.now(Clock.systemUTC())
    val since = now.plusSeconds(10)
    val upto = now.plusSeconds(30)
    val activations = Seq(
      WhiskActivation(
        namespace,
        actionName,
        creds.subject,
        ActivationId.generate(),
        start = now.plusSeconds(9),
        end = now.plusSeconds(9)),
      WhiskActivation(
        namespace,
        actionName,
        creds.subject,
        ActivationId.generate(),
        start = now.plusSeconds(20),
        end = now.plusSeconds(20)), // should match
      WhiskActivation(
        namespace,
        actionName,
        creds.subject,
        ActivationId.generate(),
        start = now.plusSeconds(10),
        end = now.plusSeconds(20)), // should match
      WhiskActivation(
        namespace,
        actionName,
        creds.subject,
        ActivationId.generate(),
        start = now.plusSeconds(31),
        end = now.plusSeconds(31)),
      WhiskActivation(
        namespace,
        actionName,
        creds.subject,
        ActivationId.generate(),
        start = now.plusSeconds(30),
        end = now.plusSeconds(30))) // should match

    try {
      (notExpectedActivations ++ activations).foreach(storeActivation(_, false, false, context))
      waitOnListActivationsInNamespace(namespace, activations.length, context)

      { // get between two time stamps
        val filter = s"since=${since.toEpochMilli}&upto=${upto.toEpochMilli}"
        val expected = activations.filter { e =>
          (e.start.equals(since) || e.start.equals(upto) || (e.start.isAfter(since) && e.start.isBefore(upto)))
        }

        checkCount(filter, expected.length)

        org.apache.openwhisk.utils.retry {
          Get(s"$collectionPath?docs=true&$filter") ~> Route.seal(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[List[JsObject]]
            expected.length should be(response.length)
            response should contain theSameElementsAs expected.map(_.toExtendedJson())
          }
        }
      }

      { // get 'upto' with no defined since value should return all activation 'upto'
        val expected = activations.filter(e => e.start.equals(upto) || e.start.isBefore(upto))
        val filter = s"upto=${upto.toEpochMilli}"

        checkCount(filter, expected.length)

        org.apache.openwhisk.utils.retry {
          Get(s"$collectionPath?docs=true&$filter") ~> Route.seal(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[List[JsObject]]
            expected.length should be(response.length)
            response should contain theSameElementsAs expected.map(_.toExtendedJson())
          }
        }
      }

      { // get 'since' with no defined upto value should return all activation 'since'
        org.apache.openwhisk.utils.retry {
          val expected = activations.filter(e => e.start.equals(since) || e.start.isAfter(since))
          val filter = s"since=${since.toEpochMilli}"

          checkCount(filter, expected.length)

          Get(s"$collectionPath?docs=true&$filter") ~> Route.seal(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[List[JsObject]]
            expected.length should be(response.length)
            response should contain theSameElementsAs expected.map(_.toExtendedJson())
          }
        }
      }

    } finally {
      (notExpectedActivations ++ activations).foreach(activation =>
        deleteActivation(ActivationId(activation.docid.asString), context))
    }
  }

  //// GET /activations?name=xyz
  it should "accept valid name parameters and reject invalid ones" in {
    implicit val tid = transid()

    Seq(("", OK), ("name=", OK), ("name=abc", OK), ("name=abc/xyz", OK), ("name=abc/xyz/123", BadRequest)).foreach {
      case (p, s) =>
        Get(s"$collectionPath?$p") ~> Route.seal(routes(creds)) ~> check {
          status should be(s)
          if (s == BadRequest) {
            responseAs[String] should include(Messages.badNameFilter(p.drop(5)))
          }
        }
    }
  }

  it should "get summary activation by namespace and action name" in {
    implicit val tid = transid()

    // create two sets of activation records, and check that only one set is served back
    val creds1 = WhiskAuthHelpers.newAuth()
    val notExpectedActivations = (1 to 2).map { i =>
      WhiskActivation(
        EntityPath(creds1.subject.asString),
        aname(),
        creds1.subject,
        ActivationId.generate(),
        start = Instant.now,
        end = Instant.now)
    }
    val activations = (1 to 2).map { i =>
      WhiskActivation(
        namespace,
        EntityName(s"xyz"),
        creds.subject,
        ActivationId.generate(),
        start = Instant.now,
        end = Instant.now)
    }.toList

    val activationsInPackage = (1 to 2).map { i =>
      WhiskActivation(
        namespace,
        EntityName(s"xyz"),
        creds.subject,
        ActivationId.generate(),
        start = Instant.now,
        end = Instant.now,
        annotations = Parameters("path", s"${namespace.asString}/pkg/xyz"))
    }.toList
    try {
      (notExpectedActivations ++ activations ++ activationsInPackage).foreach(storeActivation(_, false, false, context))
      waitOnListActivationsMatchingName(namespace, EntityPath("xyz"), activations.length, context)
      waitOnListActivationsMatchingName(
        namespace,
        EntityName("pkg").addPath(EntityName("xyz")),
        activations.length,
        context)
      checkCount("name=xyz", activations.length)

      org.apache.openwhisk.utils.retry {
        Get(s"$collectionPath?name=xyz") ~> Route.seal(routes(creds)) ~> check {
          status should be(OK)
          val response = responseAs[List[JsObject]]
          activations.length should be(response.length)
          response should contain theSameElementsAs activations.map(_.summaryAsJson)
        }
      }

      checkCount("name=pkg/xyz", activations.length)

      org.apache.openwhisk.utils.retry {
        Get(s"$collectionPath?name=pkg/xyz") ~> Route.seal(routes(creds)) ~> check {
          status should be(OK)
          val response = responseAs[List[JsObject]]
          activationsInPackage.length should be(response.length)
          response should contain theSameElementsAs activationsInPackage.map(_.summaryAsJson)
        }
      }
    } finally {
      (notExpectedActivations ++ activations ++ activationsInPackage).foreach(activation =>
        deleteActivation(ActivationId(activation.docid.asString), context))
    }

  }

  it should "reject invalid query parameter combinations" in {
    implicit val tid = transid()
    org.apache.openwhisk.utils
      .retry { // retry because view will be stale from previous test and result in null doc fields
        Get(s"$collectionPath?docs=true&count=true") ~> Route.seal(routes(creds)) ~> check {
          status should be(BadRequest)
          responseAs[ErrorResponse].error shouldBe Messages.docsNotAllowedWithCount
        }
      }
  }

  it should "reject list when limit is greater than maximum allowed value" in {
    implicit val tid = transid()
    val exceededMaxLimit = Collection.MAX_LIST_LIMIT + 1
    val response = Get(s"$collectionPath?limit=$exceededMaxLimit") ~> Route.seal(routes(creds)) ~> check {
      status should be(BadRequest)
      responseAs[String] should include {
        Messages.listLimitOutOfRange(Collection.ACTIVATIONS, exceededMaxLimit, Collection.MAX_LIST_LIMIT)
      }
    }
  }

  it should "reject list when limit is not an integer" in {
    implicit val tid = transid()
    val notAnInteger = "string"
    val response = Get(s"$collectionPath?limit=$notAnInteger") ~> Route.seal(routes(creds)) ~> check {
      status should be(BadRequest)
      responseAs[String] should include {
        Messages.argumentNotInteger(Collection.ACTIVATIONS, notAnInteger)
      }
    }
  }

  it should "reject list when skip is negative" in {
    implicit val tid = transid()
    val negativeSkip = -1
    val response = Get(s"$collectionPath?skip=$negativeSkip") ~> Route.seal(routes(creds)) ~> check {
      status should be(BadRequest)
      responseAs[String] should include {
        Messages.listSkipOutOfRange(Collection.ACTIVATIONS, negativeSkip)
      }
    }
  }

  it should "reject list when skip is not an integer" in {
    implicit val tid = transid()
    val notAnInteger = "string"
    val response = Get(s"$collectionPath?skip=$notAnInteger") ~> Route.seal(routes(creds)) ~> check {
      status should be(BadRequest)
      responseAs[String] should include {
        Messages.argumentNotInteger(Collection.ACTIVATIONS, notAnInteger)
      }
    }
  }

  it should "reject get activation by namespace and action name when action name is not a valid name" in {
    implicit val tid = transid()
    Get(s"$collectionPath?name=0%20") ~> Route.seal(routes(creds)) ~> check {
      status should be(BadRequest)
    }
  }

  it should "reject get activation with invalid since/upto value" in {
    implicit val tid = transid()
    Get(s"$collectionPath?since=xxx") ~> Route.seal(routes(creds)) ~> check {
      status should be(BadRequest)
    }
    Get(s"$collectionPath?upto=yyy") ~> Route.seal(routes(creds)) ~> check {
      status should be(BadRequest)
    }
  }

  it should "skip activations and return correct ones" in {
    implicit val tid = transid()
    val activations: Seq[WhiskActivation] = (1 to 3).map { i =>
      //make sure the time is different for each activation
      val time = Instant.now.plusMillis(i)
      WhiskActivation(namespace, aname(), creds.subject, ActivationId.generate(), start = time, end = time)
    }.toList

    try {
      activations.foreach(storeActivation(_, false, false, context))
      waitOnListActivationsInNamespace(namespace, activations.size, context)

      Get(s"$collectionPath?skip=1") ~> Route.seal(routes(creds)) ~> check {
        status should be(OK)
        val resultActivationIds = responseAs[List[JsObject]].map(_.fields("name"))
        val expectedActivationIds = activations.map(_.toJson.fields("name")).reverse.drop(1)
        resultActivationIds should be(expectedActivationIds)
      }
    } finally {
      activations.foreach(a => deleteActivation(ActivationId(a.docid.asString), context))
      waitOnListActivationsInNamespace(namespace, 0, context)
    }
  }

  it should "return last activation" in {
    implicit val tid = transid()
    val activations = (1 to 3).map { i =>
      //make sure the time is different for each activation
      val time = Instant.now.plusMillis(i)
      WhiskActivation(namespace, aname(), creds.subject, ActivationId.generate(), start = time, end = time)
    }.toList

    try {
      activations.foreach(storeActivation(_, false, false, context))
      waitOnListActivationsInNamespace(namespace, activations.size, context)

      Get(s"$collectionPath?limit=1") ~> Route.seal(routes(creds)) ~> check {
        status should be(OK)
        val activationsJson = activations.map(_.toJson)
        withClue(s"Original activations: ${activationsJson}") {
          val respNames = responseAs[List[JsObject]].map(_.fields("name"))
          val expectNames = activationsJson.map(_.fields("name")).drop(2)
          respNames should be(expectNames)
        }
      }
    } finally {
      activations.foreach(a => deleteActivation(ActivationId(a.docid.asString), context))
      waitOnListActivationsInNamespace(namespace, 0, context)
    }
  }

  //// GET /activations/id
  it should "get activation by id" in {
    implicit val tid = transid()
    val activation =
      WhiskActivation(
        namespace,
        aname(),
        creds.subject,
        ActivationId.generate(),
        start = Instant.now,
        end = Instant.now)
    try {
      storeActivation(activation, false, false, context)

      Get(s"$collectionPath/${activation.activationId.asString}") ~> Route.seal(routes(creds)) ~> check {
        status should be(OK)
        val response = responseAs[JsObject]
        response should be(activation.toExtendedJson())
      }

      // it should "get activation by name in explicit namespace owned by subject" in
      Get(s"/$namespace/${collection.path}/${activation.activationId.asString}") ~> Route.seal(routes(creds)) ~> check {
        status should be(OK)
        val response = responseAs[JsObject]
        response should be(activation.toExtendedJson())
      }

      // it should "reject get activation by name in explicit namespace not owned by subject" in
      val auser = WhiskAuthHelpers.newIdentity()
      Get(s"/$namespace/${collection.path}/${activation.activationId.asString}") ~> Route.seal(routes(auser)) ~> check {
        status should be(Forbidden)
      }
    } finally {
      deleteActivation(ActivationId(activation.docid.asString), context)
    }
  }

  //// GET /activations/id/result
  it should "get activation result by id" in {
    implicit val tid = transid()
    val activation =
      WhiskActivation(
        namespace,
        aname(),
        creds.subject,
        ActivationId.generate(),
        start = Instant.now,
        end = Instant.now)
    try {
      storeActivation(activation, false, false, context)

      Get(s"$collectionPath/${activation.activationId.asString}/result") ~> Route.seal(routes(creds)) ~> check {
        status should be(OK)
        val response = responseAs[JsObject]
        response should be(activation.response.toExtendedJson)
      }
    } finally {
      deleteActivation(ActivationId(activation.docid.asString), context)
    }
  }

  //// GET /activations/id/result when db store is disabled
  it should "return activation empty when db store is disabled" in {
    implicit val tid = transid()
    val activation =
      WhiskActivation(
        namespace,
        aname(),
        creds.subject,
        ActivationId.generate(),
        start = Instant.now,
        end = Instant.now)

    storeActivation(activation, true, true, context)

    Get(s"$collectionPath/${activation.activationId.asString}/result") ~> Route.seal(routes(creds)) ~> check {
      status should be(NotFound)
    }
  }

  //// GET /activations/id/result when store is disabled and activation is not blocking
  it should "get activation result by id when db store is disabled and activation is not blocking" in {
    implicit val tid = transid()
    val activation =
      WhiskActivation(
        namespace,
        aname(),
        creds.subject,
        ActivationId.generate(),
        start = Instant.now,
        end = Instant.now)
    try {
      storeActivation(activation, false, true, context)

      Get(s"$collectionPath/${activation.activationId.asString}/result") ~> Route.seal(routes(creds)) ~> check {
        status should be(OK)
        val response = responseAs[JsObject]
        response should be(activation.response.toExtendedJson)
      }
    } finally {
      deleteActivation(ActivationId(activation.docid.asString), context)
    }
  }

  //// GET /activations/id/result when store is disabled and activation is unsuccessful
  it should "get activation result by id when db store is disabled and activation is unsuccessful" in {
    implicit val tid = transid()
    val activation =
      WhiskActivation(
        namespace,
        aname(),
        creds.subject,
        ActivationId.generate(),
        start = Instant.now,
        end = Instant.now,
        response = ActivationResponse.whiskError("activation error"))
    try {
      storeActivation(activation, true, true, context)

      Get(s"$collectionPath/${activation.activationId.asString}/result") ~> Route.seal(routes(creds)) ~> check {
        status should be(OK)
        val response = responseAs[JsObject]
        response should be(activation.response.toExtendedJson)
      }
    } finally {
      deleteActivation(ActivationId(activation.docid.asString), context)
    }
  }

  //// GET /activations/id/logs
  it should "get activation logs by id" in {
    implicit val tid = transid()
    val activation =
      WhiskActivation(
        namespace,
        aname(),
        creds.subject,
        ActivationId.generate(),
        start = Instant.now,
        end = Instant.now)
    try {
      storeActivation(activation, false, false, context)

      Get(s"$collectionPath/${activation.activationId.asString}/logs") ~> Route.seal(routes(creds)) ~> check {
        status should be(OK)
        val response = responseAs[JsObject]
        response should be(activation.logs.toJsonObject)
      }
    } finally {
      deleteActivation(ActivationId(activation.docid.asString), context)
    }
  }

  //// GET /activations/id/bogus
  it should "reject request to get invalid activation resource" in {
    implicit val tid = transid()
    val activation =
      WhiskActivation(
        namespace,
        aname(),
        creds.subject,
        ActivationId.generate(),
        start = Instant.now,
        end = Instant.now)
    storeActivation(activation, false, false, context)
    try {

      Get(s"$collectionPath/${activation.activationId.asString}/bogus") ~> Route.seal(routes(creds)) ~> check {
        status should be(NotFound)
      }
    } finally {
      deleteActivation(ActivationId(activation.docid.asString), context)
    }
  }

  it should "reject get requests with invalid activation ids" in {
    implicit val tid = transid()
    val activationId = ActivationId.generate().toString
    val tooshort = activationId.substring(0, 31)
    val toolong = activationId + "xxx"
    val malformed = tooshort + "z"

    Get(s"$collectionPath/$tooshort") ~> Route.seal(routes(creds)) ~> check {
      status should be(BadRequest)
      responseAs[String] shouldBe Messages.activationIdLengthError(SizeError("Activation id", tooshort.length.B, 32.B))
    }

    Get(s"$collectionPath/$toolong") ~> Route.seal(routes(creds)) ~> check {
      status should be(BadRequest)
      responseAs[String] shouldBe Messages.activationIdLengthError(SizeError("Activation id", toolong.length.B, 32.B))
    }

    Get(s"$collectionPath/$malformed") ~> Route.seal(routes(creds)) ~> check {
      status should be(BadRequest)
    }
  }

  it should "reject request with put" in {
    implicit val tid = transid()
    Put(s"$collectionPath/${ActivationId.generate()}") ~> Route.seal(routes(creds)) ~> check {
      status should be(MethodNotAllowed)
    }
  }

  it should "reject request with post" in {
    implicit val tid = transid()
    Post(s"$collectionPath/${ActivationId.generate()}") ~> Route.seal(routes(creds)) ~> check {
      status should be(MethodNotAllowed)
    }
  }

  it should "reject request with delete" in {
    implicit val tid = transid()
    Delete(s"$collectionPath/${ActivationId.generate()}") ~> Route.seal(routes(creds)) ~> check {
      status should be(MethodNotAllowed)
    }
  }

  it should "report proper error when record is corrupted on get" in {
    implicit val tid = transid()

    //A bad activation type which breaks the deserialization by removing the subject entry
    class BadActivation(override val namespace: EntityPath,
                        override val name: EntityName,
                        override val subject: Subject,
                        override val activationId: ActivationId,
                        override val start: Instant,
                        override val end: Instant)
        extends WhiskActivation(namespace, name, subject, activationId, start, end) {
      override def toJson = {
        val json = super.toJson
        JsObject(json.fields - "subject")
      }
    }

    val activation =
      new BadActivation(namespace, aname(), creds.subject, ActivationId.generate(), Instant.now, Instant.now)
    storeActivation(activation, false, false, context)

    Get(s"$collectionPath/${activation.activationId}") ~> Route.seal(routes(creds)) ~> check {
      status should be(InternalServerError)
      responseAs[ErrorResponse].error shouldBe Messages.corruptedEntity
    }
  }
}
