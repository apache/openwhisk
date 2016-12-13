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

package apigw.healthtests

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

import spray.json.DefaultJsonProtocol._
import spray.json._

import com.jayway.restassured.RestAssured

import common.TestHelpers
import common.TestUtils
import common.TestUtils._
import common.Wsk
import common.WskAdmin
import common.WskProps
import common.WskTestHelpers
import system.rest.RestUtil

/**
 * Basic tests of the download link for Go CLI binaries
 */
@RunWith(classOf[JUnitRunner])
class ApiGwEndToEndTests extends FlatSpec with Matchers with RestUtil with TestHelpers with WskTestHelpers{

    implicit val wskprops = WskProps()
    val wsk = new Wsk
    val (cliuser, clinamespace) = WskAdmin.getUser(wskprops.authKey)

    it should s"create an API and successfully invoke that API" in {
        val testName = "APIGW_HEALTHTEST1"
        val testbasepath = "/"+testName+"_bp"
        val testrelpath = "/path"
        val testurlop = "get"
        val testapiname = testName+" API Name"
        val actionName = "echo"
        val urlqueryparam = "name"
        val urlqueryvalue = "test"


        try {
            println("cli user: "+cliuser+"; cli namespace: "+clinamespace)

            // Create the action for the API
            val file = TestUtils.getTestActionFilename(s"echo.js")
            wsk.action.create(name = actionName, artifact = Some(file), expectedExitCode = SUCCESS_EXIT)

            // Create the API
            var rr = wsk.api.create(basepath = Some(testbasepath), relpath = Some(testrelpath), operation = Some(testurlop), action = Some(actionName), apiname = Some(testapiname))
            rr.stdout should include("ok: created API")
            val apiurl = rr.stdout.split("\n")(1)
            println(s"apiurl: '${apiurl}'")

            // Validate the API was successfully created
            // List result will look like:
            // ok: APIs
            // Action                            Verb             API Name  URL
            // /_//whisk.system/utils/echo          get  APIGW_HEALTHTEST1 API Name  http://172.17.0.1:9001/api/ab9082cd-ea8e-465a-8a65-b491725cc4ef/APIGW_HEALTHTEST1_bp/path
            rr = wsk.api.list(basepathOrApiName = Some(testbasepath), relpath = Some(testrelpath), operation = Some(testurlop))
            rr.stdout should include("ok: APIs")
            rr.stdout should include regex (s"${actionName}\\s+${testurlop}\\s+${testapiname}\\s+")
            rr.stdout should include(testbasepath + testrelpath)

            // Recreate the API using a JSON swagger file
            rr = wsk.api.get(basepathOrApiName = Some(testbasepath))
            val swaggerfile = File.createTempFile("api", ".json")
            swaggerfile.deleteOnExit()
            val bw = new BufferedWriter(new FileWriter(swaggerfile))
            bw.write(rr.stdout)
            bw.close()

            // Delete API to that it can be recreated again using the generated swagger file
            val deleteApiResult = wsk.api.delete(basepathOrApiName = testbasepath, expectedExitCode = DONTCARE_EXIT)

            // Create the API again, but use the swagger file this time
            rr = wsk.api.create(swagger = Some(swaggerfile.getAbsolutePath()))
            rr.stdout should include("ok: created API")
            val swaggerapiurl = rr.stdout.split("\n")(1)
            println(s"apiurl: '${swaggerapiurl}'")

            // Call the API URL and validate the results
            val response = RestAssured.given().config(sslconfig).get(s"$apiurl?$urlqueryparam=$urlqueryvalue")
            response.statusCode should be(200)
            val responseString = response.body.asString
            println("URL invocation response: "+responseString)
            responseString.parseJson.asJsObject.fields(urlqueryparam).convertTo[String] should be(urlqueryvalue)
        }
        finally {
            println("Deleting action: "+actionName)
            val finallydeleteActionResult = wsk.action.delete(name = actionName, expectedExitCode = DONTCARE_EXIT)
            println("Deleting API: "+testbasepath)
            val finallydeleteApiResult = wsk.api.delete(basepathOrApiName = testbasepath, expectedExitCode = DONTCARE_EXIT)
        }
    }
}
