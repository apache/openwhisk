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

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import common.TestHelpers
import common.TestUtils
import common.Wsk
import common.WskProps
import common.WskTestHelpers
import spray.json._
import spray.json.DefaultJsonProtocol._
import java.time.Instant

@RunWith(classOf[JUnitRunner])
class WskRuleTests
    extends TestHelpers
    with WskTestHelpers {

    implicit val wskprops = WskProps()
    val wsk = new Wsk
    val defaultAction = TestUtils.getTestActionFilename("wc.js")
    val secondAction = TestUtils.getTestActionFilename("hello.js")
    val testString = "this is a test"
    val testResult = JsObject("count" -> testString.split(" ").length.toJson)

    /**
     * Sets up trigger -> rule -> action triplets. Deduplicates triggers and rules
     * and links it all up.
     *
     * @param rules Tuple3s containing
     *   (rule, trigger, (action name for created action, action name for the rule binding, actionFile))
     *   where the action name for the created action is allowed to differ from that used by the rule binding
     *   for cases that reference actions in a package binding.
     */
    def ruleSetup(rules: Seq[(String, String, (String, String, String))], assetHelper: AssetCleaner) = {
        val triggers = rules.map(_._2).distinct
        val actions = rules.map(_._3).distinct

        triggers.foreach { trigger =>
            assetHelper.withCleaner(wsk.trigger, trigger) {
                (trigger, name) => trigger.create(name)
            }
        }

        actions.foreach {
            case (actionName, _, file) =>
                assetHelper.withCleaner(wsk.action, actionName) {
                    (action, name) => action.create(name, Some(file))
                }
        }

        rules.foreach {
            case (ruleName, triggerName, action) =>
                assetHelper.withCleaner(wsk.rule, ruleName) {
                    (rule, name) => rule.create(name, triggerName, action._2)
                }
        }
    }

    behavior of "Whisk rules"

    it should "invoke the action attached on trigger fire, creating an activation for each entity including the cause" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val ruleName = "r1to1"
            val triggerName = "t1to1"
            val actionName = "a1 to 1" // spaces in name intended for greater test coverage

            ruleSetup(Seq(
                (ruleName, triggerName, (actionName, actionName, defaultAction))),
                assetHelper)

            val run = wsk.trigger.fire(triggerName, Map("payload" -> testString.toJson))

            withActivation(wsk.activation, run) {
                triggerActivation =>
                    triggerActivation.cause shouldBe None

                    withActivationsFromEntity(wsk.activation, ruleName, since = Some(Instant.ofEpochMilli(triggerActivation.start))) {
                        _.head.cause shouldBe Some(triggerActivation.activationId)
                    }

                    withActivationsFromEntity(wsk.activation, actionName, since = Some(Instant.ofEpochMilli(triggerActivation.start))) { activationList =>
                        activationList.head.response.result shouldBe Some(testResult)
                        activationList.head.cause shouldBe None
                    }
            }
    }

    it should "invoke the action from a package attached on trigger fire, creating an activation for each entity including the cause" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val ruleName = "pr1to1"
            val triggerName = "pt1to1"
            val pkgName = "rule pkg" // spaces in name intended to test uri path encoding
            val actionName = "a1 to 1"
            val pkgActionName = s"$pkgName/$actionName"

            assetHelper.withCleaner(wsk.pkg, pkgName) {
                (pkg, name) => pkg.create(name)
            }

            ruleSetup(Seq(
                (ruleName, triggerName, (pkgActionName, pkgActionName, defaultAction))),
                assetHelper)

            val now = Instant.now
            val run = wsk.trigger.fire(triggerName, Map("payload" -> testString.toJson))

            withActivation(wsk.activation, run) {
                triggerActivation =>
                    triggerActivation.cause shouldBe None

                    withActivationsFromEntity(wsk.activation, ruleName, since = Some(Instant.ofEpochMilli(triggerActivation.start))) {
                        _.head.cause shouldBe Some(triggerActivation.activationId)
                    }

                    withActivationsFromEntity(wsk.activation, actionName, since = Some(Instant.ofEpochMilli(triggerActivation.start))) {
                        _.head.response.result shouldBe Some(testResult)
                    }
            }
    }

    it should "invoke the action from a package binding attached on trigger fire, creating an activation for each entity including the cause" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val ruleName = "pr1to1"
            val triggerName = "pt1to1"
            val pkgName = "rule pkg" // spaces in name intended to test uri path encoding
            val pkgBindingName = "rule pkg binding"
            val actionName = "a1 to 1"
            val pkgActionName = s"$pkgName/$actionName"

            assetHelper.withCleaner(wsk.pkg, pkgName) {
                (pkg, name) => pkg.create(name)
            }

            assetHelper.withCleaner(wsk.pkg, pkgBindingName) {
                (pkg, name) => pkg.bind(pkgName, pkgBindingName)
            }

            ruleSetup(Seq(
                (ruleName, triggerName, (pkgActionName, s"$pkgBindingName/$actionName", defaultAction))),
                assetHelper)

            val run = wsk.trigger.fire(triggerName, Map("payload" -> testString.toJson))

            withActivation(wsk.activation, run) {
                triggerActivation =>
                    triggerActivation.cause shouldBe None

                    withActivationsFromEntity(wsk.activation, ruleName, since = Some(Instant.ofEpochMilli(triggerActivation.start))) {
                        _.head.cause shouldBe Some(triggerActivation.activationId)
                    }

                    withActivationsFromEntity(wsk.activation, actionName, since = Some(Instant.ofEpochMilli(triggerActivation.start))) {
                        _.head.response.result shouldBe Some(testResult)
                    }
            }
    }

    it should "not activate an action if the rule is deleted when the trigger is fired" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val ruleName = "ruleDelete"
            val triggerName = "ruleDeleteTrigger"
            val actionName = "ruleDeleteAction"

            assetHelper.withCleaner(wsk.trigger, triggerName) {
                (trigger, name) => trigger.create(name)
            }
            assetHelper.withCleaner(wsk.action, actionName) {
                (action, name) => action.create(name, Some(defaultAction))
            }
            assetHelper.withCleaner(wsk.rule, ruleName, false) {
                (rule, name) => rule.create(name, triggerName, actionName)
            }

            val first = wsk.trigger.fire(triggerName, Map("payload" -> "bogus".toJson))
            wsk.rule.delete(ruleName)
            wsk.trigger.fire(triggerName, Map("payload" -> "bogus2".toJson))

            withActivation(wsk.activation, first) {
                activation =>
                    // tries to find 2 activations for the action, should only find 1
                    val activations = wsk.activation.pollFor(2, Some(actionName), since = Some(Instant.ofEpochMilli(activation.start)), retries = 30)

                    activations.length shouldBe 1
            }
    }

    it should "enable and disable a rule and check action is activated only when rule is enabled" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val ruleName = "ruleDisable"
            val triggerName = "ruleDisableTrigger"
            val actionName = "ruleDisableAction"

            ruleSetup(Seq(
                (ruleName, triggerName, (actionName, actionName, defaultAction))),
                assetHelper)

            val first = wsk.trigger.fire(triggerName, Map("payload" -> testString.toJson))
            wsk.rule.disableRule(ruleName)
            wsk.trigger.fire(triggerName, Map("payload" -> s"$testString with added words".toJson))
            wsk.rule.enableRule(ruleName)
            wsk.trigger.fire(triggerName, Map("payload" -> testString.toJson))

            withActivation(wsk.activation, first) {
                triggerActivation =>
                    withActivationsFromEntity(wsk.activation, actionName, N = 2, since = Some(Instant.ofEpochMilli(triggerActivation.start))) {
                        activations =>
                            val results = activations.map(_.response.result)
                            results should contain theSameElementsAs Seq(Some(testResult), Some(testResult))
                    }
            }
    }

    it should "be able to recreate a rule with the same name and match it successfully" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val ruleName = "ruleRecreate"
            val triggerName1 = "ruleRecreateTrigger1"
            val triggerName2 = "ruleRecreateTrigger2"
            val actionName = "ruleRecreateAction"

            assetHelper.withCleaner(wsk.trigger, triggerName1) {
                (trigger, name) => trigger.create(name)
            }
            assetHelper.withCleaner(wsk.action, actionName) {
                (action, name) => action.create(name, Some(defaultAction))
            }
            assetHelper.withCleaner(wsk.rule, ruleName, false) {
                (rule, name) => rule.create(name, triggerName1, actionName)
            }

            wsk.rule.delete(ruleName)

            assetHelper.withCleaner(wsk.trigger, triggerName2) {
                (trigger, name) => trigger.create(name)
            }
            assetHelper.withCleaner(wsk.rule, ruleName) {
                (rule, name) => rule.create(name, triggerName2, actionName)
            }

            val first = wsk.trigger.fire(triggerName2, Map("payload" -> testString.toJson))
            withActivation(wsk.activation, first) {
                triggerActivation =>
                    withActivationsFromEntity(wsk.activation, actionName, since = Some(Instant.ofEpochMilli(triggerActivation.start))) {
                        _.head.response.result shouldBe Some(testResult)
                    }
            }
    }

    it should "connect two triggers via rules to one action and activate it accordingly" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val triggerName1 = "t2to1a"
            val triggerName2 = "t2to1b"
            val actionName = "a2to1"

            ruleSetup(Seq(
                ("r2to1a", triggerName1, (actionName, actionName, defaultAction)),
                ("r2to1b", triggerName2, (actionName, actionName, defaultAction))),
                assetHelper)

            val testPayloads = Seq("got three words", "got four words, period")

            val run = wsk.trigger.fire(triggerName1, Map("payload" -> testPayloads(0).toJson))
            wsk.trigger.fire(triggerName2, Map("payload" -> testPayloads(1).toJson))

            withActivation(wsk.activation, run) {
                triggerActivation =>
                    withActivationsFromEntity(wsk.activation, actionName, N = 2, since = Some(Instant.ofEpochMilli(triggerActivation.start))) {
                        activations =>
                            val results = activations.map(_.response.result)
                            val expectedResults = testPayloads.map { payload =>
                                Some(JsObject("count" -> payload.split(" ").length.toJson))
                            }

                            results should contain theSameElementsAs expectedResults
                    }
            }
    }

    it should "connect one trigger to two different actions, invoking them both eventually" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val triggerName = "t1to2"
            val actionName1 = "a1to2a"
            val actionName2 = "a1to2b"

            ruleSetup(Seq(
                ("r1to2a", triggerName, (actionName1, actionName1, defaultAction)),
                ("r1to2b", triggerName, (actionName2, actionName2, secondAction))),
                assetHelper)

            val run = wsk.trigger.fire(triggerName, Map("payload" -> testString.toJson))

            withActivation(wsk.activation, run) {
                triggerActivation =>
                    withActivationsFromEntity(wsk.activation, actionName1, since = Some(Instant.ofEpochMilli(triggerActivation.start))) {
                        _.head.response.result shouldBe Some(testResult)
                    }
                    withActivationsFromEntity(wsk.activation, actionName2, since = Some(Instant.ofEpochMilli(triggerActivation.start))) {
                        _.head.logs.get.mkString(" ") should include(s"hello $testString")
                    }
            }
    }

    it should "connect two triggers to two different actions, invoking them both eventually" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val triggerName1 = "t1to1a"
            val triggerName2 = "t1to1b"
            val actionName1 = "a1to1a"
            val actionName2 = "a1to1b"

            ruleSetup(Seq(
                ("r2to2a", triggerName1, (actionName1, actionName1, defaultAction)),
                ("r2to2b", triggerName1, (actionName2, actionName2, secondAction)),
                ("r2to2c", triggerName2, (actionName1, actionName1, defaultAction)),
                ("r2to2d", triggerName2, (actionName2, actionName2, secondAction))),
                assetHelper)

            val testPayloads = Seq("got three words", "got four words, period")
            val run = wsk.trigger.fire(triggerName1, Map("payload" -> testPayloads(0).toJson))
            wsk.trigger.fire(triggerName2, Map("payload" -> testPayloads(1).toJson))

            withActivation(wsk.activation, run) {
                triggerActivation =>
                    withActivationsFromEntity(wsk.activation, actionName1, N = 2, since = Some(Instant.ofEpochMilli(triggerActivation.start))) {
                        activations =>
                            val results = activations.map(_.response.result)
                            val expectedResults = testPayloads.map { payload =>
                                Some(JsObject("count" -> payload.split(" ").length.toJson))
                            }

                            results should contain theSameElementsAs expectedResults
                    }
                    withActivationsFromEntity(wsk.activation, actionName2, N = 2, since = Some(Instant.ofEpochMilli(triggerActivation.start))) {
                        activations =>
                            // drops the leftmost 39 characters (timestamp + streamname)
                            val logs = activations.map(_.logs.get.map(_.drop(39))).flatten
                            val expectedLogs = testPayloads.map { payload => s"hello $payload!" }

                            logs should contain theSameElementsAs expectedLogs
                    }
            }
    }

}
