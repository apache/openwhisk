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

package whisk.core.cli.test

import akka.http.scaladsl.model.StatusCodes.OK

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import spray.json.JsObject

import common.rest.WskRest
import common.rest.RestResult
import common.TestUtils.RunResult

/**
 * Tests for testing the CLI "api" subcommand.  Most of these tests require a deployed backend.
 */
@RunWith(classOf[JUnitRunner])
class ApiGwRestTests extends ApiGwTests {
  override lazy val wsk = new WskRest
  override lazy val createCode = OK.intValue

  override def verifyBadCommands(rr: RunResult, badpath: String): Unit = {
    val apiResultRest = rr.asInstanceOf[RestResult]
    val error = RestResult.getField(apiResultRest.respBody, "error")
    error should include("Error: Resource path must begin with '/'.")
  }

  override def verifyBadCommandsDelete(rr: RunResult, badpath: String): Unit = {
    val apiResultRest = rr.asInstanceOf[RestResult]
    val error = RestResult.getField(apiResultRest.respBody, "error")
    error should include(s"API deletion failure: API '/basepath' does not exist")
  }

  override def verifyBadCommandsList(rr: RunResult, badpath: String): Unit = {
    val apiResultRest = rr.asInstanceOf[RestResult]
    val apis = apiResultRest.getFieldListJsObject("apis")
    apis.size shouldBe 0
  }

  override def verifyInvalidCommands(rr: RunResult, badverb: String): Unit = {
    val apiResultRest = rr.asInstanceOf[RestResult]
    val error = apiResultRest.getField("error")
    error should include(s"Error: Resource verb '${badverb}' not supported")
  }

  override def verifyInvalidCommandsDelete(rr: RunResult, badverb: String): Unit = {
    verifyBadCommandsDelete(rr, badverb)
  }

  override def verifyInvalidCommandsList(rr: RunResult, badverb: String): Unit = {
    verifyBadCommandsList(rr, badverb)
  }

  override def verifyNonJsonSwagger(rr: RunResult, filename: String): Unit = {
    val apiResultRest = rr.asInstanceOf[RestResult]
    val error = apiResultRest.getField("error")
    error should include(s"swagger field cannot be parsed. Ensure it is valid JSON")
  }

  override def verifyMissingField(rr: RunResult): Unit = {
    val apiResultRest = rr.asInstanceOf[RestResult]
    val error = apiResultRest.getField("error")
    error should include(s"swagger is missing the basePath field.")
  }

  override def verifyApiCreated(rr: RunResult): Unit = {
    val apiResultRest = rr.asInstanceOf[RestResult]
    apiResultRest.statusCode shouldBe OK
  }

  def verifyList(rr: RunResult,
                 namespace: String,
                 actionName: String,
                 testurlop: String,
                 testbasepath: String,
                 testrelpath: String,
                 testapiname: String,
                 newEndpoint: String = ""): Unit = {
    val apiResultRest = rr.asInstanceOf[RestResult]
    val apiValue = RestResult.getFieldJsObject(apiResultRest.getFieldListJsObject("apis")(0), "value")
    val apidoc = RestResult.getFieldJsObject(apiValue, "apidoc")
    val basepath = RestResult.getField(apidoc, "basePath")
    basepath shouldBe testbasepath

    val paths = RestResult.getFieldJsObject(apidoc, "paths")
    paths.fields.contains(testrelpath) shouldBe true

    val info = RestResult.getFieldJsObject(apidoc, "info")
    val title = RestResult.getField(info, "title")
    title shouldBe testapiname

    verifyPaths(paths, testrelpath, testurlop, actionName, namespace)

    if (newEndpoint != "") {
      verifyPaths(paths, newEndpoint, testurlop, actionName, namespace)
    }
  }

  def verifyPaths(paths: JsObject,
                  testrelpath: String,
                  testurlop: String,
                  actionName: String,
                  namespace: String = "") = {
    val relpath = RestResult.getFieldJsObject(paths, testrelpath)
    val urlop = RestResult.getFieldJsObject(relpath, testurlop)
    val openwhisk = RestResult.getFieldJsObject(urlop, "x-openwhisk")
    val actionN = RestResult.getField(openwhisk, "action")
    actionN shouldBe actionName

    if (namespace != "") {
      val namespaceS = RestResult.getField(openwhisk, "namespace")
      namespaceS shouldBe namespace
    }
  }

  override def verifyApiList(rr: RunResult,
                             clinamespace: String,
                             actionName: String,
                             testurlop: String,
                             testbasepath: String,
                             testrelpath: String,
                             testapiname: String): Unit = {
    verifyList(rr, clinamespace, actionName, testurlop, testbasepath, testrelpath, testapiname)
  }

  override def verifyApiGet(rr: RunResult): Unit = {
    rr.stdout should include regex (s""""operationId":"getPathWithSub_pathsInIt"""")
  }

  override def verifyApiFullList(rr: RunResult,
                                 clinamespace: String,
                                 actionName: String,
                                 testurlop: String,
                                 testbasepath: String,
                                 testrelpath: String,
                                 testapiname: String): Unit = {
    verifyList(rr, clinamespace, actionName, testurlop, testbasepath, testrelpath, testapiname)
  }

  override def verifyApiFullListDouble(rr: RunResult,
                                       clinamespace: String,
                                       actionName: String,
                                       testurlop: String,
                                       testbasepath: String,
                                       testrelpath: String,
                                       testapiname: String,
                                       newEndpoint: String): Unit = {
    verifyList(rr, clinamespace, actionName, testurlop, testbasepath, testrelpath, testapiname, newEndpoint)
  }

  override def verifyApiDeleted(rr: RunResult): Unit = {
    val apiResultRest = rr.asInstanceOf[RestResult]
    apiResultRest.statusCode shouldBe OK
  }

  override def verifyApiDeletedRelpath(rr: RunResult,
                                       testrelpath: String,
                                       testbasepath: String,
                                       op: String = ""): Unit = {
    verifyApiDeleted(rr)
  }

  override def verifyApiNameGet(rr: RunResult,
                                testbasepath: String,
                                actionName: String,
                                responseType: String = "json"): Unit = {
    val apiResultRest = rr.asInstanceOf[RestResult]

    val apiValue = RestResult.getFieldJsObject(apiResultRest.getFieldListJsObject("apis")(0), "value")
    val apidoc = RestResult.getFieldJsObject(apiValue, "apidoc")

    val config = RestResult.getFieldJsObject(apidoc, "x-ibm-configuration")

    val cors = RestResult.getFieldJsObject(config, "cors")
    val enabled = RestResult.getFieldJsValue(cors, "enabled").toString()
    enabled shouldBe "true"

    val basepath = RestResult.getField(apidoc, "basePath")
    basepath shouldBe testbasepath

    val paths = RestResult.getFieldJsObject(apidoc, "paths")
    val relpath = RestResult.getFieldJsObject(paths, "/path")
    val urlop = RestResult.getFieldJsObject(relpath, "get")
    val openwhisk = RestResult.getFieldJsObject(urlop, "x-openwhisk")
    val actionN = RestResult.getField(openwhisk, "action")
    actionN shouldBe actionName
    rr.stdout should include regex (s""""target-url":".*${actionName}.${responseType}"""")
  }

  override def verifyInvalidSwagger(rr: RunResult): Unit = {
    verifyMissingField(rr)
  }

  override def verifyApiOp(rr: RunResult, testurlop: String, testapiname: String): Unit = {
    val apiResultRest = rr.asInstanceOf[RestResult]
    val apiValue = RestResult.getFieldJsObject(apiResultRest.getFieldListJsObject("apis")(0), "value")
    val apidoc = RestResult.getFieldJsObject(apiValue, "apidoc")
    val info = RestResult.getFieldJsObject(apidoc, "info")
    val title = RestResult.getField(info, "title")
    title shouldBe testapiname
    val paths = RestResult.getFieldJsObject(apidoc, "paths")
    val relpath = RestResult.getFieldJsObject(paths, "/")
    val urlop = RestResult.getFieldJsObject(relpath, testurlop)
    relpath.fields.contains(testurlop) shouldBe true
  }

  override def verifyApiBaseRelPath(rr: RunResult, testbasepath: String, testrelpath: String): Unit = {
    val apiResultRest = rr.asInstanceOf[RestResult]
    val apiValue = RestResult.getFieldJsObject(apiResultRest.getFieldListJsObject("apis")(0), "value")
    val apidoc = RestResult.getFieldJsObject(apiValue, "apidoc")
    val basepath = RestResult.getField(apidoc, "basePath")
    basepath shouldBe testbasepath

    val paths = RestResult.getFieldJsObject(apidoc, "paths")
    paths.fields.contains(testrelpath) shouldBe true
  }

  override def verifyApiOpVerb(rr: RunResult, testurlop: String): Unit = {
    val apiResultRest = rr.asInstanceOf[RestResult]
    val apiValue = RestResult.getFieldJsObject(apiResultRest.getFieldListJsObject("apis")(0), "value")
    val apidoc = RestResult.getFieldJsObject(apiValue, "apidoc")
    val paths = RestResult.getFieldJsObject(apidoc, "paths")
    val relpath = RestResult.getFieldJsObject(paths, "/")
    val urlop = RestResult.getFieldJsObject(relpath, testurlop)
    relpath.fields.contains(testurlop) shouldBe true
  }

  override def verifyInvalidKey(rr: RunResult): Unit = {
    rr.stderr should include("A valid auth key is required")
  }

}
