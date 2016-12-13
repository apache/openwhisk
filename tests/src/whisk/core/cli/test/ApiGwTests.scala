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

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import common.TestHelpers
import common.TestUtils._
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
    with WskTestHelpers {

    implicit val wskprops = WskProps()
    val wsk = new Wsk
    val (cliuser, clinamespace) = WskAdmin.getUser(wskprops.authKey)

    behavior of "Wsk api"

    it should "reject an api commands with an invalid path parameter" in {
        val badpath = "badpath"

        var rr = wsk.api.create(basepath = Some("/basepath"), relpath = Some(badpath), operation = Some("GET"), action = Some("action"), expectedExitCode = ANY_ERROR_EXIT)
        rr.stderr should include (s"'${badpath}' must begin with '/'")

        rr = wsk.api.delete(basepathOrApiName = "/basepath", relpath = Some(badpath), operation = Some("GET"), expectedExitCode = ANY_ERROR_EXIT)
        rr.stderr should include (s"'${badpath}' must begin with '/'")

        rr = wsk.api.list(basepathOrApiName = Some("/basepath"), relpath = Some(badpath), operation = Some("GET"), expectedExitCode = ANY_ERROR_EXIT)
        rr.stderr should include (s"'${badpath}' must begin with '/'")
    }

    it should "verify successful creation of a new API" in {
        val testName = "CLI_APIGWTEST1"
        val testbasepath = "/"+testName+"_bp"
        val testrelpath = "/path"
        val testnewrelpath = "/path_new"
        val testurlop = "get"
        val testapiname = testName+" API Name"
        val actionName = testName+"_action"

        // List result will look like:
        // ok: APIs
        // Action                            Verb             API Name  URL
        // /_/CLI_APIGWTEST1_action          get  CLI_APIGWTEST1 API Name  http://172.17.0.1:9001/api/ab9082cd-ea8e-465a-8a65-b491725cc4ef/CLI_APIGWTEST1_bp/path
        try {
            println("cli user: "+cliuser+"; cli namespace: "+clinamespace)

            var rr = wsk.api.create(basepath = Some(testbasepath), relpath = Some(testrelpath), operation = Some(testurlop), action = Some(actionName), apiname = Some(testapiname))
            rr.stdout should include("ok: created API")
            rr = wsk.api.list(basepathOrApiName = Some(testbasepath), relpath = Some(testrelpath), operation = Some(testurlop))
            rr.stdout should include("ok: APIs")
            rr.stdout should include regex (s"/${clinamespace}/${actionName}\\s+${testurlop}\\s+${testapiname}\\s+")
            rr.stdout should include(testbasepath + testrelpath)
        }
        finally {
            val deleteresult = wsk.api.delete(basepathOrApiName = testbasepath, expectedExitCode = DONTCARE_EXIT)
        }
    }
}
