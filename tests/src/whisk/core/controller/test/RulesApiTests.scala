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
import spray.http.StatusCodes.Accepted
import spray.http.StatusCodes.BadRequest
import spray.http.StatusCodes.Conflict
import spray.http.StatusCodes.OK
import spray.http.StatusCodes.NotFound
import spray.httpx.SprayJsonSupport.sprayJsonMarshaller
import spray.httpx.SprayJsonSupport.sprayJsonUnmarshaller
import spray.json.DefaultJsonProtocol.listFormat
import spray.json.DefaultJsonProtocol.RootJsObjectFormat
import spray.json.DefaultJsonProtocol.listFormat
import spray.json.DefaultJsonProtocol.StringJsonFormat
import spray.json.JsObject
import spray.json.pimpAny
import spray.json.pimpString
import whisk.core.controller.WhiskRulesApi
import whisk.core.entity.ActionLimits
import whisk.core.entity.DocId
import whisk.core.entity.EntityName
import whisk.core.entity.Exec
import whisk.core.entity.Namespace
import whisk.core.entity.Parameters
import whisk.core.entity.SemVer
import whisk.core.entity.Status
import whisk.core.entity.TriggerLimits
import whisk.core.entity.AuthKey
import whisk.core.entity.WhiskAuth
import whisk.core.entity.Subject
import whisk.core.entity.WhiskAction
import whisk.core.entity.WhiskEntity
import whisk.core.entity.WhiskRule
import whisk.core.entity.WhiskRulePut
import whisk.core.entity.WhiskTrigger
import whisk.http.ErrorResponse
import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.SECONDS
import scala.language.postfixOps

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

    /** Duration to complete state change before timing out. */
    override val ruleChangeTimeout = 5 seconds

    val creds = WhiskAuth(Subject(), AuthKey())
    val namespace = Namespace(creds.subject())
    def aname = MakeName.next("rules_tests")
    val collectionPath = s"/${Namespace.DEFAULT}/${collection.path}"
    val activeStatus = s"""{"status":"${Status.ACTIVE}"}""".parseJson.asJsObject
    val inactiveStatus = s"""{"status":"${Status.INACTIVE}"}""".parseJson.asJsObject
    val activatingStatus = s"""{"status":"${Status.ACTIVATING}"}""".parseJson.asJsObject
    val deactivatingStatus = s"""{"status":"${Status.DEACTIVATING}"}""".parseJson.asJsObject

    //// GET /rules
    it should "list rules by default namespace" in {
        implicit val tid = transid()
        val rules = (1 to 2).map { i =>
            WhiskRule(namespace, aname, Status.INACTIVE, EntityName("bogus trigger"), EntityName("bogus action"))
        }.toList
        rules foreach { put(entityStore, _) }
        waitOnView(entityStore, WhiskRule, namespace, 2)
        Get(s"$collectionPath") ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[List[JsObject]]
            rules.length should be(response.length)
            rules forall { r => response contains r.summaryAsJson } should be(true)
        }
    }

    // ?docs disabled
    ignore should "list rules by default namespace with full docs" in {
        implicit val tid = transid()
        val rules = (1 to 2).map { i =>
            WhiskRule(namespace, aname, Status.INACTIVE, EntityName("bogus trigger"), EntityName("bogus action"))
        }.toList
        rules foreach { put(entityStore, _) }
        waitOnView(entityStore, WhiskRule, namespace, 2)
        Get(s"$collectionPath?docs=true") ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[List[WhiskRule]]
            rules.length should be(response.length)
            rules forall { r => response contains r } should be(true)
        }
    }

    //// GET /rule/anme
    it should "get rule" in {
        implicit val tid = transid()
        val rule = WhiskRule(namespace, aname, Status.INACTIVE, EntityName("bogus trigger"), EntityName("bogus action"))
        val name = rule.name().replaceAll(" ", "%20")
        put(entityStore, rule)
        Get(s"$collectionPath/$name") ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[WhiskRule]
            response should be(rule)
        }
    }

    it should "reject get of non existent rule" in {
        implicit val tid = transid()
        Get(s"$collectionPath/xxx") ~> sealRoute(routes(creds)) ~> check {
            status should be(NotFound)
        }
    }

    //// DEL /rules/name
    it should "reject delete rule in state active" in {
        implicit val tid = transid()
        val rule = WhiskRule(namespace, EntityName("reject_delete_rule_active"), Status.ACTIVE, EntityName("a trigger"), EntityName("an action"))
        val name = rule.name().replaceAll(" ", "%20")
        put(entityStore, rule)
        Delete(s"$collectionPath/$name") ~> sealRoute(routes(creds)) ~> check {
            status should be(Conflict)
            responseAs[ErrorResponse].error should be(s"rule status is '${Status.ACTIVE}', must be '${Status.INACTIVE}' to delete")
        }
    }

    it should "reject delete rule in state deactivating" in {
        implicit val tid = transid()
        val rule = WhiskRule(namespace, EntityName("reject_delete_rule_deactivating"), Status.DEACTIVATING, EntityName("a trigger"), EntityName("an action"))
        val name = rule.name().replaceAll(" ", "%20")
        put(entityStore, rule)
        Delete(s"$collectionPath/$name") ~> sealRoute(routes(creds)) ~> check {
            status should be(Conflict)
        }
    }

    it should "reject delete rule in state activating" in {
        implicit val tid = transid()
        val rule = WhiskRule(namespace, EntityName("reject_delete_rule_activating"), Status.ACTIVATING, EntityName("a trigger"), EntityName("an action"))
        val name = rule.name().replaceAll(" ", "%20")
        put(entityStore, rule)
        Delete(s"$collectionPath/$name") ~> sealRoute(routes(creds)) ~> check {
            status should be(Conflict)
        }
    }

    it should "delete rule in state inactive" in {
        implicit val tid = transid()
        val rule = WhiskRule(namespace, EntityName("delete_rule_inactive"), Status.INACTIVE, EntityName("a trigger"), EntityName("an action"))
        val name = rule.name().replaceAll(" ", "%20")
        put(entityStore, rule)
        Delete(s"$collectionPath/$name") ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[WhiskRule]
            response should be(rule)
        }
    }

    //// PUT /rules/name
    it should "create rule" in {
        implicit val tid = transid()
        val trigger = WhiskTrigger(namespace, aname)
        val action = WhiskAction(namespace, aname, Exec.js("??"))
        val rule = WhiskRule(namespace, aname, Status.INACTIVE, trigger.name, action.name)
        val content = WhiskRulePut(Some(trigger.name), Some(action.name))
        val name = rule.name().replaceAll(" ", "%20")
        put(entityStore, trigger)
        put(entityStore, action)
        Put(s"$collectionPath/$name", content) ~> sealRoute(routes(creds)) ~> check {
            deleteRule(rule.docid)
            status should be(OK)
            val response = responseAs[WhiskRule]
            response should be(rule)
        }
    }

    it should "reject rule if action does not exist" in {
        implicit val tid = transid()
        val trigger = WhiskTrigger(namespace, aname)
        val content = WhiskRulePut(Some(trigger.name), Some(EntityName("bogus action")))
        put(entityStore, trigger)
        Put(s"$collectionPath/xxx", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
            responseAs[String] === s"${WhiskEntity.qualifiedName(namespace, content.action.get)} does not exist"
        }
    }

    it should "reject rule if trigger does not exist" in {
        implicit val tid = transid()
        val action = WhiskAction(namespace, aname, Exec.js("??"))
        val content = WhiskRulePut(Some(EntityName("bogus trigger")), Some(action.name))
        put(entityStore, action)
        Put(s"$collectionPath/xxx", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
            responseAs[String] === s"${WhiskEntity.qualifiedName(namespace, content.trigger.get)} does not exist"
        }
    }

    it should "reject rule if neither action or trigger do not exist" in {
        implicit val tid = transid()
        val content = WhiskRulePut(Some(EntityName("bogus trigger")), Some(EntityName("bogus action")))
        Put(s"$collectionPath/xxx", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
            responseAs[String].contains("does not exist") should be(true)
        }
    }

    it should "update rule" in {
        implicit val tid = transid()
        val trigger = WhiskTrigger(namespace, aname)
        val action = WhiskAction(namespace, aname, Exec.js("??"))
        val rule = WhiskRule(namespace, aname, Status.INACTIVE, EntityName("bogus trigger"), EntityName("bogus action"))
        val name = rule.name().replaceAll(" ", "%20")
        put(entityStore, trigger)
        put(entityStore, action)
        put(entityStore, rule)
        val content = WhiskRulePut(Some(trigger.name), Some(action.name))
        Put(s"$collectionPath/$name?overwrite=true", content) ~> sealRoute(routes(creds)) ~> check {
            deleteRule(rule.docid)
            status should be(OK)
            val response = responseAs[WhiskRule]
            response should be(WhiskRule(namespace, rule.name, Status.INACTIVE, trigger.name, action.name, version = SemVer().upPatch))
        }
    }

    it should "update rule when no new content is provided" in {
        implicit val tid = transid()
        val trigger = WhiskTrigger(namespace, aname)
        val action = WhiskAction(namespace, aname, Exec.js("??"))
        val rule = WhiskRule(namespace, aname, Status.INACTIVE, trigger.name, action.name)
        val name = rule.name().replaceAll(" ", "%20")
        put(entityStore, trigger)
        put(entityStore, action)
        put(entityStore, rule)
        val content = WhiskRulePut(None, None, None, None, None)
        Put(s"$collectionPath/$name?overwrite=true", content) ~> sealRoute(routes(creds)) ~> check {
            deleteRule(rule.docid)
            status should be(OK)
            val response = responseAs[WhiskRule]
            response should be(WhiskRule(namespace, rule.name, Status.INACTIVE, trigger.name, action.name, version = SemVer().upPatch))
        }
    }

    it should "reject update rule if trigger does not exist" in {
        implicit val tid = transid()
        val action = WhiskAction(namespace, aname, Exec.js("??"))
        val rule = WhiskRule(namespace, aname, Status.INACTIVE, EntityName("bogus trigger"), action.name)
        val name = rule.name().replaceAll(" ", "%20")
        put(entityStore, action)
        put(entityStore, rule)
        val content = WhiskRulePut(action = Some(action.name))
        Put(s"$collectionPath/$name?overwrite=true", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
            responseAs[String] === s"${WhiskEntity.qualifiedName(namespace, EntityName(rule.trigger.name))} does not exist"
        }
    }

    it should "reject update rule if action does not exist" in {
        implicit val tid = transid()
        val trigger = WhiskTrigger(namespace, aname)
        val rule = WhiskRule(namespace, EntityName("update rule"), Status.INACTIVE, trigger.name, EntityName("bogus action"))
        val name = rule.name().replaceAll(" ", "%20")
        put(entityStore, trigger)
        put(entityStore, rule)
        val content = WhiskRulePut(trigger = Some(trigger.name))
        Put(s"$collectionPath/$name?overwrite=true", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
            responseAs[String] === s"${WhiskEntity.qualifiedName(namespace, EntityName(rule.action.name))} does not exist"
        }
    }

    it should "reject update rule if neither trigger or action exist" in {
        implicit val tid = transid()
        val rule = WhiskRule(namespace, aname, Status.INACTIVE, EntityName("bogus trigger"), EntityName("bogus action"))
        val name = rule.name().replaceAll(" ", "%20")
        put(entityStore, rule)
        val content = WhiskRulePut(None, None, None, None, None)
        Put(s"$collectionPath/$name?overwrite=true", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
            responseAs[String].contains("does not exist") should be(true)
        }
    }

    it should "reject update rule in state active" in {
        implicit val tid = transid()
        val rule = WhiskRule(namespace, aname, Status.ACTIVE, EntityName("a trigger"), EntityName("an action"))
        val name = rule.name().replaceAll(" ", "%20")
        put(entityStore, rule)
        val content = WhiskRulePut(publish = Some(!rule.publish))
        Put(s"$collectionPath/$name?overwrite=true", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(Conflict)
        }
    }

    it should "reject update rule in state activating" in {
        implicit val tid = transid()
        val rule = WhiskRule(namespace, aname, Status.ACTIVATING, EntityName("a trigger"), EntityName("an action"))
        val name = rule.name().replaceAll(" ", "%20")
        put(entityStore, rule)
        val content = WhiskRulePut(publish = Some(!rule.publish))
        Put(s"$collectionPath/$name?overwrite=true", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(Conflict)
        }
    }

    it should "reject update rule in state deactivating" in {
        implicit val tid = transid()
        val rule = WhiskRule(namespace, aname, Status.DEACTIVATING, EntityName("a trigger"), EntityName("an action"))
        val name = rule.name().replaceAll(" ", "%20")
        put(entityStore, rule)
        val content = WhiskRulePut(publish = Some(!rule.publish))
        Put(s"$collectionPath/$name?overwrite=true", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(Conflict)
        }
    }

    //// POST /rules/name
    it should "do nothing to disable already disabled rule" in {
        implicit val tid = transid()
        val rule = WhiskRule(namespace, aname, Status.INACTIVE, EntityName("a trigger"), EntityName("an action"))
        val name = rule.name().replaceAll(" ", "%20")
        put(entityStore, rule)
        Post(s"$collectionPath/$name", inactiveStatus) ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
        }
    }

    it should "reject disable rule in transitioning state activating" in {
        implicit val tid = transid()
        val rule = WhiskRule(namespace, aname, Status.ACTIVATING, EntityName("a trigger"), EntityName("an action"))
        val name = rule.name().replaceAll(" ", "%20")
        put(entityStore, rule)
        Post(s"$collectionPath/$name", inactiveStatus) ~> sealRoute(routes(creds)) ~> check {
            status should be(Conflict)
        }
    }

    it should "reject disable rule in transitioning state deactivating" in {
        implicit val tid = transid()
        val rule = WhiskRule(namespace, EntityName("reject_disable_rule_deactivating"), Status.DEACTIVATING, EntityName("a trigger"), EntityName("an action"))
        val name = rule.name().replaceAll(" ", "%20")
        put(entityStore, rule)
        Post(s"$collectionPath/$name", inactiveStatus) ~> sealRoute(routes(creds)) ~> check {
            status should be(Conflict)
        }
    }

    it should "do nothing to enable already enabled rule" in {
        implicit val tid = transid()
        val rule = WhiskRule(namespace, aname, Status.ACTIVE, EntityName("a trigger"), EntityName("an action"))
        val name = rule.name().replaceAll(" ", "%20")
        put(entityStore, rule)
        Post(s"$collectionPath/$name", activeStatus) ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
        }
    }

    it should "reject enable rule in transitioning state activating" in {
        implicit val tid = transid()
        val rule = WhiskRule(namespace, aname, Status.ACTIVATING, EntityName("a trigger"), EntityName("an action"))
        val name = rule.name().replaceAll(" ", "%20")
        put(entityStore, rule)
        Post(s"$collectionPath/$name", activeStatus) ~> sealRoute(routes(creds)) ~> check {
            status should be(Conflict)
        }
    }

    it should "reject enable rule in transitioning state deactivating" in {
        implicit val tid = transid()
        val rule = WhiskRule(namespace, EntityName("reject_enable_rule_deactivating"), Status.DEACTIVATING, EntityName("a trigger"), EntityName("an action"))
        val name = rule.name().replaceAll(" ", "%20")
        put(entityStore, rule)
        Post(s"$collectionPath/$name", activeStatus) ~> sealRoute(routes(creds)) ~> check {
            status should be(Conflict)
        }
    }

    it should "reject post with invalid status activating" in {
        implicit val tid = transid()
        Post(s"$collectionPath/xyz", activatingStatus) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
        }
    }

    it should "reject post with invalid status deactivating" in {
        implicit val tid = transid()
        Post(s"$collectionPath/xyz", deactivatingStatus) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
        }
    }

    it should "reject post with status undefined" in {
        implicit val tid = transid()
        Post(s"$collectionPath/xyz") ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
        }
    }

    it should "reject post with invalid status" in {
        implicit val tid = transid()
        val badStatus = s"""{"status":"xxx"}""".parseJson.asJsObject
        Post(s"$collectionPath/xyz", badStatus) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
        }
    }

    it should "activate rule" in {
        implicit val tid = transid()
        val rule = WhiskRule(namespace, aname, Status.INACTIVE, EntityName("a trigger"), EntityName("an action"))
        val name = rule.name().replaceAll(" ", "%20")
        put(entityStore, rule, false)
        Post(s"$collectionPath/$name", activeStatus) ~> sealRoute(routes(creds)) ~> check {
            val r1 = get(entityStore, rule.docid.asDocInfo, WhiskRule)
            Thread.sleep(ruleChangeTimeout.toMillis * 2) // allow rule nanny to do its job
            val r2 = get(entityStore, rule.docid.asDocInfo, WhiskRule)
            WhiskRule.del(entityStore, r2.docinfo)
            status should be(Accepted)
            r1.status should be(Status.ACTIVATING)
            r2.status should be(Status.INACTIVE)
        }
    }

    it should "deactivate rule" in {
        implicit val tid = transid()
        val rule = WhiskRule(namespace, aname, Status.ACTIVE, EntityName("a trigger"), EntityName("an action"))
        val name = rule.name().replaceAll(" ", "%20")
        put(entityStore, rule, false)
        Post(s"$collectionPath/$name", inactiveStatus) ~> sealRoute(routes(creds)) ~> check {
            val r1 = get(entityStore, rule.docid.asDocInfo, WhiskRule)
            Thread.sleep(ruleChangeTimeout.toMillis * 2) // allow rule nanny to do its job
            val r2 = get(entityStore, rule.docid.asDocInfo, WhiskRule)
            WhiskRule.del(entityStore, r2.docinfo)
            status should be(Accepted)
            r1.status should be(Status.DEACTIVATING)
            r2.status should be(Status.INACTIVE)
        }
    }

    //// invalid resource
    it should "reject invalid resource" in {
        implicit val tid = transid()
        val rule = WhiskRule(namespace, aname, Status.ACTIVE, EntityName("a trigger"), EntityName("an action"))
        put(entityStore, rule)
        Get(s"$collectionPath/${rule.name}/bar") ~> sealRoute(routes(creds)) ~> check {
            status should be(NotFound)
        }
    }
}
