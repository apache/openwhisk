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
import common.TestUtils.NOT_FOUND
import common.TestUtils.NOT_ALLOWED
import common.TestUtils.SUCCESS_EXIT
import common.WhiskProperties
import common.Wsk
import common.WskProps
import common.WskTestHelpers
import spray.json.DefaultJsonProtocol._
import spray.json._
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
                ("", NOT_ALLOWED),
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

    it should "create, and get an action to verify annotation parsing" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "actionAnnotations"

            val file = Some(TestUtils.getCatalogFilename("samples/hello.js"))
            assetHelper.withCleaner(wsk.action, name) {
                (action, _) =>
                    action.create(name, file, annotations = getValidJSONTestArgInput)
            }

            val stdout = wsk.action.get(name).stdout
            assert(stdout.startsWith(s"ok: got action $name\n"))

            wsk.parseJsonString(stdout).fields("annotations") shouldBe getValidJSONTestArgOutput
    }

    it should "create, and get an action to verify parameter parsing" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "actionParameters"

            val file = Some(TestUtils.getCatalogFilename("samples/hello.js"))
            assetHelper.withCleaner(wsk.action, name) {
                (action, _) =>
                    action.create(name, file, parameters = getValidJSONTestArgInput)
            }

            val stdout = wsk.action.get(name).stdout
            assert(stdout.startsWith(s"ok: got action $name\n"))

            wsk.parseJsonString(stdout).fields("parameters") shouldBe getValidJSONTestArgOutput
    }

    it should "create an action with the proper parameter escapes" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "actionName"
            val file = TestUtils.getCatalogFilename("samples/hello.js")
            assetHelper.withCleaner(wsk.action, name) {
                (action, _) =>
                    wsk.cli(wskprops.overrides ++ Seq("action", "create", wsk.action.fqn(name), file, "--auth", wp.authKey) ++
                      getEscapedJSONTestArgInput()
                    )
            }

            val stdout = wsk.action.get(name).stdout
            assert(stdout.startsWith(s"ok: got action $name\n"))

            wsk.parseJsonString(stdout).fields("parameters") shouldBe getEscapedJSONTestArgOutput
    }

    it should "create an action with the proper annotation escapes" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "actionName"
            val file = TestUtils.getCatalogFilename("samples/hello.js")
            assetHelper.withCleaner(wsk.action, name) {
                (action, _) =>
                    wsk.cli(wskprops.overrides ++ Seq("action", "create", wsk.action.fqn(name), file, "--auth", wp.authKey) ++
                      getEscapedJSONTestArgInput(false)
                    )
            }

            val stdout = wsk.action.get(name).stdout
            assert(stdout.startsWith(s"ok: got action $name\n"))

            wsk.parseJsonString(stdout).fields("annotations") shouldBe getEscapedJSONTestArgOutput
    }

    it should "invoke an action that exits and get appropriate error" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "abort"
            assetHelper.withCleaner(wsk.action, name) {
                (action, _) => action.create(name, Some(TestUtils.getTestActionFilename("exit.py")))
            }

            withActivation(wsk.activation, wsk.action.invoke(name)) {
                activation =>
                    val response = activation.fields("response").asJsObject
                    response.fields("result") shouldBe JsObject("error" -> "the action did not produce a valid JSON response".toJson)
                    response.fields("status") shouldBe "action developer error".toJson
            }
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

    it should "create, and get a package to verify annotation parsing" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "packageAnnotations"

            assetHelper.withCleaner(wsk.pkg, name) {
                (pkg, _) =>
                    pkg.create(name, annotations = getValidJSONTestArgInput)
            }

            val stdout = wsk.pkg.get(name).stdout
            assert(stdout.startsWith(s"ok: got package $name\n"))

            wsk.parseJsonString(stdout).fields("annotations") shouldBe getValidJSONTestArgOutput
    }

    it should "create, and get a package to verify parameter parsing" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "packageParameters"

            assetHelper.withCleaner(wsk.pkg, name) {
                (pkg, _) =>
                    pkg.create(name, parameters = getValidJSONTestArgInput)
            }

            val stdout = wsk.pkg.get(name).stdout
            assert(stdout.startsWith(s"ok: got package $name\n"))

            wsk.parseJsonString(stdout).fields("parameters") shouldBe getValidJSONTestArgOutput
    }

    it should "create a package with the proper parameter escapes" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "packageName"
            assetHelper.withCleaner(wsk.pkg, name) {
                (pkg, _) =>
                    wsk.cli(wskprops.overrides ++ Seq("package", "create", wsk.pkg.fqn(name), "--auth", wp.authKey) ++
                      getEscapedJSONTestArgInput()
                    )
            }

            val stdout = wsk.pkg.get(name).stdout
            assert(stdout.startsWith(s"ok: got package $name\n"))

            wsk.parseJsonString(stdout).fields("parameters") shouldBe getEscapedJSONTestArgOutput
    }

    it should "create an package with the proper annotation escapes" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "packageName"
            assetHelper.withCleaner(wsk.pkg, name) {
                (pkg, _) =>
                    wsk.cli(wskprops.overrides ++ Seq("package", "create", wsk.pkg.fqn(name), "--auth", wp.authKey) ++
                      getEscapedJSONTestArgInput(false)
                    )
            }

            val stdout = wsk.pkg.get(name).stdout
            assert(stdout.startsWith(s"ok: got package $name\n"))

            wsk.parseJsonString(stdout).fields("annotations") shouldBe getEscapedJSONTestArgOutput
    }

    behavior of "Wsk triggers"

    it should "create, and get a trigger to verify annotation parsing" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "triggerAnnotations"

            assetHelper.withCleaner(wsk.trigger, name) {
                (trigger, _) =>
                    trigger.create(name, annotations = getValidJSONTestArgInput)
            }

            val stdout = wsk.trigger.get(name).stdout
            assert(stdout.startsWith(s"ok: got trigger $name\n"))

            wsk.parseJsonString(stdout).fields("annotations") shouldBe getValidJSONTestArgOutput
    }

    it should "create, and get a trigger to verify parameter parsing" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "triggerParameters"

            assetHelper.withCleaner(wsk.trigger, name) {
                (trigger, _) =>
                    trigger.create(name, parameters = getValidJSONTestArgInput)
            }

            val stdout = wsk.trigger.get(name).stdout
            assert(stdout.startsWith(s"ok: got trigger $name\n"))

            wsk.parseJsonString(stdout).fields("parameters") shouldBe getValidJSONTestArgOutput
    }

    it should "display a trigger summary when --summary flag is used with 'wsk trigger get'" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val triggerName = "mySummaryTrigger"
            assetHelper.withCleaner(wsk.trigger, triggerName, confirmDelete = false) {
                (trigger, name) => trigger.create(name)
            }

            // Summary namespace should match one of the allowable namespaces (typically 'guest')
            val ns_regex_list = wsk.namespace.list().stdout.trim.replace('\n', '|')
            val stdout = wsk.trigger.get(triggerName, summary = true).stdout
            stdout should include regex (s"(?i)trigger\\s+/${ns_regex_list}/${triggerName}")
    }

    it should "create a trigger with the proper parameter escapes" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "triggerName"
            assetHelper.withCleaner(wsk.trigger, name) {
                (trigger, _) =>
                    wsk.cli(wskprops.overrides ++ Seq("trigger", "create", wsk.trigger.fqn(name), "--auth", wp.authKey) ++
                      getEscapedJSONTestArgInput()
                    )
            }

            val stdout = wsk.trigger.get(name).stdout
            assert(stdout.startsWith(s"ok: got trigger $name\n"))

            wsk.parseJsonString(stdout).fields("parameters") shouldBe getEscapedJSONTestArgOutput
    }

    it should "create a trigger with the proper annotation escapes" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "triggerName"
            assetHelper.withCleaner(wsk.trigger, name) {
                (trigger, _) =>
                    wsk.cli(wskprops.overrides ++ Seq("trigger", "create", wsk.trigger.fqn(name), "--auth", wp.authKey) ++
                      getEscapedJSONTestArgInput(false)
                    )
            }

            val stdout = wsk.trigger.get(name).stdout
            assert(stdout.startsWith(s"ok: got trigger $name\n"))

            wsk.parseJsonString(stdout).fields("annotations") shouldBe getEscapedJSONTestArgOutput
    }

    it should "not create a trigger when feed fails to initialize" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            assetHelper.withCleaner(wsk.trigger, "badfeed", confirmDelete = false) {
                (trigger, name) =>
                    trigger.create(name, feed = Some(s"bogus"), expectedExitCode = ANY_ERROR_EXIT).
                        exitCode should equal(NOT_FOUND)
                    trigger.get(name, expectedExitCode = NOT_FOUND)

                    trigger.create(name, feed = Some(s"bogus/feed"), expectedExitCode = ANY_ERROR_EXIT).
                        exitCode should equal(NOT_FOUND)
                    trigger.get(name, expectedExitCode = NOT_FOUND)
            }
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

    behavior of "Wsk params and annotations"

    it should "reject commands that are executed with params or annot that are not key/value pairs" in {
        val invalidParamMsg = "Arguments for '-p' must be a key/value pair"
        val invalidAnnotMsg = "Arguments for '-a' must be a key/value pair"
        val invalidArgs = Seq(
            (Seq("action", "create", "actionName", "-p"), invalidParamMsg),
            (Seq("action", "create", "actionName", "-p", "key"), invalidParamMsg),
            (Seq("action", "update", "actionName", "-p"), invalidParamMsg),
            (Seq("action", "update", "actionName", "-p", "key"), invalidParamMsg),
            (Seq("action", "invoke", "actionName", "-p"), invalidParamMsg),
            (Seq("action", "invoke", "actionName", "-p", "key"), invalidParamMsg),
            (Seq("action", "create", "actionName", "-a"), invalidAnnotMsg),
            (Seq("action", "create", "actionName", "-a", "key"), invalidAnnotMsg),
            (Seq("action", "update", "actionName", "-a"), invalidAnnotMsg),
            (Seq("action", "update", "actionName", "-a", "key"), invalidAnnotMsg),
            (Seq("action", "invoke", "actionName", "-a"), invalidAnnotMsg),
            (Seq("action", "invoke", "actionName", "-a", "key"), invalidAnnotMsg),
            (Seq("package", "create", "packageName", "-p"), invalidParamMsg),
            (Seq("package", "create", "packageName", "-p", "key"), invalidParamMsg),
            (Seq("package", "update", "packageName", "-p"), invalidParamMsg),
            (Seq("package", "update", "packageName", "-p", "key"), invalidParamMsg),
            (Seq("package", "bind", "packageName", "boundPackageName", "-p"), invalidParamMsg),
            (Seq("package", "bind", "packageName", "boundPackageName", "-p", "key"), invalidParamMsg),
            (Seq("package", "create", "packageName", "-a"), invalidAnnotMsg),
            (Seq("package", "create", "packageName", "-a", "key"), invalidAnnotMsg),
            (Seq("package", "update", "packageName", "-a"), invalidAnnotMsg),
            (Seq("package", "update", "packageName", "-a", "key"), invalidAnnotMsg),
            (Seq("package", "bind", "packageName", "boundPackageName", "-a"), invalidAnnotMsg),
            (Seq("package", "bind", "packageName", "boundPackageName", "-a", "key"), invalidAnnotMsg),
            (Seq("trigger", "create", "triggerName", "-p"), invalidParamMsg),
            (Seq("trigger", "create", "triggerName", "-p", "key"), invalidParamMsg),
            (Seq("trigger", "update", "triggerName", "-p"), invalidParamMsg),
            (Seq("trigger", "update", "triggerName", "-p", "key"), invalidParamMsg),
            (Seq("trigger", "fire", "triggerName", "-p"), invalidParamMsg),
            (Seq("trigger", "fire", "triggerName", "-p", "key"), invalidParamMsg),
            (Seq("trigger", "create", "triggerName", "-a"), invalidAnnotMsg),
            (Seq("trigger", "create", "triggerName", "-a", "key"), invalidAnnotMsg),
            (Seq("trigger", "update", "triggerName", "-a"), invalidAnnotMsg),
            (Seq("trigger", "update", "triggerName", "-a", "key"), invalidAnnotMsg),
            (Seq("trigger", "fire", "triggerName", "-a"), invalidAnnotMsg),
            (Seq("trigger", "fire", "triggerName", "-a", "key"), invalidAnnotMsg)
        )

        invalidArgs foreach {
            case (cmd, err) =>
              val stderr = wsk.cli(cmd, expectedExitCode = ERROR_EXIT).stderr
              stderr should include(err)
              stderr should include("Run 'wsk --help' for usage.")
        }
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

    def getEscapedJSONTestArgInput(parameters: Boolean = true) = Seq(
        if (parameters) "-p" else "-a",
        "\"key\"with\\escapes",                 // key:   key"with\escapes (will be converted to JSON string "key\"with\\escapes")
        "{\"valid\": \"JSON\"}",                // value: {"valid":"JSON"}
        if (parameters) "-p" else "-a",
        "another\"escape\"",                    // key:   another"escape" (will be converted to JSON string "another\"escape\"")
        "{\"valid\": \"\\nJ\\rO\\tS\\bN\\f\"}", // value: {"valid":"\nJ\rO\tS\bN\f"}  JSON strings can escape: \n, \r, \t, \b, \f
        // NOTE: When uncommentting these tests, be sure to include the expected response in getEscapedJSONTestArgOutput()
        //        if (parameters) "-p" else "-a",
        //        "escape\\again",                        // key:   escape\again (will be converted to JSON string "escape\\again")
        //        "{\"valid\": \"JS\\u2312ON\"}",         // value: {"valid":"JS\u2312ON"}   JSON strings can have escaped 4 digit unicode
        //        if (parameters) "-p" else "-a",
        //        "mykey",                                // key:   mykey  (will be converted to JSON string "key")
        //        "{\"valid\": \"JS\\/ON\"}",             // value: {"valid":"JS\/ON"}   JSON strings can have escaped \/
        if (parameters) "-p" else "-a",
        "key1",                                 // key:   key  (will be converted to JSON string "key")
        "{\"nonascii\": \"日本語\"}",           // value: {"nonascii":"日本語"}   JSON strings can have non-ascii
        if (parameters) "-p" else "-a",
        "key2",                                 // key:   key  (will be converted to JSON string "key")
        "{\"valid\": \"J\\\\SO\\\"N\"}"         // value: {"valid":"J\\SO\"N"}   JSON strings can have escaped \\ and \"
    )

    def getEscapedJSONTestArgOutput() = JsArray(
        JsObject(
            "key" -> JsString("\"key\"with\\escapes"),
            "value" -> JsObject(
                "valid" -> JsString("JSON")
            )
        ),
        JsObject(
            "key" -> JsString("another\"escape\""),
            "value" -> JsObject(
                "valid" -> JsString("\nJ\rO\tS\bN\f")
            )
        ),
        JsObject(
            "key" -> JsString("key1"),
            "value" -> JsObject(
                "nonascii" -> JsString("日本語")
            )
        ),
        JsObject(
            "key" -> JsString("key2"),
            "value" -> JsObject(
                "valid" -> JsString("J\\SO\"N")
            )
        )
    )

    def getValidJSONTestArgOutput() = JsArray(
        JsObject(
            "key" -> JsString("number"),
            "value" -> JsNumber(8)
        ),
        JsObject(
            "key" -> JsString("objArr"),
            "value" -> JsArray(
                JsObject(
                    "name" -> JsString("someName"),
                    "required" -> JsBoolean(true)
                ),
                JsObject(
                    "name" -> JsString("events"),
                    "count" -> JsNumber(10)
                )
            )
        ),
        JsObject(
            "key" -> JsString("strArr"),
            "value" -> JsArray(
                JsString("44"),
                JsString("55")
            )
        ),
        JsObject(
            "key" -> JsString("string"),
            "value" -> JsString("This is a string")
        ),
        JsObject(
            "key" -> JsString("numArr"),
            "value" -> JsArray(
                JsNumber(44),
                JsNumber(55)
            )
        ),
        JsObject(
            "key" -> JsString("object"),
            "value" -> JsObject(
                "objString" -> JsString("aString"),
                "objStrNum" -> JsString("123"),
                "objNum" -> JsNumber(300),
                "objBool" -> JsBoolean(false),
                "objNumArr" -> JsArray(
                    JsNumber(1),
                    JsNumber(2)
                ),
                "objStrArr" -> JsArray(
                    JsString("1"),
                    JsString("2")
                )
            )
        ),
        JsObject(
            "key" -> JsString("strNum"),
            "value" -> JsString("9")
        )
    )

    def getValidJSONTestArgInput() = Map(
        "string" -> JsString("This is a string"),
        "strNum" -> JsString("9"),
        "number" -> JsNumber(8),
        "numArr" -> JsArray(
            JsNumber(44),
            JsNumber(55)
        ),
        "strArr" -> JsArray(
            JsString("44"),
            JsString("55")
        ),
        "objArr" -> JsArray(
            JsObject(
                "name" -> JsString("someName"),
                "required" -> JsBoolean(true)
            ),
            JsObject(
                "name" -> JsString("events"),
                "count" -> JsNumber(10)
            )
        ),
        "object" -> JsObject(
            "objString" -> JsString("aString"),
            "objStrNum" -> JsString("123"),
            "objNum" -> JsNumber(300),
            "objBool" -> JsBoolean(false),
            "objNumArr" -> JsArray(
                JsNumber(1),
                JsNumber(2)
            ),
            "objStrArr" -> JsArray(
                JsString("1"),
                JsString("2")
            )
        )
    )
}
