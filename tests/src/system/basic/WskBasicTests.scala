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

package system.basic

import java.time.Instant

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import common.TestHelpers
import common.TestUtils
import common.TestUtils.CONFLICT
import common.TestUtils.SUCCESS_EXIT
import common.TestUtils.UNAUTHORIZED
import common.Wsk
import common.WskProps
import common.WskTestHelpers
import spray.json._
import spray.json.DefaultJsonProtocol._
import spray.json.pimpAny

@RunWith(classOf[JUnitRunner])
class WskBasicTests
    extends TestHelpers
    with WskTestHelpers {

    implicit val wskprops = WskProps()
    val wsk = new Wsk(usePythonCLI = false)
    val defaultAction = Some(TestUtils.getTestActionFilename("hello.js"))

    behavior of "Wsk CLI"

    it should "confirm wsk exists" in {
        Wsk.exists(wsk.usePythonCLI)
    }

    it should "show api build details" in {
        val wsk = new Wsk(usePythonCLI = true)
        val rr = wsk.cli(wskprops.overrides ++ Seq("property", "get", "--apibuild", "--apibuildno"))
        rr.stderr should not include ("https:///api/v1: http: no Host in request URL")
        rr.stdout should not include regex("Cannot determine API build")
        rr.stdout should include regex ("""(?i)whisk API build\s+201.*""")
        rr.stdout should include regex ("""(?i)whisk API build number\s+.*""")
    }

    it should "reject creating duplicate entity" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "testDuplicateCreate"
            assetHelper.withCleaner(wsk.trigger, name) {
                (trigger, _) => trigger.create(name)
            }
            assetHelper.withCleaner(wsk.action, name, confirmDelete = false) {
                (action, _) => action.create(name, defaultAction, expectedExitCode = CONFLICT)
            }
    }

    it should "reject deleting entity in wrong collection" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "testCrossDelete"
            assetHelper.withCleaner(wsk.trigger, name) {
                (trigger, _) => trigger.create(name)
            }
            wsk.action.delete(name, expectedExitCode = CONFLICT)
    }

    it should "reject unauthenticated access" in {
        implicit val wskprops = WskProps("xxx") // shadow properties
        val errormsg = "The supplied authentication is invalid"
        wsk.namespace.list(expectedExitCode = UNAUTHORIZED).
            stderr should include(errormsg)
        wsk.namespace.get(expectedExitCode = UNAUTHORIZED).
            stderr should include(errormsg)
    }

    behavior of "Wsk Package CLI"

    it should "create, update, get and list a package" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "testPackage"
            val params = Map("a" -> "A".toJson)
            assetHelper.withCleaner(wsk.pkg, name) {
                (pkg, _) =>
                    pkg.create(name, parameters = params, shared = Some(true))
                    pkg.create(name, update = true)
            }
            val stdout = wsk.pkg.get(name).stdout
            stdout should include regex (""""key": "a"""")
            stdout should include regex (""""value": "A"""")
            stdout should include regex (""""publish": true""")
            stdout should include regex (""""version": "0.0.2"""")
            wsk.pkg.list().stdout should include(name)
    }

    behavior of "Wsk Action CLI"

    it should "create the same action twice with different cases" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            assetHelper.withCleaner(wsk.action, "TWICE") { (action, name) => action.create(name, defaultAction) }
            assetHelper.withCleaner(wsk.action, "twice") { (action, name) => action.create(name, defaultAction) }
    }

    it should "create an action, then update its kind" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "createAndUpdate"
            val file = Some(TestUtils.getTestActionFilename("hello.js"))

            assetHelper.withCleaner(wsk.action, name) {
                (action, _) => action.create(name, file, kind = Some("nodejs"))
            }

            // create action as nodejs (v0.12)
            wsk.action.get(name).stdout should include regex (""""kind": "nodejs"""")

            // update to nodejs:6
            wsk.action.create(name, file, kind = Some("nodejs:6"), update = true)
            wsk.action.get(name).stdout should include regex (""""kind": "nodejs:6"""")
    }

    it should "create, update, get and list an action" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "createAndUpdate"
            val file = Some(TestUtils.getTestActionFilename("hello.js"))
            val params = Map("a" -> "A".toJson)
            assetHelper.withCleaner(wsk.action, name) {
                (action, _) =>
                    action.create(name, file, parameters = params, shared = Some(true))
                    action.create(name, None, parameters = Map("b" -> "B".toJson), update = true)
            }
            val stdout = wsk.action.get(name).stdout
            stdout should not include regex(""""key": "a"""")
            stdout should not include regex(""""value": "A"""")
            stdout should include regex (""""key": "b""")
            stdout should include regex (""""value": "B"""")
            stdout should include regex (""""publish": true""")
            stdout should include regex (""""version": "0.0.2"""")
            wsk.action.list().stdout should include(name)
    }

    it should "reject delete of action that does not exist" in {
        wsk.action.sanitize("deleteFantasy").
            stderr should include regex ("""The requested resource does not exist. \(code \d+\)""")
    }

    it should "create, and invoke an action that utilizes a docker container" in withAssetCleaner(wskprops) {
        val name = "dockerContainer"
        (wp, assetHelper) =>
            assetHelper.withCleaner(wsk.action, name) {
                // this docker image will be need to be pulled from dockerhub and hence has to be published there first
                (action, _) => action.create(name, Some("openwhisk/example"), kind = Some("docker"))
            }

            val args = Map("payload" -> "test".toJson)
            val run = wsk.action.invoke(name, args)
            withActivation(wsk.activation, run) {
                activation =>
                    activation.response.result shouldBe Some(JsObject(
                        "args" -> args.toJson,
                        "msg" -> "Hello from arbitrary C program!".toJson))
            }
    }

    /**
     * Tests creating an action from a malformed js file. This should fail in
     * some way - preferably when trying to create the action. If not, then
     * surely when it runs there should be some indication in the logs. Don't
     * think this is true currently.
     */
    it should "create and invoke action with malformed js resulting in activation error" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "MALFORMED"
            assetHelper.withCleaner(wsk.action, name) {
                (action, _) => action.create(name, Some(TestUtils.getTestActionFilename("malformed.js")))
            }

            val run = wsk.action.invoke(name, Map("payload" -> "whatever".toJson))
            withActivation(wsk.activation, run) {
                activation =>
                    activation.response.status shouldBe "action developer error"
                    // representing nodejs giving an error when given malformed.js
                    activation.response.result.get.toString should include("ReferenceError")
            }
    }

    /**
     * Tests creating an nodejs action that throws a whisk.error() response. The error message thrown by the
     * whisk.error() should be returned.
     */
    it should "create and invoke a blocking action resulting in a whisk.error response" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "whiskError"
            assetHelper.withCleaner(wsk.action, name) {
                (action, _) => action.create(name, Some(TestUtils.getTestActionFilename("applicationError1.js")))
            }

            wsk.action.invoke(name, blocking = true, expectedExitCode = 246)
                .stderr should include regex (""""error": "This error thrown on purpose by the action."""")
    }

    it should "invoke a blocking action and get only the result" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "basicInvoke"
            assetHelper.withCleaner(wsk.action, name) {
                (action, _) => action.create(name, Some(TestUtils.getTestActionFilename("wc.js")))
            }
            wsk.action.invoke(name, Map("payload" -> "one two three".toJson), blocking = true, result = true)
                .stdout should include regex (""""count": 3""")
    }

    behavior of "Wsk Trigger CLI"

    it should "create, update, get, fire and list trigger" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "listTriggers"
            val params = Map("a" -> "A".toJson)
            assetHelper.withCleaner(wsk.trigger, name) {
                (trigger, _) =>
                    trigger.create(name, parameters = params, shared = Some(true))
                    trigger.create(name, update = true)
            }
            val stdout = wsk.trigger.get(name).stdout
            stdout should include regex (""""key": "a"""")
            stdout should include regex (""""value": "A"""")
            stdout should include regex (""""publish": true""")
            stdout should include regex (""""version": "0.0.2"""")

            val dynamicParams = Map("t" -> "T".toJson)
            val run = wsk.trigger.fire(name, dynamicParams)
            withActivation(wsk.activation, run) {
                activation =>
                    activation.response.result shouldBe Some(dynamicParams.toJson)
                    activation.end shouldBe Instant.EPOCH.toEpochMilli
            }

            wsk.trigger.list().stdout should include(name)
    }

    behavior of "Wsk Rule CLI"

    it should "create rule, get rule, update rule and list rule" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val ruleName = "listRules"
            val triggerName = "listRulesTrigger"
            val actionName = "listRulesAction";
            assetHelper.withCleaner(wsk.trigger, triggerName) {
                (trigger, name) => trigger.create(name)
            }
            assetHelper.withCleaner(wsk.action, actionName) {
                (action, name) => action.create(name, defaultAction)
            }
            assetHelper.withCleaner(wsk.rule, ruleName) {
                (rule, name) =>
                    rule.create(name, trigger = triggerName, action = actionName)
            }

            // to validate that the rule was created enabled, we do an update and expect CONFLICT
            // (because rule updates against enabled rules must fail)
            wsk.rule.create(ruleName, trigger = triggerName, action = actionName, update = true, expectedExitCode = CONFLICT)

            // now, we disable the rule, so that we can perform the actual update
            wsk.rule.disableRule(ruleName);

            // finally, we perform the update, and expect success this time
            wsk.rule.create(ruleName, trigger = triggerName, action = actionName, update = true)

            val stdout = wsk.rule.get(ruleName).stdout
            stdout should include(ruleName)
            stdout should include(triggerName)
            stdout should include(actionName)
            stdout should include regex (""""version": "0.0.2"""")
            wsk.rule.list().stdout should include(ruleName)
    }

    it should "display a rule summary when --summary flag is used with 'wsk rule get'" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val ruleName = "mySummaryRule"
            val triggerName = "summaryRuleTrigger"
            val actionName = "summaryRuleAction";
            assetHelper.withCleaner(wsk.trigger, triggerName) {
                (trigger, name) => trigger.create(name)
            }
            assetHelper.withCleaner(wsk.action, actionName) {
                (action, name) => action.create(name, defaultAction)
            }
            assetHelper.withCleaner(wsk.rule, ruleName, confirmDelete = false) {
                (rule, name) => rule.create(name, trigger = triggerName, action = actionName)
            }
            // Summary namespace should match one of the allowable namespaces (typically 'guest')
            val ns_regex_list = wsk.namespace.list().stdout.trim.replace('\n', '|')
            val stdout = wsk.rule.get(ruleName, summary = true).stdout
            stdout should include regex (s"(?i)rule\\s+/${ns_regex_list}/${ruleName}")
    }

    behavior of "Wsk Namespace CLI"

    it should "list namespaces" in {
        wsk.namespace.list().
            stdout should include regex ("@|guest")
    }

    it should "list entities in default namespace" in {
        // use a fresh wsk props instance that is guaranteed to use
        // the default namespace
        wsk.namespace.get(expectedExitCode = SUCCESS_EXIT)(WskProps()).
            stdout should include("default")
    }
}
