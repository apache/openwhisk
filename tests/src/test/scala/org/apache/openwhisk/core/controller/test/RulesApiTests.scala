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

import scala.language.postfixOps
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.server.Route
import spray.json.DefaultJsonProtocol._
import spray.json._
import org.apache.openwhisk.core.controller.WhiskRulesApi
import org.apache.openwhisk.core.entitlement.Collection
import org.apache.openwhisk.core.entity.{WhiskRuleResponse, _}
import org.apache.openwhisk.core.entity.test.OldWhiskTrigger
import org.apache.openwhisk.http.ErrorResponse

import scala.language.postfixOps
import org.apache.openwhisk.core.entity.test.OldWhiskRule
import org.apache.openwhisk.http.Messages

/**
 * Tests Rules API.
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
class RulesApiTests extends ControllerTestCommon with WhiskRulesApi {

  /** Rules API tests */
  behavior of "Rules API"

  val creds = WhiskAuthHelpers.newIdentity()
  val namespace = EntityPath(creds.subject.asString)
  def aname() = MakeName.next("rules_tests")
  def afullname(namespace: EntityPath, name: String) = FullyQualifiedEntityName(namespace, EntityName(name))
  val collectionPath = s"/${EntityPath.DEFAULT}/${collection.path}"
  val activeStatus = s"""{"status":"${Status.ACTIVE}"}""".parseJson.asJsObject
  val inactiveStatus = s"""{"status":"${Status.INACTIVE}"}""".parseJson.asJsObject
  val parametersLimit = Parameters.sizeLimit
  val dummyInstant = Instant.now()

  def checkResponse(response: WhiskRuleResponse, expected: WhiskRuleResponse) =
    // ignore `updated` field because another test covers it
    response should be(expected copy (updated = response.updated))

  //// GET /rules
  it should "list rules by default/explicit namespace" in {
    implicit val tid = transid()

    val rules = (1 to 2).map { i =>
      WhiskRule(namespace, aname(), afullname(namespace, "bogus trigger"), afullname(namespace, "bogus action"))
    }.toList
    rules foreach { put(entityStore, _) }
    waitOnView(entityStore, WhiskRule, namespace, 2, includeDocs = true)
    Get(s"$collectionPath") ~> Route.seal(routes(creds)) ~> check {
      status should be(OK)
      val response = responseAs[List[JsObject]]
      rules.length should be(response.length)
      response should contain theSameElementsAs rules.map(_.toJson)
    }

    // it should "list rules with explicit namespace owned by subject" in {
    Get(s"/$namespace/${collection.path}") ~> Route.seal(routes(creds)) ~> check {
      status should be(OK)
      val response = responseAs[List[JsObject]]
      rules.length should be(response.length)
      response should contain theSameElementsAs rules.map(_.toJson)
    }

    // it should "reject list rules with explicit namespace not owned by subject" in {
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
        Messages.listLimitOutOfRange(Collection.RULES, exceededMaxLimit, Collection.MAX_LIST_LIMIT)
      }
    }
  }

  it should "reject list when limit is not an integer" in {
    implicit val tid = transid()
    val notAnInteger = "string"
    val response = Get(s"$collectionPath?limit=$notAnInteger") ~> Route.seal(routes(creds)) ~> check {
      status should be(BadRequest)
      responseAs[String] should include {
        Messages.argumentNotInteger(Collection.RULES, notAnInteger)
      }
    }
  }

  it should "reject list when skip is negative" in {
    implicit val tid = transid()
    val negativeSkip = -1
    val response = Get(s"$collectionPath?skip=$negativeSkip") ~> Route.seal(routes(creds)) ~> check {
      status should be(BadRequest)
      responseAs[String] should include {
        Messages.listSkipOutOfRange(Collection.RULES, negativeSkip)
      }
    }
  }

  it should "reject list when skip is not an integer" in {
    implicit val tid = transid()
    val notAnInteger = "string"
    val response = Get(s"$collectionPath?skip=$notAnInteger") ~> Route.seal(routes(creds)) ~> check {
      status should be(BadRequest)
      responseAs[String] should include {
        Messages.argumentNotInteger(Collection.RULES, notAnInteger)
      }
    }
  }

  //?docs disabled
  ignore should "list rules by default namespace with full docs" in {
    implicit val tid = transid()

    val rules = (1 to 2).map { i =>
      WhiskRule(namespace, aname(), afullname(namespace, "bogus trigger"), afullname(namespace, "bogus action"))
    }.toList
    rules foreach { put(entityStore, _) }
    waitOnView(entityStore, WhiskRule, namespace, 2, includeDocs = true)
    Get(s"$collectionPath?docs=true") ~> Route.seal(routes(creds)) ~> check {
      status should be(OK)
      val response = responseAs[List[WhiskRule]]
      rules.length should be(response.length)
      response should contain theSameElementsAs rules.map(_.toJson)
    }
  }

  //// GET /rule/name
  it should "get rule by name in default/explicit namespace" in {
    implicit val tid = transid()

    val rule =
      WhiskRule(namespace, aname(), afullname(namespace, "bogus trigger"), afullname(namespace, "bogus action"))
    put(entityStore, rule)
    Get(s"$collectionPath/${rule.name}") ~> Route.seal(routes(creds)) ~> check {
      status should be(OK)
      val response = responseAs[WhiskRuleResponse]
      checkResponse(response, rule.withStatus(Status.INACTIVE))
    }

    // it should "get trigger by name in explicit namespace owned by subject" in
    Get(s"/$namespace/${collection.path}/${rule.name}") ~> Route.seal(routes(creds)) ~> check {
      status should be(OK)
      val response = responseAs[WhiskRuleResponse]
      checkResponse(response, rule.withStatus(Status.INACTIVE))
    }

    // it should "reject get trigger by name in explicit namespace not owned by subject" in
    val auser = WhiskAuthHelpers.newIdentity()
    Get(s"/$namespace/${collection.path}/${rule.name}") ~> Route.seal(routes(auser)) ~> check {
      status should be(Forbidden)
    }
  }

  it should "reject get of non existent rule" in {
    implicit val tid = transid()

    Get(s"$collectionPath/xxx") ~> Route.seal(routes(creds)) ~> check {
      status should be(NotFound)
    }
  }

  it should "get rule with active state in trigger" in {
    implicit val tid = transid()

    val rule = WhiskRule(
      namespace,
      EntityName("get_active_rule"),
      afullname(namespace, "get_active_rule trigger"),
      afullname(namespace, "an action"))
    val trigger = WhiskTrigger(rule.trigger.path, rule.trigger.name, rules = Some {
      Map(rule.fullyQualifiedName(false) -> ReducedRule(rule.action, Status.ACTIVE))
    })

    put(entityStore, trigger)
    put(entityStore, rule)

    Get(s"$collectionPath/${rule.name}") ~> Route.seal(routes(creds)) ~> check {
      status should be(OK)
      val response = responseAs[WhiskRuleResponse]
      checkResponse(response, rule.withStatus(Status.ACTIVE))
    }
  }

  it should "get rule with updated field" in {
    implicit val tid = transid()

    val rule = WhiskRule(
      namespace,
      EntityName("get_active_rule"),
      afullname(namespace, "get_active_rule trigger"),
      afullname(namespace, "an action"))
    val trigger = WhiskTrigger(rule.trigger.path, rule.trigger.name, rules = Some {
      Map(rule.fullyQualifiedName(false) -> ReducedRule(rule.action, Status.ACTIVE))
    })

    put(entityStore, trigger)
    put(entityStore, rule)

    // `updated` field should be compared with a document in DB
    val r = get(entityStore, rule.docid, WhiskRule)

    Get(s"$collectionPath/${rule.name}") ~> Route.seal(routes(creds)) ~> check {
      status should be(OK)
      val response = responseAs[WhiskRuleResponse]
      response should be(rule.withStatus(Status.ACTIVE) copy (updated = r.updated))
    }
  }

  it should "get rule with no rule-entries in trigger" in {
    implicit val tid = transid()

    val trigger = WhiskTrigger(namespace, EntityName("get_rule_with_empty_trigger trigger"))
    val rule = WhiskRule(
      namespace,
      EntityName("get_rule_with_empty_trigger"),
      trigger.fullyQualifiedName(false),
      afullname(namespace, "an action"))

    put(entityStore, trigger)
    put(entityStore, rule)

    Get(s"$collectionPath/${rule.name}") ~> Route.seal(routes(creds)) ~> check {
      status should be(OK)
      val response = responseAs[WhiskRuleResponse]
      checkResponse(response, rule.withStatus(Status.INACTIVE))
    }
  }

  it should "report Conflict if the name was of a different type" in {
    implicit val tid = transid()

    val trigger = WhiskTrigger(namespace, aname())

    put(entityStore, trigger)

    Get(s"/$namespace/${collection.path}/${trigger.name}") ~> Route.seal(routes(creds)) ~> check {
      status should be(Conflict)
    }
  }

  // DEL /rules/name
  it should "not reject delete rule in state active" in {
    implicit val tid = transid()

    val rule = WhiskRule(
      namespace,
      EntityName("reject_delete_rule_active"),
      FullyQualifiedEntityName(namespace, aname()),
      afullname(namespace, "an action"))
    val trigger = WhiskTrigger(namespace, rule.trigger.name, rules = Some {
      Map(rule.fullyQualifiedName(false) -> ReducedRule(rule.action, Status.ACTIVE))
    })

    put(entityStore, trigger)
    put(entityStore, rule)

    Delete(s"$collectionPath/${rule.name}") ~> Route.seal(routes(creds)) ~> check {
      deleteTrigger(trigger.docid)

      status should be(OK)
      val response = responseAs[WhiskRuleResponse]
      checkResponse(response, rule.withStatus(Status.INACTIVE))
    }
  }

  it should "delete rule in state inactive" in {
    implicit val tid = transid()

    val rule = WhiskRule(
      namespace,
      aname(),
      FullyQualifiedEntityName(namespace, aname()),
      FullyQualifiedEntityName(namespace, aname()))
    val triggerLink = ReducedRule(rule.action, Status.INACTIVE)
    val trigger = WhiskTrigger(
      rule.trigger.path,
      rule.trigger.name,
      rules = Some(Map(rule.fullyQualifiedName(false) -> triggerLink)))

    put(entityStore, trigger, false)
    put(entityStore, rule)

    Delete(s"$collectionPath/${rule.name}") ~> Route.seal(routes(creds)) ~> check {
      val t = get(entityStore, trigger.docid, WhiskTrigger)
      deleteTrigger(t.docid)

      status should be(OK)
      t.rules.get.get(rule.fullyQualifiedName(false)) shouldBe None
      val response = responseAs[WhiskRuleResponse]
      checkResponse(response, rule.withStatus(Status.INACTIVE))
    }
  }

  it should "delete rule in state inactive even if the trigger has been deleted" in {
    implicit val tid = transid()

    val rule = WhiskRule(
      namespace,
      EntityName("delete_rule_inactive"),
      afullname(namespace, "a trigger"),
      afullname(namespace, "an action"))

    put(entityStore, rule)

    Delete(s"$collectionPath/${rule.name}") ~> Route.seal(routes(creds)) ~> check {
      status should be(OK)
      val response = responseAs[WhiskRuleResponse]
      checkResponse(response, rule.withStatus(Status.INACTIVE))
    }
  }

  it should "delete rule in state inactive even if the trigger has no reference to the rule" in {
    implicit val tid = transid()

    val rule =
      WhiskRule(namespace, aname(), FullyQualifiedEntityName(namespace, aname()), afullname(namespace, "an action"))
    val trigger = WhiskTrigger(rule.trigger.path, rule.trigger.name)

    put(entityStore, trigger, false)
    put(entityStore, rule)

    Delete(s"$collectionPath/${rule.name}") ~> Route.seal(routes(creds)) ~> check {
      deleteTrigger(trigger.docid)

      status should be(OK)
      val response = responseAs[WhiskRuleResponse]
      checkResponse(response, rule.withStatus(Status.INACTIVE))
    }
  }

  //// PUT /rules/name
  it should "create rule" in {
    implicit val tid = transid()

    val rule = WhiskRule(
      namespace,
      aname(),
      FullyQualifiedEntityName(namespace, aname()),
      FullyQualifiedEntityName(namespace, aname()))
    val trigger = WhiskTrigger(rule.trigger.path, rule.trigger.name)
    val action = WhiskAction(rule.action.path, rule.action.name, jsDefault("??"))
    val content = WhiskRulePut(Some(rule.trigger), Some(rule.action))

    put(entityStore, trigger, false)
    put(entityStore, action)

    Put(s"$collectionPath/${rule.name}", content) ~> Route.seal(routes(creds)) ~> check {
      val t = get(entityStore, trigger.docid, WhiskTrigger)
      deleteTrigger(t.docid)
      deleteRule(rule.docid)

      status should be(OK)
      val response = responseAs[WhiskRuleResponse]
      checkResponse(response, rule.withStatus(Status.ACTIVE))
      t.rules.get(rule.fullyQualifiedName(false)) shouldBe ReducedRule(action.fullyQualifiedName(false), Status.ACTIVE)
    }
  }

  it should "create rule without fully qualifying name" in {
    implicit val tid = transid()

    val rule = WhiskRule(
      namespace,
      aname(),
      FullyQualifiedEntityName(namespace, aname()),
      FullyQualifiedEntityName(namespace, aname()))
    val trigger = WhiskTrigger(rule.trigger.path, rule.trigger.name)
    val action = WhiskAction(rule.action.path, rule.action.name, jsDefault("??"))
    val content = JsObject(
      "trigger" -> JsString(s"/_/${trigger.name.asString}"),
      "action" -> JsString(s"/_/${action.name.asString}"))

    put(entityStore, trigger, false)
    put(entityStore, action)

    Put(s"$collectionPath/${rule.name}", content) ~> Route.seal(routes(creds)) ~> check {
      val t = get(entityStore, trigger.docid, WhiskTrigger)
      deleteTrigger(t.docid)
      deleteRule(rule.docid)

      status should be(OK)
      val response = responseAs[WhiskRuleResponse]
      checkResponse(response, rule.withStatus(Status.ACTIVE))
      t.rules.get(rule.fullyQualifiedName(false)) shouldBe ReducedRule(action.fullyQualifiedName(false), Status.ACTIVE)
    }
  }

  it should "reject create rule without namespace in referenced entities" in {
    implicit val tid = transid()

    val rule = WhiskRule(
      namespace,
      aname(),
      FullyQualifiedEntityName(namespace, aname()),
      FullyQualifiedEntityName(namespace, aname()))
    val trigger = WhiskTrigger(rule.trigger.path, rule.trigger.name)
    val action = WhiskAction(rule.action.path, rule.action.name, jsDefault("??"))
    val contentT =
      JsObject("trigger" -> trigger.name.toJson, "action" -> action.fullyQualifiedName(false).toDocId.toJson)
    val contentA =
      JsObject("action" -> action.name.toJson, "trigger" -> trigger.fullyQualifiedName(false).toDocId.toJson)

    Put(s"$collectionPath/${rule.name}", contentT) ~> Route.seal(routes(creds)) ~> check {
      status should be(BadRequest)
      responseAs[String] shouldBe s"The request content was malformed:\nrequirement failed: ${Messages.malformedFullyQualifiedEntityName}"
    }

    Put(s"$collectionPath/${rule.name}", contentA) ~> Route.seal(routes(creds)) ~> check {
      status should be(BadRequest)
      responseAs[String] shouldBe s"The request content was malformed:\nrequirement failed: ${Messages.malformedFullyQualifiedEntityName}"
    }
  }

  it should "create rule with an action in a package" in {
    implicit val tid = transid()

    val provider = WhiskPackage(namespace, aname(), publish = true)
    val action = WhiskAction(provider.fullPath, aname(), jsDefault("??"))
    val trigger = WhiskTrigger(namespace, aname())
    val rule = WhiskRule(namespace, aname(), trigger.fullyQualifiedName(false), action.fullyQualifiedName(false))
    val content = WhiskRulePut(Some(rule.trigger), Some(rule.action))

    put(entityStore, provider)
    put(entityStore, trigger, false)
    put(entityStore, action)

    Put(s"$collectionPath/${rule.name}", content) ~> Route.seal(routes(creds)) ~> check {
      val t = get(entityStore, trigger.docid, WhiskTrigger)
      deleteTrigger(t.docid)
      deleteRule(rule.docid)

      status should be(OK)
      val response = responseAs[WhiskRuleResponse]
      checkResponse(response, rule.withStatus(Status.ACTIVE))
      t.rules.get(rule.fullyQualifiedName(false)) shouldBe ReducedRule(action.fullyQualifiedName(false), Status.ACTIVE)
    }
  }

  it should "create rule with an action in a binding" in {
    implicit val tid = transid()

    val provider = WhiskPackage(namespace, aname(), publish = true)
    val reference = WhiskPackage(namespace, aname(), provider.bind)
    val action = WhiskAction(provider.fullPath, aname(), jsDefault("??"))
    val trigger = WhiskTrigger(namespace, aname())
    val actionReference = reference.binding.map(b => b.namespace.addPath(b.name)).get
    val rule = WhiskRule(
      namespace,
      aname(),
      trigger.fullyQualifiedName(false),
      FullyQualifiedEntityName(actionReference, action.name))
    val content = WhiskRulePut(Some(rule.trigger), Some(rule.action))

    put(entityStore, provider)
    put(entityStore, reference)
    put(entityStore, trigger, false)
    put(entityStore, action)

    Put(s"$collectionPath/${rule.name}", content) ~> Route.seal(routes(creds)) ~> check {
      val t = get(entityStore, trigger.docid, WhiskTrigger)
      deleteTrigger(t.docid)
      deleteRule(rule.docid)

      status should be(OK)
      val response = responseAs[WhiskRuleResponse]
      checkResponse(response, rule.withStatus(Status.ACTIVE))
      t.rules.get(rule.fullyQualifiedName(false)) shouldBe ReducedRule(action.fullyQualifiedName(false), Status.ACTIVE)
    }
  }

  it should "reject create rule with annotations which are too big" in {
    implicit val tid = transid()

    val trigger = WhiskTrigger(namespace, aname())
    val action = WhiskAction(namespace, aname(), jsDefault("??"))

    val keys: List[Long] =
      List.range(Math.pow(10, 9) toLong, (Parameters.sizeLimit.toBytes / 20 + Math.pow(10, 9) + 2) toLong)
    val annotations = keys map { key =>
      Parameters(key.toString, "a" * 10)
    } reduce (_ ++ _)
    val content =
      s"""{"trigger":"${trigger.name}","action":"${action.name}","annotations":$annotations}""".parseJson.asJsObject

    put(entityStore, trigger, false)
    put(entityStore, action)

    Put(s"$collectionPath/${aname()}", content) ~> Route.seal(routes(creds)) ~> check {
      val t = get(entityStore, trigger.docid, WhiskTrigger)
      deleteTrigger(t.docid)

      status should be(PayloadTooLarge)
      responseAs[String] should include {
        Messages.entityTooBig(SizeError(WhiskEntity.annotationsFieldName, annotations.size, Parameters.sizeLimit))
      }
    }
  }

  it should "reject update rule with annotations which are too big" in {
    implicit val tid = transid()

    val trigger = WhiskTrigger(namespace, aname())
    val action = WhiskAction(namespace, aname(), jsDefault("??"))
    val rule = WhiskRule(namespace, aname(), trigger.fullyQualifiedName(false), action.fullyQualifiedName(false))

    val keys: List[Long] =
      List.range(Math.pow(10, 9) toLong, (Parameters.sizeLimit.toBytes / 20 + Math.pow(10, 9) + 2) toLong)
    val annotations = keys map { key =>
      Parameters(key.toString, "a" * 10)
    } reduce (_ ++ _)
    val content =
      s"""{"trigger":"${trigger.name}","action":"${action.name}","annotations":$annotations}""".parseJson.asJsObject

    put(entityStore, trigger, false)
    put(entityStore, action)
    put(entityStore, rule)

    Put(s"$collectionPath/${rule.name}?overwrite=true", content) ~> Route.seal(routes(creds)) ~> check {
      val t = get(entityStore, trigger.docid, WhiskTrigger)
      deleteTrigger(t.docid)

      status should be(PayloadTooLarge)
      responseAs[String] should include {
        Messages.entityTooBig(SizeError(WhiskEntity.annotationsFieldName, annotations.size, Parameters.sizeLimit))
      }
    }
  }

  it should "reject rule if action does not exist" in {
    implicit val tid = transid()

    val trigger = WhiskTrigger(namespace, aname())
    val content = WhiskRulePut(Some(trigger.fullyQualifiedName(false)), Some(afullname(namespace, "bogus action")))

    put(entityStore, trigger)

    Put(s"$collectionPath/xxx", content) ~> Route.seal(routes(creds)) ~> check {
      status should be(BadRequest)
      responseAs[String] === s"${content.action.get.qualifiedNameWithLeadingSlash} does not exist"
    }
  }

  it should "reject rule if trigger does not exist" in {
    implicit val tid = transid()

    val action = WhiskAction(namespace, aname(), jsDefault("??"))
    val content = WhiskRulePut(Some(afullname(namespace, "bogus trigger")), Some(action.fullyQualifiedName(false)))

    put(entityStore, action)

    Put(s"$collectionPath/xxx", content) ~> Route.seal(routes(creds)) ~> check {
      status should be(BadRequest)
      responseAs[String] === s"${content.trigger.get.qualifiedNameWithLeadingSlash} does not exist"
    }
  }

  it should "reject rule if neither action or trigger do not exist" in {
    implicit val tid = transid()

    val content = WhiskRulePut(Some(afullname(namespace, "bogus trigger")), Some(afullname(namespace, "bogus action")))

    Put(s"$collectionPath/xxx", content) ~> Route.seal(routes(creds)) ~> check {
      status should be(BadRequest)
      responseAs[String].contains("does not exist") should be(true)
    }
  }

  it should "update rule with new trigger and action at once" in {
    implicit val tid = transid()

    val trigger = WhiskTrigger(namespace, aname())
    val action = WhiskAction(namespace, aname(), jsDefault("??"))
    val rule =
      WhiskRule(namespace, aname(), afullname(namespace, "bogus trigger"), afullname(namespace, "bogus action"))
    val content = WhiskRulePut(Some(trigger.fullyQualifiedName(false)), Some(action.fullyQualifiedName(false)))

    put(entityStore, trigger, false)
    put(entityStore, action)
    put(entityStore, rule, false)

    Put(s"$collectionPath/${rule.name}?overwrite=true", content) ~> Route.seal(routes(creds)) ~> check {
      val t = get(entityStore, trigger.docid, WhiskTrigger)
      deleteTrigger(t.docid)
      deleteRule(rule.docid)

      status should be(OK)

      t.rules.get(rule.fullyQualifiedName(false)).action should be(action.fullyQualifiedName(false))
      val response = responseAs[WhiskRuleResponse]
      checkResponse(
        response,
        WhiskRuleResponse(
          namespace,
          rule.name,
          Status.INACTIVE,
          trigger.fullyQualifiedName(false),
          action.fullyQualifiedName(false),
          version = SemVer().upPatch,
          updated = dummyInstant))
    }
  }

  it should "update rule with a new action while passing the same trigger" in {
    implicit val tid = transid()

    val trigger = WhiskTrigger(namespace, aname())
    val action = WhiskAction(namespace, aname(), jsDefault("??"))
    val rule = WhiskRule(namespace, aname(), trigger.fullyQualifiedName(false), afullname(namespace, "bogus action"))
    val content = WhiskRulePut(Some(trigger.fullyQualifiedName(false)), Some(action.fullyQualifiedName(false)))

    put(entityStore, trigger, false)
    put(entityStore, action)
    put(entityStore, rule, false)

    Put(s"$collectionPath/${rule.name}?overwrite=true", content) ~> Route.seal(routes(creds)) ~> check {
      val t = get(entityStore, trigger.docid, WhiskTrigger)
      deleteTrigger(t.docid)
      deleteRule(rule.docid)

      status should be(OK)
      t.rules.get(rule.fullyQualifiedName(false)).action should be(action.fullyQualifiedName(false))
      val response = responseAs[WhiskRuleResponse]
      checkResponse(
        response,
        WhiskRuleResponse(
          namespace,
          rule.name,
          Status.INACTIVE,
          trigger.fullyQualifiedName(false),
          action.fullyQualifiedName(false),
          version = SemVer().upPatch,
          updated = dummyInstant))
    }
  }

  it should "update rule with just a new action" in {
    implicit val tid = transid()

    val trigger = WhiskTrigger(namespace, aname())
    val action = WhiskAction(namespace, aname(), jsDefault("??"))
    val rule = WhiskRule(namespace, aname(), trigger.fullyQualifiedName(false), afullname(namespace, "bogus action"))
    val content = WhiskRulePut(action = Some(action.fullyQualifiedName(false)))

    put(entityStore, trigger, false)
    put(entityStore, action)
    put(entityStore, rule, false)

    Put(s"$collectionPath/${rule.name}?overwrite=true", content) ~> Route.seal(routes(creds)) ~> check {
      val t = get(entityStore, trigger.docid, WhiskTrigger)
      deleteTrigger(t.docid)
      deleteRule(rule.docid)

      status should be(OK)
      t.rules.get(rule.fullyQualifiedName(false)).action should be(action.fullyQualifiedName(false))
      val response = responseAs[WhiskRuleResponse]
      checkResponse(
        response,
        WhiskRuleResponse(
          namespace,
          rule.name,
          Status.INACTIVE,
          trigger.fullyQualifiedName(false),
          action.fullyQualifiedName(false),
          version = SemVer().upPatch,
          updated = dummyInstant))
    }
  }

  it should "update rule with just a new trigger" in {
    implicit val tid = transid()

    val trigger = WhiskTrigger(namespace, aname())
    val action = WhiskAction(namespace, aname(), jsDefault("??"))
    val rule = WhiskRule(namespace, aname(), trigger.fullyQualifiedName(false), action.fullyQualifiedName(false))
    val content = WhiskRulePut(trigger = Some(trigger.fullyQualifiedName(false)))

    put(entityStore, trigger, false)
    put(entityStore, action)
    put(entityStore, rule, false)

    Put(s"$collectionPath/${rule.name}?overwrite=true", content) ~> Route.seal(routes(creds)) ~> check {
      val t = get(entityStore, trigger.docid, WhiskTrigger)
      deleteTrigger(t.docid)
      deleteRule(rule.docid)

      status should be(OK)
      t.rules.get.get(rule.fullyQualifiedName(false)) shouldBe a[Some[_]]
      val response = responseAs[WhiskRuleResponse]
      checkResponse(
        response,
        WhiskRuleResponse(
          namespace,
          rule.name,
          Status.INACTIVE,
          trigger.fullyQualifiedName(false),
          action.fullyQualifiedName(false),
          version = SemVer().upPatch,
          updated = dummyInstant))
    }
  }

  it should "update rule when no new content is provided" in {
    implicit val tid = transid()
    val trigger = WhiskTrigger(namespace, aname())
    val action = WhiskAction(namespace, aname(), jsDefault("??"))
    val rule = WhiskRule(namespace, aname(), trigger.fullyQualifiedName(false), action.fullyQualifiedName(false))
    val content = WhiskRulePut(None, None, None, None, None)

    put(entityStore, trigger, false)
    put(entityStore, action)
    put(entityStore, rule, false)

    Put(s"$collectionPath/${rule.name}?overwrite=true", content) ~> Route.seal(routes(creds)) ~> check {
      deleteTrigger(trigger.docid)
      deleteRule(rule.docid)

      status should be(OK)
      val response = responseAs[WhiskRuleResponse]
      checkResponse(
        response,
        WhiskRuleResponse(
          namespace,
          rule.name,
          Status.INACTIVE,
          trigger.fullyQualifiedName(false),
          action.fullyQualifiedName(false),
          version = SemVer().upPatch,
          updated = dummyInstant))
    }
  }

  it should "reject update rule if trigger does not exist" in {
    implicit val tid = transid()

    val action = WhiskAction(namespace, aname(), jsDefault("??"))
    val rule = WhiskRule(namespace, aname(), afullname(namespace, "bogus trigger"), action.fullyQualifiedName(false))
    val content = WhiskRulePut(action = Some(action.fullyQualifiedName(false)))

    put(entityStore, action)
    put(entityStore, rule)

    Put(s"$collectionPath/${rule.name}?overwrite=true", content) ~> Route.seal(routes(creds)) ~> check {
      status should be(BadRequest)
      responseAs[String] === s"${rule.trigger.qualifiedNameWithLeadingSlash} does not exist"
    }
  }

  it should "reject update rule if action does not exist" in {
    implicit val tid = transid()

    val trigger = WhiskTrigger(namespace, aname())
    val rule = WhiskRule(namespace, aname(), trigger.fullyQualifiedName(false), afullname(namespace, "bogus action"))
    val content = WhiskRulePut(trigger = Some(trigger.fullyQualifiedName(false)))

    put(entityStore, trigger)
    put(entityStore, rule)

    Put(s"$collectionPath/${rule.name}?overwrite=true", content) ~> Route.seal(routes(creds)) ~> check {
      status should be(BadRequest)
      responseAs[String] === s"${rule.action.qualifiedNameWithLeadingSlash} does not exist"
    }
  }

  it should "reject update rule if neither trigger or action exist" in {
    implicit val tid = transid()
    val rule =
      WhiskRule(namespace, aname(), afullname(namespace, "bogus trigger"), afullname(namespace, "bogus action"))
    val content = WhiskRulePut(None, None, None, None, None)

    put(entityStore, rule)

    Put(s"$collectionPath/${rule.name}?overwrite=true", content) ~> Route.seal(routes(creds)) ~> check {
      status should be(BadRequest)
      responseAs[String] should {
        include(s"${rule.action.qualifiedNameWithLeadingSlash} does not exist") or
          include(s"${rule.trigger.qualifiedNameWithLeadingSlash} does not exist")
      }
    }
  }

  it should "not reject update rule in state active" in {
    implicit val tid = transid()

    val rule = WhiskRule(namespace, aname(), afullname(namespace, "a trigger"), afullname(namespace, "an action"))
    val trigger = WhiskTrigger(namespace, rule.trigger.name, rules = Some {
      Map(rule.fullyQualifiedName(false) -> ReducedRule(rule.action, Status.ACTIVE))
    })
    val action = WhiskAction(namespace, aname(), jsDefault("??"))
    val content = WhiskRulePut(Some(trigger.fullyQualifiedName(false)), Some(action.fullyQualifiedName(false)))

    put(entityStore, trigger, false)
    put(entityStore, action)
    put(entityStore, rule, false)

    Put(s"$collectionPath/${rule.name}?overwrite=true", content) ~> Route.seal(routes(creds)) ~> check {
      val t = get(entityStore, trigger.docid, WhiskTrigger)
      deleteTrigger(t.docid)
      deleteRule(rule.docid)

      status should be(OK)
      t.rules.get(rule.fullyQualifiedName(false)).action should be(action.fullyQualifiedName(false))
      val response = responseAs[WhiskRuleResponse]
      checkResponse(
        response,
        WhiskRuleResponse(
          namespace,
          rule.name,
          Status.ACTIVE,
          trigger.fullyQualifiedName(false),
          action.fullyQualifiedName(false),
          version = SemVer().upPatch,
          updated = dummyInstant))
    }
  }

  //// POST /rules/name
  it should "do nothing to disable already disabled rule" in {
    implicit val tid = transid()

    val rule = WhiskRule(namespace, aname(), afullname(namespace, "a trigger"), afullname(namespace, "an action"))

    put(entityStore, rule)

    Post(s"$collectionPath/${rule.name}", inactiveStatus) ~> Route.seal(routes(creds)) ~> check {
      status should be(OK)
    }
  }

  it should "do nothing to enable already enabled rule" in {
    implicit val tid = transid()

    val rule = WhiskRule(namespace, aname(), afullname(namespace, aname().name), afullname(namespace, "an action"))
    val trigger = WhiskTrigger(namespace, rule.trigger.name, rules = Some {
      Map(rule.fullyQualifiedName(false) -> ReducedRule(rule.action, Status.ACTIVE))
    })

    put(entityStore, trigger)
    put(entityStore, rule)

    Post(s"$collectionPath/${rule.name}", activeStatus) ~> Route.seal(routes(creds)) ~> check {
      status should be(OK)
    }
  }

  it should "reject post with status undefined" in {
    implicit val tid = transid()

    Post(s"$collectionPath/xyz") ~> Route.seal(routes(creds)) ~> check {
      status should be(BadRequest)
    }
  }

  it should "reject post with invalid status" in {
    implicit val tid = transid()

    val badStatus = s"""{"status":"xxx"}""".parseJson.asJsObject

    Post(s"$collectionPath/xyz", badStatus) ~> Route.seal(routes(creds)) ~> check {
      status should be(BadRequest)
    }
  }

  it should "activate rule" in {
    implicit val tid = transid()

    val rule = WhiskRule(namespace, aname(), afullname(namespace, aname().name), afullname(namespace, "an action"))
    val trigger = WhiskTrigger(namespace, rule.trigger.name, rules = Some {
      Map(rule.fullyQualifiedName(false) -> ReducedRule(rule.action, Status.INACTIVE))
    })

    put(entityStore, trigger, false)
    put(entityStore, rule)

    Post(s"$collectionPath/${rule.name}", activeStatus) ~> Route.seal(routes(creds)) ~> check {
      val t = get(entityStore, trigger.docid, WhiskTrigger)
      deleteTrigger(t.docid)

      status should be(OK)

      t.rules.get(rule.fullyQualifiedName(false)).status should be(Status.ACTIVE)
    }
  }

  it should "activate rule without rule in trigger" in {
    implicit val tid = transid()

    val rule = WhiskRule(namespace, aname(), afullname(namespace, aname().name), afullname(namespace, "an action"))
    val trigger = WhiskTrigger(namespace, rule.trigger.name)

    put(entityStore, trigger, false)
    put(entityStore, rule)

    Post(s"$collectionPath/${rule.name}", activeStatus) ~> Route.seal(routes(creds)) ~> check {
      val t = get(entityStore, trigger.docid, WhiskTrigger)
      deleteTrigger(t.docid)

      status should be(OK)
      t.rules.get(rule.fullyQualifiedName(false)).status should be(Status.ACTIVE)
    }
  }

  it should "reject rule activation, if the trigger is absent" in {
    implicit val tid = transid()

    val rule = WhiskRule(namespace, aname(), afullname(namespace, aname().name), afullname(namespace, "an action"))

    put(entityStore, rule)

    Post(s"$collectionPath/${rule.name}", activeStatus) ~> Route.seal(routes(creds)) ~> check {
      status should be(NotFound)
    }
  }

  it should "deactivate rule" in {
    implicit val tid = transid()

    val rule = WhiskRule(namespace, aname(), afullname(namespace, aname().name), afullname(namespace, "an action"))
    val trigger = WhiskTrigger(namespace, rule.trigger.name, rules = Some {
      Map(rule.fullyQualifiedName(false) -> ReducedRule(rule.action, Status.ACTIVE))
    })

    put(entityStore, trigger, false)
    put(entityStore, rule)

    Post(s"$collectionPath/${rule.name}", inactiveStatus) ~> Route.seal(routes(creds)) ~> check {
      val t = get(entityStore, trigger.docid, WhiskTrigger)
      deleteTrigger(t.docid)

      status should be(OK)
      t.rules.get(rule.fullyQualifiedName(false)).status should be(Status.INACTIVE)
    }
  }

  // invalid resource
  it should "reject invalid resource" in {
    implicit val tid = transid()

    val rule = WhiskRule(namespace, aname(), afullname(namespace, "a trigger"), afullname(namespace, "an action"))

    put(entityStore, rule)

    Get(s"$collectionPath/${rule.name}/bar") ~> Route.seal(routes(creds)) ~> check {
      status should be(NotFound)
    }
  }

  // migration path
  it should "handle a rule with the old schema gracefully" in {
    implicit val tid = transid()

    val rule = OldWhiskRule(namespace, aname(), EntityName("a trigger"), EntityName("an action"), Status.ACTIVE)

    put(entityStore, rule)

    Get(s"$collectionPath/${rule.name}") ~> Route.seal(routes(creds)) ~> check {
      status should be(OK)
      val response = responseAs[WhiskRuleResponse]
      checkResponse(response, rule.toWhiskRule.withStatus(Status.INACTIVE))
    }
  }

  it should "create rule even if the attached trigger has the old schema" in {
    implicit val tid = transid()

    val rule = WhiskRule(namespace, aname(), afullname(namespace, aname().name), afullname(namespace, aname().name))
    val trigger = OldWhiskTrigger(namespace, rule.trigger.name)

    val action = WhiskAction(namespace, rule.action.name, jsDefault("??"))
    val content = WhiskRulePut(Some(rule.trigger), Some(rule.action))

    put(entityStore, trigger, false)
    put(entityStore, action)

    Put(s"$collectionPath/${rule.name}", content) ~> Route.seal(routes(creds)) ~> check {
      val t = get(entityStore, trigger.docid, WhiskTrigger)
      deleteTrigger(t.docid)
      deleteRule(rule.docid)

      status should be(OK)
      t.rules.get(rule.fullyQualifiedName(false)) shouldBe ReducedRule(action.fullyQualifiedName(false), Status.ACTIVE)
      val response = responseAs[WhiskRuleResponse]
      checkResponse(response, rule.withStatus(Status.ACTIVE))
    }
  }

  it should "activate rule even if it is still in the old schema" in {
    implicit val tid = transid()

    val ruleNameQualified = FullyQualifiedEntityName(namespace, aname())
    val triggerName = aname()
    val actionName = FullyQualifiedEntityName(namespace, aname())
    val rule = OldWhiskRule(namespace, ruleNameQualified.name, triggerName, actionName.name, Status.ACTIVE)
    val trigger = WhiskTrigger(
      namespace,
      triggerName,
      rules = Some(Map(ruleNameQualified -> ReducedRule(actionName, Status.INACTIVE))))

    put(entityStore, trigger, false)
    put(entityStore, rule)

    Post(s"$collectionPath/${rule.name}", activeStatus) ~> Route.seal(routes(creds)) ~> check {
      val t = get(entityStore, trigger.docid, WhiskTrigger)
      deleteTrigger(t.docid)

      status should be(OK)

      t.rules.get(ruleNameQualified).status should be(Status.ACTIVE)
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

    val content = WhiskRulePut()
    Put(s"$collectionPath/${entity.name}", content) ~> Route.seal(routes(creds)) ~> check {
      status should be(InternalServerError)
      responseAs[ErrorResponse].error shouldBe Messages.corruptedEntity
    }
  }

  it should "report proper error when action record is corrupted on put" in {
    implicit val tid = transid()
    val tentity = BadEntity(namespace, aname())
    val aentity = BadEntity(namespace, aname())
    val rule = WhiskRule(namespace, aname(), afullname(namespace, aname().name), afullname(namespace, aname().name))
    val trigger = WhiskTrigger(namespace, rule.trigger.name)
    val action = WhiskAction(namespace, rule.action.name, jsDefault("??"))

    val contenta = WhiskRulePut(Some(tentity.fullyQualifiedName(false)), Some(aentity.fullyQualifiedName(false)))
    val contentb = WhiskRulePut(Some(trigger.fullyQualifiedName(false)), Some(aentity.fullyQualifiedName(false)))
    val contentc = WhiskRulePut(Some(tentity.fullyQualifiedName(false)), Some(action.fullyQualifiedName(false)))

    put(entityStore, tentity)
    put(entityStore, aentity)
    put(entityStore, trigger)
    put(entityStore, action)

    Put(s"$collectionPath/${aname()}", contenta) ~> Route.seal(routes(creds)) ~> check {
      status should be(BadRequest)
      responseAs[ErrorResponse].error shouldBe Messages.corruptedEntity
    }

    Put(s"$collectionPath/${aname()}", contentb) ~> Route.seal(routes(creds)) ~> check {
      status should be(BadRequest)
      responseAs[ErrorResponse].error shouldBe Messages.corruptedEntity
    }

    Put(s"$collectionPath/${aname()}", contentc) ~> Route.seal(routes(creds)) ~> check {
      status should be(BadRequest)
      responseAs[ErrorResponse].error shouldBe Messages.corruptedEntity
    }
  }
}
