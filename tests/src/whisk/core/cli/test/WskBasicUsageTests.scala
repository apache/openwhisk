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

package whisk.core.cli.test

import java.io.File
import java.time.Instant

import scala.language.postfixOps
import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt

import org.apache.commons.io.FileUtils
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import common.TestHelpers
import common.TestUtils
import common.TestUtils.ANY_ERROR_EXIT
import common.TestUtils.DONTCARE_EXIT
import common.TestUtils.BAD_REQUEST
import common.TestUtils.ERROR_EXIT
import common.TestUtils.MISUSE_EXIT
import common.TestUtils.NOTALLOWED
import common.TestUtils.SUCCESS_EXIT
import common.WhiskProperties
import common.Wsk
import common.WskProps
import common.WskTestHelpers
import spray.json.DefaultJsonProtocol.IntJsonFormat
import spray.json.DefaultJsonProtocol.LongJsonFormat
import spray.json.JsObject
import spray.json.pimpAny
import spray.json.pimpString
import whisk.core.entity.ByteSize
import whisk.core.entity.LogLimit._
import whisk.core.entity.MemoryLimit._
import whisk.core.entity.TimeLimit._
import whisk.core.entity.size.SizeInt
import whisk.utils.retry

/**
 * Tests for basic CLI usage. Some of these tests require a deployed backend.
 */
@RunWith(classOf[JUnitRunner])
class WskBasicCliUsageTests
    extends TestHelpers
    with WskTestHelpers {

    implicit val wskprops = WskProps()
    val wsk = new Wsk(usePythonCLI = false)
    val defaultAction = Some(TestUtils.getCatalogFilename("samples/hello.js"))

    behavior of "Wsk CLI usage"

    it should "confirm wsk exists" in {
        Wsk.exists(wsk.usePythonCLI)
    }

    it should "show help and usage info" in {
        val stdout = wsk.cli(Seq("-h")).stdout

        if (wsk.usePythonCLI) {
            stdout should include("usage:")
            stdout should include("optional arguments")
            stdout should include("available commands")
            stdout should include("-help")
        } else {
            stdout should include regex ("""(?i)Usage:""")
            stdout should include regex ("""(?i)Flags""")
            stdout should include regex ("""(?i)Available commands""")
            stdout should include regex ("""(?i)--help""")
        }
    }

    it should "show cli build version" in {
        val stdout = wsk.cli(Seq("property", "get", "--cliversion")).stdout
        stdout should include regex ("""(?i)whisk CLI version\s+201.*""")
    }

    it should "show api version" in {
        val stdout = wsk.cli(Seq("property", "get", "--apiversion")).stdout
        stdout should include regex ("""(?i)whisk API version\s+v1""")
    }

    it should "show api build version using property file" in {
        val tmpwskprops = File.createTempFile("wskprops", ".tmp")
        val env = Map("WSK_CONFIG_FILE" -> tmpwskprops.getAbsolutePath())
        wsk.cli(wskprops.overrides ++ Seq("property", "set"), env = env)
        val stdout = wsk.cli(Seq("property", "get", "--apibuild", "-i"), env = env).stdout
        try {
            stdout should include regex ("""(?i)whisk API build\s+201.*""")
        } finally {
            tmpwskprops.delete()
        }
    }

    it should "fail to show api build when setting apihost to bogus value" in {
        val wsk = new Wsk(usePythonCLI = true)
        val rr = wsk.cli(Seq("--apihost", "xxxx.yyyy", "property", "get", "--apibuild"), expectedExitCode = ANY_ERROR_EXIT)
        rr.stdout should { include regex ("Cannot determine API build") or include regex ("Unknown") }
        rr.stdout should not include regex("""(?i)whisk API build\s+201.*""")
        rr.stderr should not include ("https:///api/v1: http: no Host in request URL")
    }

    it should "show api build using http apihost" in {
        val wsk = new Wsk(usePythonCLI = true)
        val apihost = s"http://${WhiskProperties.getControllerHost}:${WhiskProperties.getControllerPort}"
        val stdout = wsk.cli(Seq("--apihost", apihost, "property", "get", "--apibuild")).stdout
        stdout should not { include regex ("Cannot determine API build") or include regex ("Unknown") }
        stdout should include regex ("""(?i)whisk API build\s+201.*""")
    }

    it should "validate default property values" in {
        val tmpwskprops = File.createTempFile("wskprops", ".tmp")
        val env = Map("WSK_CONFIG_FILE" -> tmpwskprops.getAbsolutePath())
        val stdout = wsk.cli(Seq("property", "unset", "--auth", "--apihost", "--apiversion", "--namespace"), env = env).stdout
        try {
            stdout should include regex ("ok: whisk auth unset")
            stdout should include regex ("ok: whisk API host unset")
            stdout should include regex ("ok: whisk API version unset")
            stdout should include regex ("ok: whisk namespace unset")

            wsk.cli(Seq("property", "get", "--auth"), env = env).
                stdout should include regex ("""(?i)whisk auth\s*$""") // default = empty string
            wsk.cli(Seq("property", "get", "--apihost"), env = env).
                stdout should include regex ("""(?i)whisk API host\s*$""") // default = empty string
            wsk.cli(Seq("property", "get", "--namespace"), env = env).
                stdout should include regex ("""(?i)whisk namespace\s*_$""") // default = _
        } finally {
            tmpwskprops.delete()
        }
    }

    it should "set auth in property file" in {
        val tmpwskprops = File.createTempFile("wskprops", ".tmp")
        val env = Map("WSK_CONFIG_FILE" -> tmpwskprops.getAbsolutePath())
        wsk.cli(Seq("property", "set", "--auth", "testKey"), env = env)
        try {
            val fileContent = FileUtils.readFileToString(tmpwskprops)
            fileContent should include("AUTH=testKey")
        } finally {
            tmpwskprops.delete()
        }
    }

    it should "set multiple property values with single command" in {
        val tmpwskprops = File.createTempFile("wskprops", ".tmp")
        val env = Map("WSK_CONFIG_FILE" -> tmpwskprops.getAbsolutePath())
        val stdout = wsk.cli(Seq("property", "set", "--auth", "testKey", "--apihost", "openwhisk.ng.bluemix.net", "--apiversion", "v1"), env = env).stdout
        try {
            stdout should include regex ("ok: whisk auth set")
            stdout should include regex ("ok: whisk API host set")
            stdout should include regex ("ok: whisk API version set")
            val fileContent = FileUtils.readFileToString(tmpwskprops)
            fileContent should include("AUTH=testKey")
            fileContent should include("APIHOST=openwhisk.ng.bluemix.net")
            fileContent should include("APIVERSION=v1")
        } finally {
            tmpwskprops.delete()
        }
    }

    it should "reject bad command" in {
        if (wsk.usePythonCLI) {
            wsk.cli(Seq("bogus"), expectedExitCode = MISUSE_EXIT).
                stderr should include("usage:")
        } else {
            val result = wsk.cli(Seq("bogus"), expectedExitCode = ERROR_EXIT)
            result.stderr should include regex ("""(?i)Run 'wsk --help' for usage""")
        }
    }

    it should "reject authenticated command when no auth key is given" in {
        // override wsk props file in case it exists
        val tmpwskprops = File.createTempFile("wskprops", ".tmp")
        val env = Map("WSK_CONFIG_FILE" -> tmpwskprops.getAbsolutePath())
        val stderr = wsk.cli(Seq("list"), env = env, expectedExitCode = MISUSE_EXIT).stderr
        try {
            stderr should include regex (s"usage[:.]") // Python CLI: "usage:", Go CLI: "usage."
            stderr should include("--auth is required")
        } finally {
            tmpwskprops.delete()
        }
    }

    behavior of "Wsk actions"

    it should "reject delete of an action without an action name" in {
        val stderr = wsk.cli(Seq("action", "delete"), expectedExitCode = ERROR_EXIT).stderr
        stderr should include("error: Invalid argument(s). An action name is required.")
        stderr should include("Run 'wsk --help' for usage.")
    }

    it should "reject delete of an action with an invalid argument" in {
        val stderr = wsk.cli(Seq("action", "delete", "actionName", "invalidArg"), expectedExitCode = ERROR_EXIT).stderr
        stderr should include("error: Invalid argument(s): invalidArg")
        stderr should include("Run 'wsk --help' for usage.")
    }

    it should "reject creating entities with invalid names" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val names = Seq(
                ("", NOTALLOWED),
                (" ", BAD_REQUEST),
                ("hi+there", BAD_REQUEST),
                ("$hola", BAD_REQUEST),
                ("dora?", BAD_REQUEST),
                ("|dora|dora?", BAD_REQUEST))

            names foreach {
                case (name, ec) =>
                    assetHelper.withCleaner(wsk.action, name, confirmDelete = false) {
                        (action, _) => action.create(name, defaultAction, expectedExitCode = ec)
                    }
            }
    }

    it should "reject get of an action without an action name" in {
        val stderr = wsk.cli(Seq("action", "get"), expectedExitCode = ERROR_EXIT).stderr
        stderr should include("error: Invalid argument(s). An action name is required.")
        stderr should include("Run 'wsk --help' for usage.")
    }

    it should "reject get of an action with an invalid argument" in {
        val stderr = wsk.cli(Seq("action", "get", "actionName", "invalidArg"), expectedExitCode = ERROR_EXIT).stderr
        stderr should include("error: Invalid argument(s): invalidArg")
        stderr should include("Run 'wsk --help' for usage.")
    }

    it should "reject create with missing file" in {
        wsk.action.create("missingFile", Some("notfound"),
            expectedExitCode = MISUSE_EXIT).
            stderr should include("not a valid file")
    }

    it should "reject action update when specified file is missing" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            // Create dummy action to update
            val name = "updateMissingFile"
            val file = Some(TestUtils.getCatalogFilename("samples/hello.js"))
            assetHelper.withCleaner(wsk.action, name) { (action, name) => action.create(name, file) }
            // Update it with a missing file
            wsk.action.create("updateMissingFile", Some("notfound"), update = true, expectedExitCode = MISUSE_EXIT)
    }

    it should "reject list of an action with an invalid argument" in {
        val stderr = wsk.cli(Seq("action", "list", "actionName", "invalidArg"), expectedExitCode = ERROR_EXIT).stderr
        stderr should include("error: Invalid argument(s): invalidArg")
        stderr should include("Run 'wsk --help' for usage.")
    }

    it should "reject invoke of an action without an action name" in {
        val stderr = wsk.cli(Seq("action", "invoke"), expectedExitCode = ERROR_EXIT).stderr
        stderr should include("error: Invalid argument(s). An action name is required.")
        stderr should include("Run 'wsk --help' for usage.")
    }

    it should "reject invoke of an action with an invalid argument" in {
        val stderr = wsk.cli(Seq("action", "invoke", "actionName", "invalidArg"), expectedExitCode = ERROR_EXIT).stderr
        stderr should include("error: Invalid argument(s): invalidArg")
        stderr should include("Run 'wsk --help' for usage.")
    }

    behavior of "Wsk packages"

    it should "reject create of a package without a package name" in {
        val stderr = wsk.cli(Seq("package", "create"), expectedExitCode = ERROR_EXIT).stderr
        stderr should include("error: Invalid argument(s). A package name is required.")
        stderr should include("Run 'wsk --help' for usage.")
    }

    it should "reject create of a package with an invalid argument" in {
        val stderr = wsk.cli(Seq("package", "create", "packageName", "invalidArg"),
            expectedExitCode = ERROR_EXIT).stderr
        stderr should include("error: Invalid argument(s): invalidArg")
        stderr should include("Run 'wsk --help' for usage.")
    }

    it should "reject update of a package without a package name" in {
        val stderr = wsk.cli(Seq("package", "update"), expectedExitCode = ERROR_EXIT).stderr
        stderr should include("error: Invalid argument(s). A package name is required.")
        stderr should include("Run 'wsk --help' for usage.")
    }

    it should "reject update of a package with an invalid argument" in {
        val stderr = wsk.cli(Seq("package", "update", "packageName", "invalidArg"),
            expectedExitCode = ERROR_EXIT).stderr
        stderr should include("error: Invalid argument(s): invalidArg")
        stderr should include("Run 'wsk --help' for usage.")
    }

    it should "reject get of a package without a package name" in {
        val stderr = wsk.cli(Seq("package", "get"), expectedExitCode = ERROR_EXIT).stderr
        stderr should include("error: Invalid argument(s). A package name is required.")
        stderr should include("Run 'wsk --help' for usage.")
    }

    it should "reject get of a package with an invalid argument" in {
        val stderr = wsk.cli(Seq("package", "get", "packageName", "invalidArg"), expectedExitCode = ERROR_EXIT).stderr
        stderr should include("error: Invalid argument(s): invalidArg")
        stderr should include("Run 'wsk --help' for usage.")
    }

    it should "reject bind of a package without a package name" in {
        val stderr = wsk.cli(Seq("package", "bind"), expectedExitCode = ERROR_EXIT).stderr
        stderr should include("error: Invalid argument(s). A package name and binding name are required.")
        stderr should include("Run 'wsk --help' for usage.")
    }

    it should "reject bind of a package without a binding name" in {
        val stderr = wsk.cli(Seq("package", "bind", "somePackage"), expectedExitCode = ERROR_EXIT).stderr
        stderr should include("error: Invalid argument(s). A package name and binding name are required.")
        stderr should include("Run 'wsk --help' for usage.")
    }

    it should "reject bind of a package with an invalid argument" in {
        val stderr = wsk.cli(Seq("package", "bind", "packageName", "bindingName", "invalidArg"),
            expectedExitCode = ERROR_EXIT).stderr
        stderr should include("error: Invalid argument(s): invalidArg")
        stderr should include("Run 'wsk --help' for usage.")
    }

    it should "reject list of a package with an invalid argument" in {
        val stderr = wsk.cli(Seq("package", "list", "namespace", "invalidArg"), expectedExitCode = ERROR_EXIT).stderr
        stderr should include("error: Invalid argument(s): invalidArg")
        stderr should include("Run 'wsk --help' for usage.")
    }

    it should "reject delete of a package without a package name" in {
        val stderr = wsk.cli(Seq("package", "delete"), expectedExitCode = ERROR_EXIT).stderr
        stderr should include("error: Invalid argument(s). A package name is required.")
        stderr should include("Run 'wsk --help' for usage.")
    }

    it should "reject delete of a package with an invalid argument" in {
        val stderr = wsk.cli(Seq("package", "delete", "namespace", "invalidArg"), expectedExitCode = ERROR_EXIT).stderr
        stderr should include("error: Invalid argument(s): invalidArg")
        stderr should include("Run 'wsk --help' for usage.")
    }

    it should "reject refresh of a package with an invalid argument" in {
        val stderr = wsk.cli(Seq("package", "refresh", "namespace", "invalidArg"), expectedExitCode = ERROR_EXIT).stderr
        stderr should include("error: Invalid argument(s): invalidArg")
        stderr should include("Run 'wsk --help' for usage.")
    }

    behavior of "Wsk entity list formatting"

    it should "create, and list a package with a long name" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "x" * 70
            assetHelper.withCleaner(wsk.pkg, name) {
                (pkg, _) =>
                    pkg.create(name)
            }
            retry({
                wsk.pkg.list().stdout should include(s"$name private")
            }, 5, Some(1 second))
    }

    it should "create, and list an action with a long name" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "x" * 70
            val file = Some(TestUtils.getCatalogFilename("samples/hello.js"))
            assetHelper.withCleaner(wsk.action, name) {
                (action, _) =>
                    action.create(name, file)
            }
            retry({
                wsk.action.list().stdout should include(s"$name private")
            }, 5, Some(1 second))
    }

    it should "create, and list a trigger with a long name" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "x" * 70
            assetHelper.withCleaner(wsk.trigger, name) {
                (trigger, _) =>
                    trigger.create(name)
            }
            retry({
                wsk.trigger.list().stdout should include(s"$name private")
            }, 5, Some(1 second))
    }

    it should "create, and list a rule with a long name" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val ruleName = "x" * 70
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
            retry({
                wsk.rule.list().stdout should include(s"$ruleName private")
            }, 5, Some(1 second))
    }

    behavior of "Wsk action parameters"

    it should "create an action with different permutations of limits" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val file = Some(TestUtils.getCatalogFilename("samples/hello.js"))

            def testLimit(timeout: Option[Duration] = None, memory: Option[ByteSize] = None, logs: Option[ByteSize] = None, ec: Int = SUCCESS_EXIT) = {
                // Limits to assert, standard values if CLI omits certain values
                val limits = JsObject(
                    "timeout" -> timeout.getOrElse(STD_DURATION).toMillis.toJson,
                    "memory" -> memory.getOrElse(STD_MEMORY).toMB.toInt.toJson,
                    "logs" -> logs.getOrElse(STD_LOGSIZE).toMB.toInt.toJson)

                val name = "ActionLimitTests" + Instant.now.toEpochMilli
                val createResult = assetHelper.withCleaner(wsk.action, name, confirmDelete = (ec == SUCCESS_EXIT)) {
                    (action, _) =>
                        val result = action.create(name, file, logsize = logs, memory = memory, timeout = timeout, expectedExitCode = DONTCARE_EXIT)
                        withClue(s"create failed for parameters: timeout = $timeout, memory = $memory, logsize = $logs:") {
                            result.exitCode should be(ec)
                        }
                        result
                }

                if (ec == SUCCESS_EXIT) {
                    val JsObject(parsedAction) = wsk.action.get(name).stdout.split("\n").tail.mkString.parseJson.asJsObject
                    parsedAction("limits") shouldBe limits
                } else {
                    createResult.stderr should include("allowed threshold")
                }
            }

            // Assert for valid permutations that the values are set correctly
            for {
                time <- Seq(None, Some(MIN_DURATION), Some(MAX_DURATION))
                mem <- Seq(None, Some(MIN_MEMORY), Some(MAX_MEMORY))
                log <- Seq(None, Some(MIN_LOGSIZE), Some(MAX_LOGSIZE))
            } testLimit(time, mem, log)

            // Assert that invalid permutation are rejected
            testLimit(Some(0.milliseconds), None, None, BAD_REQUEST)
            testLimit(Some(100.minutes), None, None, BAD_REQUEST)
            testLimit(None, Some(0.MB), None, BAD_REQUEST)
            testLimit(None, Some(32768.MB), None, BAD_REQUEST)
            testLimit(None, None, Some(32768.MB), BAD_REQUEST)
    }

    ignore should "create a trigger using property file" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "listTriggers"
            val tmpProps = File.createTempFile("wskprops", ".tmp")
            val env = Map("WSK_CONFIG_FILE" -> tmpProps.getAbsolutePath())
            wsk.cli(Seq("property", "set", "--auth", wp.authKey) ++ wskprops.overrides, env = env)
            assetHelper.withCleaner(wsk.trigger, name) {
                (trigger, _) =>
                    wsk.cli(Seq("-i", "trigger", "create", name), env = env)
            }
            tmpProps.delete()
    }

}
