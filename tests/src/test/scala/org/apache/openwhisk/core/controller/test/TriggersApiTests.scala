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

import java.time.Instant

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.headers.RawHeader
import spray.json._
import spray.json.DefaultJsonProtocol._

import org.apache.openwhisk.core.controller.WhiskTriggersApi
import org.apache.openwhisk.core.entitlement.Collection
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.entity.WhiskRule
import org.apache.openwhisk.core.entity.size._
import org.apache.openwhisk.core.entity.test.OldWhiskTrigger
import org.apache.openwhisk.http.ErrorResponse
import org.apache.openwhisk.http.Messages
import org.apache.openwhisk.core.database.UserContext

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

  val creds = WhiskAuthHelpers.newIdentity()
  val context = UserContext(creds)
  val namespace = EntityPath(creds.subject.asString)
  val collectionPath = s"/${EntityPath.DEFAULT}/${collection.path}"
  def aname() = MakeName.next("triggers_tests")
  def afullname(namespace: EntityPath, name: String) = FullyQualifiedEntityName(namespace, EntityName(name))
  val parametersLimit = Parameters.sizeLimit
  val dummyInstant = Instant.now()

  //// GET /triggers
  it should "list triggers by default/explicit namespace" in {
    implicit val tid = transid()
    val triggers = (1 to 2).map { i =>
      WhiskTrigger(namespace, aname(), Parameters("x", "b"))
    }.toList
    triggers foreach { put(entityStore, _) }
    waitOnView(entityStore, WhiskTrigger, namespace, 2)
    Get(s"$collectionPath") ~> Route.seal(routes(creds)) ~> check {
      status should be(OK)
      val response = responseAs[List[JsObject]]
      triggers.length should be(response.length)
      response should contain theSameElementsAs triggers.map(_.summaryAsJson)
    }

    // it should "list triggers with explicit namespace owned by subject" in {
    Get(s"/$namespace/${collection.path}") ~> Route.seal(routes(creds)) ~> check {
      status should be(OK)
      val response = responseAs[List[JsObject]]
      triggers.length should be(response.length)
      response should contain theSameElementsAs triggers.map(_.summaryAsJson)
    }

    // it should "reject list triggers with explicit namespace not owned by subject" in {
    val auser = WhiskAuthHelpers.newIdentity()
    Get(s"/$namespace/${collection.path}") ~> Route.seal(routes(auser)) ~> check {
      status should be(Forbidden)
    }
  }

  it should "reject list when limit is greater than maximum allowed value" in {
    implicit val tid = transid()
    val exceededMaxLimit = Collection.MAX_LIST_LIMIT + 1
    val response = Get(s"$collectionPath?limit=$exceededMaxLimit") ~> Route.seal(routes(creds)) ~> check {
      status should be(BadRequest)
      responseAs[String] should include {
        Messages.listLimitOutOfRange(Collection.TRIGGERS, exceededMaxLimit, Collection.MAX_LIST_LIMIT)
      }
    }
  }

  it should "reject list when limit is not an integer" in {
    implicit val tid = transid()
    val notAnInteger = "string"
    val response = Get(s"$collectionPath?limit=$notAnInteger") ~> Route.seal(routes(creds)) ~> check {
      status should be(BadRequest)
      responseAs[String] should include {
        Messages.argumentNotInteger(Collection.TRIGGERS, notAnInteger)
      }
    }
  }

  it should "reject list when skip is negative" in {
    implicit val tid = transid()
    val negativeSkip = -1
    val response = Get(s"$collectionPath?skip=$negativeSkip") ~> Route.seal(routes(creds)) ~> check {
      status should be(BadRequest)
      responseAs[String] should include {
        Messages.listSkipOutOfRange(Collection.TRIGGERS, negativeSkip)
      }
    }
  }

  it should "reject list when skip is not an integer" in {
    implicit val tid = transid()
    val notAnInteger = "string"
    val response = Get(s"$collectionPath?skip=$notAnInteger") ~> Route.seal(routes(creds)) ~> check {
      status should be(BadRequest)
      responseAs[String] should include {
        Messages.argumentNotInteger(Collection.TRIGGERS, notAnInteger)
      }
    }
  }

  // ?docs disabled
  ignore should "list triggers by default namespace with full docs" in {
    implicit val tid = transid()
    val triggers = (1 to 2).map { i =>
      WhiskTrigger(namespace, aname(), Parameters("x", "b"))
    }.toList
    triggers foreach { put(entityStore, _) }
    waitOnView(entityStore, WhiskTrigger, namespace, 2)
    Get(s"$collectionPath?docs=true") ~> Route.seal(routes(creds)) ~> check {
      status should be(OK)
      val response = responseAs[List[WhiskTrigger]]
      triggers.length should be(response.length)
      response should contain theSameElementsAs triggers.map(_.summaryAsJson)
    }
  }

  //// GET /triggers/name
  it should "get trigger by name in default/explicit namespace" in {
    implicit val tid = transid()
    val trigger = WhiskTrigger(namespace, aname(), Parameters("x", "b"))
    put(entityStore, trigger)
    Get(s"$collectionPath/${trigger.name}") ~> Route.seal(routes(creds)) ~> check {
      status should be(OK)
      val response = responseAs[WhiskTrigger]
      response should be(trigger)
    }

    // it should "get trigger by name in explicit namespace owned by subject" in
    Get(s"/$namespace/${collection.path}/${trigger.name}") ~> Route.seal(routes(creds)) ~> check {
      status should be(OK)
      val response = responseAs[WhiskTrigger]
      response should be(trigger)
    }

    // it should "reject get trigger by name in explicit namespace not owned by subject" in
    val auser = WhiskAuthHelpers.newIdentity()
    Get(s"/$namespace/${collection.path}/${trigger.name}") ~> Route.seal(routes(auser)) ~> check {
      status should be(Forbidden)
    }
  }

  it should "get trigger with updated field" in {
    implicit val tid = transid()
    val trigger = WhiskTrigger(namespace, aname(), Parameters("x", "b"))
    put(entityStore, trigger)

    // `updated` field should be compared with a document in DB
    val t = get(entityStore, trigger.docid, WhiskTrigger)

    Get(s"$collectionPath/${trigger.name}") ~> Route.seal(routes(creds)) ~> check {
      status should be(OK)
      val response = responseAs[WhiskTrigger]
      response should be(trigger copy (updated = t.updated))
    }
  }

  it should "report Conflict if the name was of a different type" in {
    implicit val tid = transid()
    val rule = WhiskRule(
      namespace,
      aname(),
      FullyQualifiedEntityName(namespace, aname()),
      FullyQualifiedEntityName(namespace, aname()))
    put(entityStore, rule)
    Get(s"/$namespace/${collection.path}/${rule.name}") ~> Route.seal(routes(creds)) ~> check {
      status should be(Conflict)
    }
  }

  //// DEL /triggers/name
  it should "delete trigger by name" in {
    implicit val tid = transid()
    val trigger = WhiskTrigger(namespace, aname(), Parameters("x", "b"))
    put(entityStore, trigger)
    Delete(s"$collectionPath/${trigger.name}") ~> Route.seal(routes(creds)) ~> check {
      status should be(OK)
      val response = responseAs[WhiskTrigger]
      response should be(trigger)
    }
  }

  //// PUT /triggers/name
  it should "put should accept request with missing optional properties" in {
    implicit val tid = transid()
    val trigger = WhiskTrigger(namespace, aname())
    val content = WhiskTriggerPut()
    Put(s"$collectionPath/${trigger.name}", content) ~> Route.seal(routes(creds)) ~> check {
      deleteTrigger(trigger.docid)
      status should be(OK)
      val response = responseAs[WhiskTrigger]
      checkWhiskEntityResponse(response, trigger.withoutRules)
    }
  }

  it should "put should accept request with valid feed parameter" in {
    implicit val tid = transid()
    val trigger = WhiskTrigger(namespace, aname(), annotations = Parameters(Parameters.Feed, "xyz"))
    val content = WhiskTriggerPut(annotations = Some(trigger.annotations))
    Put(s"$collectionPath/${trigger.name}", content) ~> Route.seal(routes(creds)) ~> check {
      deleteTrigger(trigger.docid)
      status should be(OK)
      val response = responseAs[WhiskTrigger]
      checkWhiskEntityResponse(response, trigger.withoutRules)
    }
  }

  it should "put should reject request with undefined feed parameter" in {
    implicit val tid = transid()
    val trigger = WhiskTrigger(namespace, aname(), annotations = Parameters(Parameters.Feed, ""))
    val content = WhiskTriggerPut(annotations = Some(trigger.annotations))
    Put(s"$collectionPath/${trigger.name}", content) ~> Route.seal(routes(creds)) ~> check {
      status should be(BadRequest)
    }
  }

  it should "put should reject request with bad feed parameters" in {
    implicit val tid = transid()
    val trigger = WhiskTrigger(namespace, aname(), annotations = Parameters(Parameters.Feed, "a,b"))
    val content = WhiskTriggerPut(annotations = Some(trigger.annotations))
    Put(s"$collectionPath/${trigger.name}", content) ~> Route.seal(routes(creds)) ~> check {
      status should be(BadRequest)
    }
  }

  it should "reject activation with entity which is too big" in {
    implicit val tid = transid()
    val code = "a" * (allowedActivationEntitySize.toInt + 1)
    val content = s"""{"a":"$code"}""".stripMargin
    Post(s"$collectionPath/${aname()}", content.parseJson.asJsObject) ~> Route.seal(routes(creds)) ~> check {
      status should be(PayloadTooLarge)
      responseAs[String] should include {
        Messages.entityTooBig(
          SizeError(fieldDescriptionForSizeError, (content.length).B, allowedActivationEntitySize.B))
      }
    }
  }

  it should "reject create with parameters which are too big" in {
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

  it should "reject create with annotations which are too big" in {
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

  it should "reject update with parameters which are too big" in {
    implicit val tid = transid()
    val trigger = WhiskTrigger(namespace, aname())
    val keys: List[Long] =
      List.range(Math.pow(10, 9) toLong, (parametersLimit.toBytes / 20 + Math.pow(10, 9) + 2) toLong)
    val parameters = keys map { key =>
      Parameters(key.toString, "a" * 10)
    } reduce (_ ++ _)
    val content = s"""{"parameters":$parameters}""".parseJson.asJsObject
    put(entityStore, trigger)
    Put(s"$collectionPath/${trigger.name}?overwrite=true", content) ~> Route.seal(routes(creds)) ~> check {
      status should be(PayloadTooLarge)
      responseAs[String] should include {
        Messages.entityTooBig(SizeError(WhiskEntity.paramsFieldName, parameters.size, Parameters.sizeLimit))
      }
    }
  }

  it should "put should accept update request with missing optional properties" in {
    implicit val tid = transid()
    val trigger = WhiskTrigger(namespace, aname(), Parameters("x", "b"))
    val content = WhiskTriggerPut()
    put(entityStore, trigger)
    Put(s"$collectionPath/${trigger.name}?overwrite=true", content) ~> Route.seal(routes(creds)) ~> check {
      deleteTrigger(trigger.docid)
      status should be(OK)
      val response = responseAs[WhiskTrigger]
      checkWhiskEntityResponse(
        response,
        WhiskTrigger(
          trigger.namespace,
          trigger.name,
          trigger.parameters,
          version = trigger.version.upPatch,
          updated = dummyInstant).withoutRules)
    }
  }

  it should "put should reject update request for trigger with existing feed" in {
    implicit val tid = transid()
    val trigger = WhiskTrigger(namespace, aname(), annotations = Parameters(Parameters.Feed, "xyz"))
    val content = WhiskTriggerPut(annotations = Some(trigger.annotations))
    put(entityStore, trigger)
    Put(s"$collectionPath/${trigger.name}?overwrite=true", content) ~> Route.seal(routes(creds)) ~> check {
      deleteTrigger(trigger.docid)
      status should be(OK)
    }
  }

  it should "put should reject update request for trigger with new feed" in {
    implicit val tid = transid()
    val trigger = WhiskTrigger(namespace, aname())
    val content = WhiskTriggerPut(annotations = Some(Parameters(Parameters.Feed, "xyz")))
    put(entityStore, trigger)
    Put(s"$collectionPath/${trigger.name}?overwrite=true", content) ~> Route.seal(routes(creds)) ~> check {
      deleteTrigger(trigger.docid)
      status should be(OK)
    }
  }

  //// POST /triggers/name
  it should "fire a trigger" in {
    implicit val tid = transid()
    val rule = WhiskRule(namespace, aname(), afullname(namespace, aname().name), afullname(namespace, "bogus action"))
    val trigger = WhiskTrigger(namespace, rule.trigger.name, rules = Some {
      Map(rule.fullyQualifiedName(false) -> ReducedRule(rule.action, Status.ACTIVE))
    })
    val content = JsObject("xxx" -> "yyy".toJson)
    put(entityStore, trigger)
    put(entityStore, rule)
    Post(s"$collectionPath/${trigger.name}", content) ~> Route.seal(routes(creds)) ~> check {
      status should be(Accepted)
      val response = responseAs[JsObject]
      val JsString(id) = response.fields("activationId")
      val activationId = ActivationId.parse(id).get
      response.fields("activationId") should not be None
      headers should contain(RawHeader(ActivationIdHeader, response.fields("activationId").convertTo[String]))

      val activationDoc = DocId(WhiskEntity.qualifiedName(namespace, activationId))
      org.apache.openwhisk.utils.retry({
        println(s"trying to obtain async activation doc: '${activationDoc}'")

        val activation = getActivation(ActivationId(activationDoc.asString), context)
        deleteActivation(ActivationId(activationDoc.asString), context)
        activation.end should be(Instant.EPOCH)
        activation.response.result should be(Some(content))
      }, 30, Some(1.second))
    }
  }

  it should "fire a trigger without args" in {
    implicit val tid = transid()
    val rule = WhiskRule(namespace, aname(), afullname(namespace, aname().name), afullname(namespace, "bogus action"))
    val trigger = WhiskTrigger(namespace, rule.trigger.name, Parameters("x", "b"), rules = Some {
      Map(rule.fullyQualifiedName(false) -> ReducedRule(rule.action, Status.ACTIVE))
    })
    put(entityStore, trigger)
    put(entityStore, rule)
    Post(s"$collectionPath/${trigger.name}") ~> Route.seal(routes(creds)) ~> check {
      val response = responseAs[JsObject]
      val JsString(id) = response.fields("activationId")
      val activationId = ActivationId.parse(id).get
      val activationDoc = DocId(WhiskEntity.qualifiedName(namespace, activationId))
      org.apache.openwhisk.utils.retry({
        println(s"trying to delete async activation doc: '${activationDoc}'")
        deleteActivation(ActivationId(activationDoc.asString), context)
        response.fields("activationId") should not be None
        headers should contain(RawHeader(ActivationIdHeader, response.fields("activationId").convertTo[String]))
      }, 30, Some(1.second))
    }
  }

  it should "not fire a trigger without a rule" in {
    implicit val tid = transid()
    val trigger = WhiskTrigger(namespace, aname())
    put(entityStore, trigger)
    Post(s"$collectionPath/${trigger.name}") ~> Route.seal(routes(creds)) ~> check {
      status shouldBe NoContent
    }
  }

  //// invalid resource
  it should "reject invalid resource" in {
    implicit val tid = transid()
    val trigger = WhiskTrigger(namespace, aname())
    put(entityStore, trigger)
    Get(s"$collectionPath/${trigger.name}/bar") ~> Route.seal(routes(creds)) ~> check {
      status should be(NotFound)
    }
  }

  // migration path
  it should "be able to handle a trigger as of the old schema" in {
    implicit val tid = transid()
    val trigger = OldWhiskTrigger(namespace, aname())
    put(entityStore, trigger)
    Get(s"$collectionPath/${trigger.name}") ~> Route.seal(routes(creds)) ~> check {
      val response = responseAs[WhiskTrigger]
      status should be(OK)
      checkWhiskEntityResponse(response, trigger.toWhiskTrigger)
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
      responseAs[ErrorResponse].error shouldBe Messages.corruptedEntity
    }
  }

  it should "report proper error when record is corrupted on put" in {
    implicit val tid = transid()
    val entity = BadEntity(namespace, aname())
    put(entityStore, entity)

    val content = WhiskTriggerPut()
    Put(s"$collectionPath/${entity.name}", content) ~> Route.seal(routes(creds)) ~> check {
      status should be(InternalServerError)
      responseAs[ErrorResponse].error shouldBe Messages.corruptedEntity
    }
  }
}
