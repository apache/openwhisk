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

import java.io.File
import java.time.Instant

import org.apache.commons.io.FileUtils
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import spray.json._
import spray.json.DefaultJsonProtocol._
import spray.json.pimpAny
import spray.json.pimpString
import spray.json.JsObject

import common.TestHelpers
import common.TestUtils
import common.TestUtils.ANY_ERROR_EXIT
import common.TestUtils.BAD_REQUEST
import common.TestUtils.CONFLICT
import common.TestUtils.FORBIDDEN
import common.TestUtils.MISUSE_EXIT
import common.TestUtils.NOTALLOWED
import common.TestUtils.NOT_FOUND
import common.TestUtils.SUCCESS_EXIT
import common.TestUtils.TIMEOUT
import common.TestUtils.UNAUTHORIZED
import common.TestUtils.ERROR_EXIT
import common.WhiskProperties
import common.Wsk
import common.WskProps
import common.WskTestHelpers
import whisk.core.entity.WhiskPackage
import whisk.core.entity.ByteSize
import whisk.core.entity.size.SizeInt
import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt

@RunWith(classOf[JUnitRunner])
class WskBasicTests
    extends TestHelpers
    with WskTestHelpers {

    implicit val wskprops = WskProps()
    val wsk = new Wsk(usePythonCLI = false)
    val defaultAction = Some(TestUtils.getCatalogFilename("samples/hello.js"))

    behavior of "Wsk CLI"

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

    it should "validate default property values" in {
        val tmpwskprops = File.createTempFile("wskprops", ".tmp")
        val env = Map("WSK_CONFIG_FILE" -> tmpwskprops.getAbsolutePath())
        try {
            wsk.cli(Seq("property", "unset", "--auth", "--apihost", "--apiversion", "--namespace"), env = env)
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

    it should "show api build version" in {
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
        val stdout = wsk.cli(Seq("--apihost", "xxxx.yyyy", "property", "get", "--apibuild"), expectedExitCode = ANY_ERROR_EXIT).stdout
        stdout should not include regex("""(?i)whisk API build\s+201.*""")
        stdout should include regex ("Cannot determine API build")
    }

    ignore should "show api build using http apihost" in {
        val wsk = new Wsk(usePythonCLI = true)
        val apihost = s"http://${WhiskProperties.getControllerHost}:${WhiskProperties.getControllerPort}"
        val stdout = wsk.cli(Seq("--apihost", apihost, "property", "get", "--apibuild")).stdout
        stdout should include regex ("""(?i)whisk API build\s+201.*""")
    }

    it should "show api build number" in {
        val tmpwskprops = File.createTempFile("wskprops", ".tmp")
        val env = Map("WSK_CONFIG_FILE" -> tmpwskprops.getAbsolutePath())
        wsk.cli(wskprops.overrides ++ Seq("property", "set"), env = env)
        val stdout = wsk.cli(Seq("property", "get", "--apibuildno", "-i"), env = env).stdout
        try {
            stdout should include regex ("""(?i)whisk API build.*\s+.*""")
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

    it should "delete multiple property values with single command" in {
        val tmpwskprops = File.createTempFile("wskprops", ".tmp")
        val env = Map("WSK_CONFIG_FILE" -> tmpwskprops.getAbsolutePath())
        val stdout = wsk.cli(Seq("property", "unset", "--auth", "--apihost", "--apiversion", "--namespace"), env = env).stdout
        try {
            stdout should include regex ("ok: whisk auth unset")
            stdout should include regex ("ok: whisk API host unset")
            stdout should include regex ("ok: whisk API version unset")
            stdout should include regex ("ok: whisk namespace unset")
        } finally {
            tmpwskprops.delete()
        }
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

    it should "reject unauthenticated access" in {
        implicit val wskprops = WskProps("xxx") // shadow properties
        val errormsg = "The supplied authentication is invalid"
        wsk.namespace.list(expectedExitCode = UNAUTHORIZED).
            stderr should include(errormsg)
        wsk.namespace.get(expectedExitCode = UNAUTHORIZED).
            stderr should include(errormsg)
    }

    it should "reject deleting action in shared package not owned by authkey" in {
        wsk.action.get("/whisk.system/util/cat") // make sure it exists
        wsk.action.delete("/whisk.system/util/cat", expectedExitCode = FORBIDDEN)
    }

    it should "reject create action in shared package not owned by authkey" in {
        wsk.action.get("/whisk.system/util/notallowed", expectedExitCode = NOT_FOUND) // make sure it does not exist
        val file = Some(TestUtils.getCatalogFilename("samples/hello.js"))
        try {
            wsk.action.create("/whisk.system/util/notallowed", file, expectedExitCode = FORBIDDEN)
        } finally {
            wsk.action.sanitize("/whisk.system/util/notallowed")
        }
    }

    it should "reject update action in shared package not owned by authkey" in {
        wsk.action.create("/whisk.system/util/cat", None,
            update = true, shared = Some(true), expectedExitCode = FORBIDDEN)
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
        val tmpwskprops = File.createTempFile("wskprops", ".tmp")
        val env = Map("WSK_CONFIG_FILE" -> tmpwskprops.getAbsolutePath())
        wsk.cli(Seq("property", "unset", "--auth"), env = env)
        val stderr = wsk.cli(Seq("list"), env = env, expectedExitCode = MISUSE_EXIT).stderr
        try {
            stderr should include regex (s"usage[:.]") // Python CLI: "usage:", Go CLI: "usage."
            stderr should include("--auth is required")
        } finally {
            tmpwskprops.delete()
        }
    }

    behavior of "Wsk Package CLI"

    it should "list shared packages" in {
        val result = wsk.pkg.list(Some("/whisk.system")).stdout
        result should include regex ("""/whisk.system/samples\s+shared""")
        result should include regex ("""/whisk.system/util\s+shared""")
    }

    it should "list shared package actions" in {
        val result = wsk.action.list(Some("/whisk.system/util")).stdout
        result should include regex ("""/whisk.system/util/head\s+shared""")
        result should include regex ("""/whisk.system/util/date\s+shared""")
    }

    it should "create, update, get and list a package" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "samplePackage"
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

    it should "create, and list a package with a long name" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            assetHelper.withCleaner(wsk.pkg, name) {
                (pkg, _) =>
                    pkg.create(name)
            }
            wsk.pkg.list().stdout should include(name + " private")
    }

    it should "create a package binding" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "bindPackage"
            val provider = "/whisk.system/samples"
            val annotations = Map("a" -> "A".toJson, WhiskPackage.bindingFieldName -> "xxx".toJson)
            assetHelper.withCleaner(wsk.pkg, name) {
                (pkg, _) =>
                    pkg.bind(provider, name, annotations = annotations)
            }
            val stdout = wsk.pkg.get(name).stdout
            stdout should include regex (""""key": "a"""")
            stdout should include regex (""""value": "A"""")
            stdout should include regex (s""""key": "${WhiskPackage.bindingFieldName}"""")
            stdout should not include regex(""""key": "xxx"""")
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

            val resJSON = stdout.substring(stdout.indexOf("\n") + 1).parseJson.asJsObject
            assert(resJSON.fields("annotations") == getValidJSONTestArgOutput)
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

            val resJSON = stdout.substring(stdout.indexOf("\n") + 1).parseJson.asJsObject
            assert(resJSON.fields("parameters") == getValidJSONTestArgOutput)
    }

    it should "not create a package when -a is specified without arguments" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val stderr = wsk.cli(wskprops.overrides ++ Seq("package", "create", "packageName", "--auth", wp.authKey,
                "-a"), expectedExitCode = ERROR_EXIT).stderr
            stderr should include("Annotation arguments must be a key value pair")
    }

    it should "not create a package when -p is specified without arguments" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val stderr = wsk.cli(wskprops.overrides ++ Seq("package", "create", "packageName", "--auth", wp.authKey,
                "-p"), expectedExitCode = ERROR_EXIT).stderr
            stderr should include("Parameter arguments must be a key value pair")
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

            val resJSON = stdout.substring(stdout.indexOf("\n") + 1).parseJson.asJsObject
            assert(resJSON.fields("parameters") == getEscapedJSONTestArgOutput)
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

            val resJSON = stdout.substring(stdout.indexOf("\n") + 1).parseJson.asJsObject
            assert(resJSON.fields("annotations") == getEscapedJSONTestArgOutput)
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
            val file = Some(TestUtils.getCatalogFilename("samples/hello.js"))

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
            val file = Some(TestUtils.getCatalogFilename("samples/hello.js"))
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

    it should "create, and list an action with a long name" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            val file = Some(TestUtils.getCatalogFilename("samples/hello.js"))
            assetHelper.withCleaner(wsk.action, name) {
                (action, _) =>
                    action.create(name, file)
            }
            wsk.action.list().stdout should include(name + " private")
    }

    it should "create an action with different permutations of limits" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val file = Some(TestUtils.getCatalogFilename("samples/hello.js"))

            def testLimit(timeout: Option[Duration] = None, memory: Option[Int] = None, logs: Option[ByteSize] = None) = {
                println(s"Passing timeout = $timeout, memory = $memory, logs = $logs")

                // Limits to assert, standard values if CLI omits certain values
                val limits = JsObject(
                    "timeout" -> timeout.map(_.toMillis).getOrElse(60000L).toJson,
                    "memory" -> memory.getOrElse(256).toJson,
                    "logs" -> logs.map(_.toMB).getOrElse(10L).toJson)

                val name = "ActionLimitTests" + Instant.now.toEpochMilli
                assetHelper.withCleaner(wsk.action, name) { (action, _) =>
                    action.create(name, file, logsize = logs, memory = memory, timeout = timeout)
                }

                val JsObject(parsedAction) = wsk.action.get(name).stdout.split("\n").tail.mkString.parseJson.asJsObject
                parsedAction("limits") shouldBe limits
            }

            // Assert for every permutation that the values are set correctly
            for {
                time <- Seq(None, Some(100.milliseconds), Some(2.minutes), Some(5.minutes))
                mem <- Seq(None, Some(128), Some(256), Some(512))
                log <- Seq(None, Some(0.MB), Some(5.MB), Some(10.MB))
            } testLimit(time, mem, log)
    }

    it should "get an action" in {
        wsk.action.get("/whisk.system/samples/wordCount").
            stdout should include("words")
    }

    it should "reject delete of action that does not exist" in {
        wsk.action.sanitize("deleteFantasy").
            stderr should include regex ("""The requested resource does not exist. \(code \d+\)""")
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

            val resJSON = stdout.substring(stdout.indexOf("\n") + 1).parseJson.asJsObject
            assert(resJSON.fields("annotations") == getValidJSONTestArgOutput)
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

            val resJSON = stdout.substring(stdout.indexOf("\n") + 1).parseJson.asJsObject
            assert(resJSON.fields("parameters") == getValidJSONTestArgOutput)
    }

    it should "not create an action when -a is specified without arguments" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val stderr = wsk.cli(wskprops.overrides ++ Seq("action", "create", "actionName", "--auth", wp.authKey,
                "-a"), expectedExitCode = ERROR_EXIT).stderr
            stderr should include("Annotation arguments must be a key value pair")
    }

    it should "not create an action when -p is specified without arguments" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val stderr = wsk.cli(wskprops.overrides ++ Seq("action", "create", "actionName", "--auth", wp.authKey,
                "-p"), expectedExitCode = ERROR_EXIT).stderr
            stderr should include("Parameter arguments must be a key value pair")
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

            val resJSON = stdout.substring(stdout.indexOf("\n") + 1).parseJson.asJsObject
            assert(resJSON.fields("parameters") == getEscapedJSONTestArgOutput)
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

            val resJSON = stdout.substring(stdout.indexOf("\n") + 1).parseJson.asJsObject
            assert(resJSON.fields("annotations") == getEscapedJSONTestArgOutput)
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
                    activation.fields("response").asJsObject.fields("status") should be("action developer error".toJson)
                    // representing nodejs giving an error when given malformed.js
                    activation.fields("response").asJsObject.toString should include("ReferenceError")
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
                (action, _) => action.create(name, Some(TestUtils.getCatalogFilename("samples/wc.js")))
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
                    activation.fields("response").asJsObject.fields("result") should be(dynamicParams.toJson)
                    activation.fields("end") should be(Instant.EPOCH.toEpochMilli.toJson)
            }

            wsk.trigger.list().stdout should include(name)
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

    it should "create, and list a trigger with a long name" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            assetHelper.withCleaner(wsk.trigger, name) {
                (trigger, _) =>
                    trigger.create(name)
            }
            wsk.trigger.list().stdout should include(name + " private")
    }

    it should "not create a trigger when feed fails to initialize" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            assetHelper.withCleaner(wsk.trigger, "badfeed", confirmDelete = false) {
                (trigger, name) =>
                    trigger.create(name, feed = Some(s"bogus"), expectedExitCode = ANY_ERROR_EXIT).
                        exitCode should { equal(NOT_FOUND) or equal(FORBIDDEN) }
                    trigger.get(name, expectedExitCode = NOT_FOUND)

                    trigger.create(name, feed = Some(s"bogus/feed"), expectedExitCode = ANY_ERROR_EXIT).
                        exitCode should { equal(NOT_FOUND) or equal(FORBIDDEN) }
                    trigger.get(name, expectedExitCode = NOT_FOUND)

                    // verify that the feed runs and returns an application error (502 or Gateway Timeout)
                    trigger.create(name, feed = Some(s"/whisk.system/github/webhook"), expectedExitCode = TIMEOUT)
                    trigger.get(name, expectedExitCode = NOT_FOUND)
            }
    }

    it should "create, and get a trigger to verify annotation parsing" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "triggerAnnotations"

            assetHelper.withCleaner(wsk.trigger, name) {
                (trigger, _) =>
                    trigger.create(name, annotations = getValidJSONTestArgInput)
            }

            val stdout = wsk.trigger.get(name).stdout
            assert(stdout.startsWith(s"ok: got trigger $name\n"))

            val resJSON = stdout.substring(stdout.indexOf("\n") + 1).parseJson.asJsObject
            assert(resJSON.fields("annotations") == getValidJSONTestArgOutput)
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

            val resJSON = stdout.substring(stdout.indexOf("\n") + 1).parseJson.asJsObject
            assert(resJSON.fields("parameters") == getValidJSONTestArgOutput)
    }

    it should "not create a trigger when -a is specified without arguments" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val stderr = wsk.cli(wskprops.overrides ++ Seq("trigger", "create", "triggerName", "--auth", wp.authKey,
                "-a"), expectedExitCode = ERROR_EXIT).stderr
            stderr should include("Annotation arguments must be a key value pair")
    }

    it should "not create a trigger when -p is specified without arguments" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val stderr = wsk.cli(wskprops.overrides ++ Seq("trigger", "create", "triggerName", "--auth", wp.authKey,
                "-p"), expectedExitCode = ERROR_EXIT).stderr
            stderr should include("Parameter arguments must be a key value pair")
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

            val resJSON = stdout.substring(stdout.indexOf("\n") + 1).parseJson.asJsObject
            assert(resJSON.fields("parameters") == getEscapedJSONTestArgOutput)
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

            val resJSON = stdout.substring(stdout.indexOf("\n") + 1).parseJson.asJsObject
            assert(resJSON.fields("annotations") == getEscapedJSONTestArgOutput)
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
                    rule.create(name, trigger = triggerName, action = actionName, update = true)
            }

            val stdout = wsk.rule.get(ruleName).stdout
            stdout should include(ruleName)
            stdout should include(triggerName)
            stdout should include(actionName)
            stdout should include regex (""""version": "0.0.2"""")
            wsk.rule.list().stdout should include(ruleName)
    }

    it should "create, and list a rule with a long name" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val ruleName = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
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
            wsk.rule.list().stdout should include(ruleName + " private")
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

    def getEscapedJSONTestArgInput(parameters: Boolean = true) = Seq(
        if (parameters) "-p" else "-a",
        "\"key\"with\\escapes",
        "{\"invalid\": \"J\"S\"ON\"}",          // Cannot put qoutes inside JSON
        if (parameters) "-p" else "-a",
        "another\"escape\"",
        "{\"valid\": \"\\nJ\\rO\\tS\\bN\\f\"}",   // Can escape \n, \r, \t, \b, \f
        if (parameters) "-p" else "-a",
        "escape\\again",
        "{\"invalid\": \"JS\\ON\"}"             // Cannot escape anything besides \n, \r, \t, \b, \f
    )

    def getEscapedJSONTestArgOutput() = JsArray(
        JsObject(
            "key" -> JsString("\"key\"with\\escapes"),
            "value" -> JsString("{\"invalid\": \"J\"S\"ON\"}")
        ),
        JsObject(
            "key" -> JsString("another\"escape\""),
            "value" -> JsObject(
                "valid" -> JsString("\nJ\rO\tS\bN\f")
            )
        ),
        JsObject(
            "key" -> JsString("escape\\again"),
            "value" -> JsString("{\"invalid\": \"JS\\ON\"}")
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
