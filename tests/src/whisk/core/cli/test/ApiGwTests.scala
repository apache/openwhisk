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

import java.time.Instant
import scala.concurrent.duration._
import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfterAll
import org.scalatest.junit.JUnitRunner
import common.TestHelpers
import common.TestUtils._
import common.TestUtils
import common.WhiskProperties
import common.Wsk
import common.WskAdmin
import common.WskProps
import common.WskTestHelpers

/**
 * Tests for testing the CLI "api" subcommand.  Most of these tests require a deployed backend.
 */
@RunWith(classOf[JUnitRunner])
class ApiGwTests
    extends TestHelpers
    with WskTestHelpers
    with BeforeAndAfterAll {

    implicit val wskprops = WskProps()
    val wsk = new Wsk
    val (cliuser, clinamespace) = WskAdmin.getUser(wskprops.authKey)

    // This test suite makes enough CLI invocations in 60 seconds to trigger the OpenWhisk
    // throttling restriction.  To avoid CLI failures due to being throttled, track the
    // CLI invocation calls and when at the throttle limit, pause the next CLI invocation
    // with exactly enough time to relax the throttling.
    val throttleWindow = 1.minute
    var cliCallCount = 5  // Set to >0 to allow for other action invocations in prior tests
    var clearedThrottleTime = Instant.now
    val maxActionsPerMin = WhiskProperties.getMaxActionInvokesPerMinute()

    /**
      * Expected to be called before or after each CLI invocation
      * If number of CLI invocations in this suite have reached the throttle limit
      * then pause the test for enough time so that the throttle restriction is gone
      */
    def checkThrottle() = {
      // If the # CLI calls at the throttle limit, then wait enough time to avoid the CLI being blocked
      cliCallCount += 1
      if ( cliCallCount > maxActionsPerMin ) {
        println(s"Action invokes ${cliCallCount} exceeds per minute thottle limit of ${maxActionsPerMin}")
        val waitedAlready = Duration.fromNanos(java.time.Duration.between(clearedThrottleTime, Instant.now).toNanos)
        settleThrottle(waitedAlready)
        cliCallCount = 0
        clearedThrottleTime = Instant.now
      }
    }

    /**
     * Settles throttles of 1 minute. Waits up to 1 minute depending on the time already waited.
     *
     * @param waitedAlready the time already gone after the last invoke or fire
     */
    def settleThrottle(waitedAlready: FiniteDuration) = {
      val timeToWait = (throttleWindow - waitedAlready).max(Duration.Zero)
      println(s"Waiting for ${timeToWait.toSeconds} seconds, already waited for ${waitedAlready.toSeconds} seconds")
      Thread.sleep(timeToWait.toMillis)
    }

    /*
     * Forcibly clear the throttle so that downstream tests are not affected by
     * this test suite
     */
    override def afterAll() = {
      // If this test suite is exiting with over 30 action invocations since the last throttle clearing, clear the throttle
      if (cliCallCount > 30) {
        val waitedAlready = Duration.fromNanos(java.time.Duration.between(clearedThrottleTime, Instant.now).toNanos)
        settleThrottle(waitedAlready)
      }
    }

    def apiCreate(
      basepath: Option[String] = None,
      relpath: Option[String] = None,
      operation: Option[String] = None,
      action: Option[String] = None,
      apiname: Option[String] = None,
      swagger: Option[String] = None,
      expectedExitCode: Int = SUCCESS_EXIT): RunResult = {
        checkThrottle()
        wsk.api.create(basepath, relpath, operation, action, apiname, swagger, expectedExitCode)
    }

    def apiList(
      basepathOrApiName: Option[String] = None,
      relpath: Option[String] = None,
      operation: Option[String] = None,
      limit: Option[Int] = None,
      since: Option[Instant] = None,
      full: Option[Boolean] = None,
      expectedExitCode: Int = SUCCESS_EXIT): RunResult = {
        checkThrottle()
        wsk.api.list(basepathOrApiName, relpath, operation, limit, since, full, expectedExitCode)
    }

    def apiGet(
      basepathOrApiName: Option[String] = None,
      full: Option[Boolean] = None,
      expectedExitCode: Int = SUCCESS_EXIT): RunResult = {
        checkThrottle()
        wsk.api.get(basepathOrApiName, full, expectedExitCode)
    }

    def apiDelete(
      basepathOrApiName: String,
      relpath: Option[String] = None,
      operation: Option[String] = None,
      expectedExitCode: Int = SUCCESS_EXIT): RunResult = {
        checkThrottle()
        wsk.api.delete(basepathOrApiName, relpath, operation, expectedExitCode)
    }

    behavior of "Wsk api"

    it should "reject an api commands with an invalid path parameter" in {
        val badpath = "badpath"

        var rr = apiCreate(basepath = Some("/basepath"), relpath = Some(badpath), operation = Some("GET"), action = Some("action"), expectedExitCode = ANY_ERROR_EXIT)
        rr.stderr should include (s"'${badpath}' must begin with '/'")

        rr = apiDelete(basepathOrApiName = "/basepath", relpath = Some(badpath), operation = Some("GET"), expectedExitCode = ANY_ERROR_EXIT)
        rr.stderr should include (s"'${badpath}' must begin with '/'")

        rr = apiList(basepathOrApiName = Some("/basepath"), relpath = Some(badpath), operation = Some("GET"), expectedExitCode = ANY_ERROR_EXIT)
        rr.stderr should include (s"'${badpath}' must begin with '/'")
    }

    it should "verify full list output" in {
      val testName = "CLI_APIGWTEST_RO1"
      val testbasepath = "/" + testName + "_bp"
      val testrelpath = "/path"
      val testnewrelpath = "/path_new"
      val testurlop = "get"
      val testapiname = testName + " API Name"
      val actionName = testName + "_action"
      try {
        println("cli user: " + cliuser + "; cli namespace: " + clinamespace)

        var rr = apiCreate(basepath = Some(testbasepath), relpath = Some(testrelpath), operation = Some(testurlop), action = Some(actionName), apiname = Some(testapiname))
        println("api create: " + rr.stdout)
        rr.stdout should include("ok: created API")
        rr = apiList(basepathOrApiName = Some(testbasepath), relpath = Some(testrelpath), operation = Some(testurlop), full = Some(true))
        println("api list: " + rr.stdout)
        rr.stdout should include("ok: APIs")
        rr.stdout should include regex (s"Action:\\s+/${clinamespace}/${actionName}\n")
        rr.stdout should include regex (s"Verb:\\s+${testurlop}\n")
        rr.stdout should include regex (s"Base path:\\s+${testbasepath}\n")
        rr.stdout should include regex (s"Path:\\s+${testrelpath}\n")
        rr.stdout should include regex (s"API Name:\\s+${testapiname}\n")
        rr.stdout should include regex (s"URL:\\s+")
        rr.stdout should include(testbasepath + testrelpath)
      }
      finally {
        val deleteresult = apiDelete(basepathOrApiName = testbasepath)
      }
    }

    it should "verify successful creation and deletion of a new API" in {
        val testName = "CLI_APIGWTEST1"
        val testbasepath = "/"+testName+"_bp"
        val testrelpath = "/path"
        val testnewrelpath = "/path_new"
        val testurlop = "get"
        val testapiname = testName+" API Name"
        val actionName = testName+"_action"
        try {
            println("cli user: "+cliuser+"; cli namespace: "+clinamespace)

            var rr = apiCreate(basepath = Some(testbasepath), relpath = Some(testrelpath), operation = Some(testurlop), action = Some(actionName), apiname = Some(testapiname))
            rr.stdout should include("ok: created API")
            rr = apiList(basepathOrApiName = Some(testbasepath), relpath = Some(testrelpath), operation = Some(testurlop))
            rr.stdout should include("ok: APIs")
            rr.stdout should include regex (s"/${clinamespace}/${actionName}\\s+${testurlop}\\s+${testapiname}\\s+")
            rr.stdout should include(testbasepath + testrelpath)
            val deleteresult = apiDelete(basepathOrApiName = testbasepath)
            deleteresult.stdout should include("ok: deleted API")
        }
        finally {
            val deleteresult = apiDelete(basepathOrApiName = testbasepath, expectedExitCode = DONTCARE_EXIT)
        }
    }

    it should "verify get API name " in {
        val testName = "CLI_APIGWTEST3"
        val testbasepath = "/"+testName+"_bp"
        val testrelpath = "/path"
        val testnewrelpath = "/path_new"
        val testurlop = "get"
        val testapiname = testName+" API Name"
        val actionName = testName+"_action"
        try {
            var rr = apiCreate(basepath = Some(testbasepath), relpath = Some(testrelpath), operation = Some(testurlop), action = Some(actionName), apiname = Some(testapiname))
            rr.stdout should include("ok: created API")
            rr = apiGet(basepathOrApiName = Some(testapiname))
            rr.stdout should include(testbasepath)
            rr.stdout should include(s"${actionName}")
        }
        finally {
            val deleteresult = apiDelete(basepathOrApiName = testbasepath, expectedExitCode = DONTCARE_EXIT)
        }
    }

    it should "verify delete API name " in {
      val testName = "CLI_APIGWTEST4"
      val testbasepath = "/"+testName+"_bp"
      val testrelpath = "/path"
      val testnewrelpath = "/path_new"
      val testurlop = "get"
      val testapiname = testName+" API Name"
      val actionName = testName+"_action"
      try {
        var rr = apiCreate(basepath = Some(testbasepath), relpath = Some(testrelpath), operation = Some(testurlop), action = Some(actionName), apiname = Some(testapiname))
        rr.stdout should include("ok: created API")
        rr = apiDelete(basepathOrApiName = testapiname)
        rr.stdout should include("ok: deleted API")
      }
      finally {
        val deleteresult = apiDelete(basepathOrApiName = testbasepath, expectedExitCode = DONTCARE_EXIT)
      }
    }

    it should "verify delete API basepath " in {
      val testName = "CLI_APIGWTEST5"
      val testbasepath = "/"+testName+"_bp"
      val testrelpath = "/path"
      val testnewrelpath = "/path_new"
      val testurlop = "get"
      val testapiname = testName+" API Name"
      val actionName = testName+"_action"
      try {
        var rr = apiCreate(basepath = Some(testbasepath), relpath = Some(testrelpath), operation = Some(testurlop), action = Some(actionName), apiname = Some(testapiname))
        rr.stdout should include("ok: created API")
        rr = apiDelete(basepathOrApiName = testbasepath)
        rr.stdout should include("ok: deleted API")
      }
      finally {
        val deleteresult = apiDelete(basepathOrApiName = testbasepath, expectedExitCode = DONTCARE_EXIT)
      }
    }

    it should "verify adding endpoints to existing api" in {
      val testName = "CLI_APIGWTEST6"
      val testbasepath = "/"+testName+"_bp"
      val testrelpath = "/path2"
      val testnewrelpath = "/path_new"
      val testurlop = "get"
      val testapiname = testName+" API Name"
      val actionName = testName+"_action"
      val newEndpoint = "/newEndpoint"
      try {
        var rr = apiCreate(basepath = Some(testbasepath), relpath = Some(testrelpath), operation = Some(testurlop), action = Some(actionName), apiname = Some(testapiname))
        rr.stdout should include("ok: created API")
        rr = apiCreate(basepath = Some(testbasepath), relpath = Some(newEndpoint), operation = Some(testurlop), action = Some(actionName), apiname = Some(testapiname))
        rr.stdout should include("ok: created API")
        rr = apiList(basepathOrApiName = Some(testbasepath))
        rr.stdout should include("ok: APIs")
        rr.stdout should include regex (s"/${clinamespace}/${actionName}\\s+${testurlop}\\s+${testapiname}\\s+")
        rr.stdout should include(testbasepath + testrelpath)
        rr.stdout should include(testbasepath + newEndpoint)
      }
      finally {
        val deleteresult = apiDelete(basepathOrApiName = testbasepath, expectedExitCode = DONTCARE_EXIT)
      }
    }

    it should "verify successful creation with swagger doc as input" in {
      // NOTE: These values must match the swagger file contents
      val testName = "CLI_APIGWTEST7"
      val testbasepath = "/"+testName+"_bp"
      val testrelpath = "/path"
      val testurlop = "get"
      val testapiname = testName+" API Name"
      val actionName = testName+"_action"
      val swaggerPath = TestUtils.getTestApiGwFilename("testswaggerdoc1")
      try {
        var rr = apiCreate(swagger = Some(swaggerPath))
        rr.stdout should include("ok: created API")
        rr = apiList(basepathOrApiName = Some(testbasepath), relpath = Some(testrelpath), operation = Some(testurlop))
        println("list stdout: "+rr.stdout)
        println("list stderr: "+rr.stderr)
        rr.stdout should include("ok: APIs")
        // Actual CLI namespace will vary from local dev to automated test environments, so don't check
        rr.stdout should include regex (s"/[@\\w._\\-]+/${actionName}\\s+${testurlop}\\s+${testapiname}\\s+")
        rr.stdout should include(testbasepath + testrelpath)
      }
      finally {
        val deleteresult = apiDelete(basepathOrApiName = testbasepath, expectedExitCode = DONTCARE_EXIT)
      }
    }

    it should "verify adding endpoints to two existing apis" in {
      val testName = "CLI_APIGWTEST8"
      val testbasepath = "/"+testName+"_bp"
      val testbasepath2 = "/"+testName+"_bp2"
      val testrelpath = "/path2"
      val testnewrelpath = "/path_new"
      val testurlop = "get"
      val testapiname = testName+" API Name"
      val actionName = testName+"_action"
      val newEndpoint = "/newEndpoint"
      try {
        var rr = apiCreate(basepath = Some(testbasepath), relpath = Some(testrelpath), operation = Some(testurlop), action = Some(actionName), apiname = Some(testapiname))
        rr.stdout should include("ok: created API")
        rr = apiCreate(basepath = Some(testbasepath2), relpath = Some(testrelpath), operation = Some(testurlop), action = Some(actionName), apiname = Some(testapiname))
        rr.stdout should include("ok: created API")

        // Update both APIs - each with a new endpoint
        rr = apiCreate(basepath = Some(testbasepath), relpath = Some(newEndpoint), operation = Some(testurlop), action = Some(actionName))
        rr.stdout should include("ok: created API")
        rr = apiCreate(basepath = Some(testbasepath2), relpath = Some(newEndpoint), operation = Some(testurlop), action = Some(actionName))
        rr.stdout should include("ok: created API")

        rr = apiList(basepathOrApiName = Some(testbasepath))
        rr.stdout should include("ok: APIs")
        rr.stdout should include regex (s"/${clinamespace}/${actionName}\\s+${testurlop}\\s+${testapiname}\\s+")
        rr.stdout should include(testbasepath + testrelpath)
        rr.stdout should include(testbasepath + newEndpoint)

        rr = apiList(basepathOrApiName = Some(testbasepath2))
        rr.stdout should include("ok: APIs")
        rr.stdout should include regex (s"/${clinamespace}/${actionName}\\s+${testurlop}\\s+${testapiname}\\s+")
        rr.stdout should include(testbasepath2 + testrelpath)
        rr.stdout should include(testbasepath2 + newEndpoint)
      }
      finally {
        var deleteresult = apiDelete(basepathOrApiName = testbasepath, expectedExitCode = DONTCARE_EXIT)
        deleteresult = apiDelete(basepathOrApiName = testbasepath2, expectedExitCode = DONTCARE_EXIT)
      }
    }

    it should "verify successful creation of a new API using an action name using all allowed characters" in {
      // Be aware: full action name is close to being truncated by the 'list' command
      // e.g. /lime@us.ibm.com/CLI_APIGWTEST9a-c@t ion  is currently at the 40 char 'list' display max
      val testName = "CLI_APIGWTEST9"
      val testbasepath = "/" + testName + "_bp"
      val testrelpath = "/path"
      val testnewrelpath = "/path_new"
      val testurlop = "get"
      val testapiname = testName+" API Name"
      val actionName = testName+"a-c@t ion"
      try {
        println("cli user: "+cliuser+"; cli namespace: "+clinamespace)

        var rr = apiCreate(basepath = Some(testbasepath), relpath = Some(testrelpath), operation = Some(testurlop), action = Some(actionName), apiname = Some(testapiname))
        rr.stdout should include("ok: created API")
        rr = apiList(basepathOrApiName = Some(testbasepath), relpath = Some(testrelpath), operation = Some(testurlop))
        rr.stdout should include("ok: APIs")
        rr.stdout should include regex (s"/${clinamespace}/${actionName}\\s+${testurlop}\\s+${testapiname}\\s+")
        rr.stdout should include(testbasepath + testrelpath)
        val deleteresult = apiDelete(basepathOrApiName = testbasepath)
        deleteresult.stdout should include("ok: deleted API")
      }
      finally {
        val deleteresult = apiDelete(basepathOrApiName = testbasepath, expectedExitCode = DONTCARE_EXIT)
      }
    }

    it should "verify failed creation with invalid swagger doc as input" in {
      val testName = "CLI_APIGWTEST10"
      val testbasepath = "/" + testName + "_bp"
      val testrelpath = "/path"
      val testnewrelpath = "/path_new"
      val testurlop = "get"
      val testapiname = testName + " API Name"
      val actionName = testName + "_action"
      val swaggerPath = TestUtils.getTestApiGwFilename(s"testswaggerdocinvalid")
      try {
        var rr = apiCreate(swagger = Some(swaggerPath), expectedExitCode = ANY_ERROR_EXIT)
        println("api create stdout: " + rr.stdout)
        println("api create stderr: " + rr.stderr)
        rr.stderr should include(s"Swagger file is invalid")
      } finally {
        val deleteresult = apiDelete(basepathOrApiName = testbasepath, expectedExitCode = DONTCARE_EXIT)
      }
    }

    it should "verify delete basepath/path " in {
      val testName = "CLI_APIGWTEST11"
      val testbasepath = "/" + testName + "_bp"
      val testrelpath = "/path"
      val testnewrelpath = "/path_new"
      val testurlop = "get"
      val testapiname = testName + " API Name"
      val actionName = testName + "_action"
      try {
        var rr = apiCreate(basepath = Some(testbasepath), relpath = Some(testrelpath), operation = Some(testurlop), action = Some(actionName), apiname = Some(testapiname))
        rr.stdout should include("ok: created API")
        var rr2 = apiCreate(basepath = Some(testbasepath), relpath = Some(testnewrelpath), operation = Some(testurlop), action = Some(actionName), apiname = Some(testapiname))
        rr2.stdout should include("ok: created API")
        rr = apiDelete(basepathOrApiName = testbasepath, relpath = Some(testrelpath))
        rr.stdout should include("ok: deleted " + testrelpath +" from "+ testbasepath)
        rr2 = apiList(basepathOrApiName = Some(testbasepath), relpath = Some(testnewrelpath))
        rr2.stdout should include("ok: APIs")
        rr2.stdout should include regex (s"/${clinamespace}/${actionName}\\s+${testurlop}\\s+${testapiname}\\s+")
        rr2.stdout should include(testbasepath + testnewrelpath)
      } finally {
        val deleteresult = apiDelete(basepathOrApiName = testbasepath, expectedExitCode = DONTCARE_EXIT)
      }
    }

    it should "verify delete single operation from existing API basepath/path/operation(s) " in {
      val testName = "CLI_APIGWTEST12"
      val testbasepath = "/" + testName + "_bp"
      val testrelpath = "/path2"
      val testnewrelpath = "/path_new"
      val testurlop = "get"
      val testurlop2 = "post"
      val testapiname = testName + " API Name"
      val actionName = testName + "_action"
      try {
        var rr = apiCreate(basepath = Some(testbasepath), relpath = Some(testrelpath), operation = Some(testurlop), action = Some(actionName), apiname = Some(testapiname))
        rr.stdout should include("ok: created API")
        rr = apiCreate(basepath = Some(testbasepath), relpath = Some(testrelpath), operation = Some(testurlop2), action = Some(actionName), apiname = Some(testapiname))
        rr.stdout should include("ok: created API")
        rr = apiList(basepathOrApiName = Some(testbasepath))
        rr.stdout should include("ok: APIs")
        rr.stdout should include regex (s"/${clinamespace}/${actionName}\\s+${testurlop}\\s+${testapiname}\\s+")
        rr.stdout should include(testbasepath + testrelpath)
        rr = apiDelete(basepathOrApiName = testbasepath,relpath = Some(testrelpath), operation = Some(testurlop2))
        rr.stdout should include("ok: deleted " + testrelpath + " " + "POST" +" from "+ testbasepath)
        rr = apiList(basepathOrApiName = Some(testbasepath))
        rr.stdout should include regex (s"/${clinamespace}/${actionName}\\s+${testurlop}\\s+${testapiname}\\s+")
      } finally {
        val deleteresult = apiDelete(basepathOrApiName = testbasepath, expectedExitCode = DONTCARE_EXIT)
      }
    }

    it should "verify successful creation with complex swagger doc as input" in {
      val testName = "CLI_APIGWTEST13"
      val testbasepath = "/test1/v1"
      val testrelpath = "/whisk.system/utils/echo"
      val testrelpath2 = "/whisk.system/utils/split"
      val testurlop = "get"
      val testapiname = "/test1/v1"
      val actionName = "test1a"
      val swaggerPath = TestUtils.getTestApiGwFilename(s"testswaggerdoc2")
      try {
        var rr = apiCreate(swagger = Some(swaggerPath))
        println("api create stdout: " + rr.stdout)
        println("api create stderror: " + rr.stderr)
        rr.stdout should include("ok: created API")
        rr = apiList(basepathOrApiName = Some(testbasepath), relpath = Some(testrelpath), operation = Some(testurlop))
        rr.stdout should include("ok: APIs")
        // Actual CLI namespace will vary from local dev to automated test environments, so don't check
        rr.stdout should include regex (s"/[@\\w._\\-]+/${actionName}\\s+${testurlop}\\s+${testapiname}\\s+")
        rr.stdout should include(testbasepath + testrelpath)
        rr.stdout should include(testbasepath + testrelpath2)
      } finally {
        val deleteresult = apiDelete(basepathOrApiName = testbasepath, expectedExitCode = DONTCARE_EXIT)
      }
    }

    it should "verify successful creation and deletion with multiple base paths" in {
      val testName = "CLI_APIGWTEST14"
      val testbasepath = "/" + testName + "_bp"
      val testbasepath2 = "/" + testName + "_bp2"
      val testrelpath = "/path"
      val testnewrelpath = "/path_new"
      val testurlop = "get"
      val testapiname = testName + " API Name"
      val actionName = testName + "_action"
      try {
        var rr = apiCreate(basepath = Some(testbasepath), relpath = Some(testrelpath), operation = Some(testurlop), action = Some(actionName), apiname = Some(testapiname))
        rr.stdout should include("ok: created API")
        rr = apiList(basepathOrApiName = Some(testbasepath), relpath = Some(testrelpath), operation = Some(testurlop))
        rr.stdout should include("ok: APIs")
        rr.stdout should include regex (s"/${clinamespace}/${actionName}\\s+${testurlop}\\s+${testapiname}\\s+")
        rr.stdout should include(testbasepath + testrelpath)
        rr = apiCreate(basepath = Some(testbasepath2), relpath = Some(testrelpath), operation = Some(testurlop), action = Some(actionName), apiname = Some(testapiname))
        rr.stdout should include("ok: created API")
        rr = apiList(basepathOrApiName = Some(testbasepath2), relpath = Some(testrelpath), operation = Some(testurlop))
        rr.stdout should include("ok: APIs")
        rr.stdout should include regex (s"/${clinamespace}/${actionName}\\s+${testurlop}\\s+${testapiname}\\s+")
        rr.stdout should include(testbasepath2 + testrelpath)
        rr = apiDelete(basepathOrApiName = testbasepath2)
        rr.stdout should include("ok: deleted API")
        rr = apiList(basepathOrApiName = Some(testbasepath), relpath = Some(testrelpath), operation = Some(testurlop))
        rr.stdout should include("ok: APIs")
        rr.stdout should include regex (s"/${clinamespace}/${actionName}\\s+${testurlop}\\s+${testapiname}\\s+")
        rr.stdout should include(testbasepath + testrelpath)
        rr = apiDelete(basepathOrApiName = testbasepath)
        rr.stdout should include("ok: deleted API")
      } finally {
        var deleteresult = apiDelete(basepathOrApiName = testbasepath, expectedExitCode = DONTCARE_EXIT)
        deleteresult = apiDelete(basepathOrApiName = testbasepath2, expectedExitCode = DONTCARE_EXIT)
      }
    }
}
