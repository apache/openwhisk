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

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import common.JsHelpers
import common.StreamLogging
import common.TestUtils
import common.TestUtils.ANY_ERROR_EXIT
import common.TestUtils.DONTCARE_EXIT
import common.TestUtils.SUCCESS_EXIT
import common.Wsk
import common.WskActorSystem
import common.WskAdmin
import common.WskProps

/**
 * Tests for basic CLI usage. Some of these tests require a deployed backend.
 */
@RunWith(classOf[JUnitRunner])
class WskApiGwTests extends BaseApiGwTests with WskActorSystem with JsHelpers with StreamLogging {

  val systemId: String = "whisk.system"
  override implicit val wskprops = WskProps(authKey = WskAdmin.listKeys(systemId)(0)._1, namespace = systemId)
  val wsk: common.Wsk = new Wsk

  it should "reject apimgmt actions that are invoked with not enough parameters" in {
    val invalidArgs = Seq(
      //getApi
      ("/whisk.system/apimgmt/getApi", ANY_ERROR_EXIT, "Invalid authentication.", Seq()),
      //deleteApi
      (
        "/whisk.system/apimgmt/deleteApi",
        ANY_ERROR_EXIT,
        "Invalid authentication.",
        Seq("-p", "basepath", "/ApiGwRoutemgmtActionTests_bp")),
      (
        "/whisk.system/apimgmt/deleteApi",
        ANY_ERROR_EXIT,
        "basepath is required",
        Seq("-p", "__ow_user", "_", "-p", "accesstoken", "TOKEN")),
      (
        "/whisk.system/apimgmt/deleteApi",
        ANY_ERROR_EXIT,
        "When specifying an operation, the path is required",
        Seq(
          "-p",
          "__ow_user",
          "_",
          "-p",
          "accesstoken",
          "TOKEN",
          "-p",
          "basepath",
          "/ApiGwRoutemgmtActionTests_bp",
          "-p",
          "operation",
          "get")),
      //createApi
      (
        "/whisk.system/apimgmt/createApi",
        ANY_ERROR_EXIT,
        "apidoc is required",
        Seq("-p", "__ow_user", "_", "-p", "accesstoken", "TOKEN")),
      (
        "/whisk.system/apimgmt/createApi",
        ANY_ERROR_EXIT,
        "apidoc is missing the namespace field",
        Seq("-p", "__ow_user", "_", "-p", "accesstoken", "TOKEN", "-p", "apidoc", "{}")),
      (
        "/whisk.system/apimgmt/createApi",
        ANY_ERROR_EXIT,
        "apidoc is missing the gatewayBasePath field",
        Seq("-p", "__ow_user", "_", "-p", "accesstoken", "TOKEN", "-p", "apidoc", """{"namespace":"_"}""")),
      (
        "/whisk.system/apimgmt/createApi",
        ANY_ERROR_EXIT,
        "apidoc is missing the gatewayPath field",
        Seq(
          "-p",
          "__ow_user",
          "_",
          "-p",
          "accesstoken",
          "TOKEN",
          "-p",
          "apidoc",
          """{"namespace":"_","gatewayBasePath":"/ApiGwRoutemgmtActionTests_bp"}""")),
      (
        "/whisk.system/apimgmt/createApi",
        ANY_ERROR_EXIT,
        "apidoc is missing the gatewayMethod field",
        Seq(
          "-p",
          "__ow_user",
          "_",
          "-p",
          "accesstoken",
          "TOKEN",
          "-p",
          "apidoc",
          """{"namespace":"_","gatewayBasePath":"/ApiGwRoutemgmtActionTests_bp","gatewayPath":"ApiGwRoutemgmtActionTests_rp"}""")),
      (
        "/whisk.system/apimgmt/createApi",
        ANY_ERROR_EXIT,
        "apidoc is missing the action field",
        Seq(
          "-p",
          "__ow_user",
          "_",
          "-p",
          "accesstoken",
          "TOKEN",
          "-p",
          "apidoc",
          """{"namespace":"_","gatewayBasePath":"/ApiGwRoutemgmtActionTests_bp","gatewayPath":"ApiGwRoutemgmtActionTests_rp","gatewayMethod":"get"}""")),
      (
        "/whisk.system/apimgmt/createApi",
        ANY_ERROR_EXIT,
        "action is missing the backendMethod field",
        Seq(
          "-p",
          "__ow_user",
          "_",
          "-p",
          "accesstoken",
          "TOKEN",
          "-p",
          "apidoc",
          """{"namespace":"_","gatewayBasePath":"/ApiGwRoutemgmtActionTests_bp","gatewayPath":"ApiGwRoutemgmtActionTests_rp","gatewayMethod":"get","action":{}}""")),
      (
        "/whisk.system/apimgmt/createApi",
        ANY_ERROR_EXIT,
        "action is missing the backendUrl field",
        Seq(
          "-p",
          "__ow_user",
          "_",
          "-p",
          "accesstoken",
          "TOKEN",
          "-p",
          "apidoc",
          """{"namespace":"_","gatewayBasePath":"/ApiGwRoutemgmtActionTests_bp","gatewayPath":"ApiGwRoutemgmtActionTests_rp","gatewayMethod":"get","action":{"backendMethod":"post"}}""")),
      (
        "/whisk.system/apimgmt/createApi",
        ANY_ERROR_EXIT,
        "action is missing the namespace field",
        Seq(
          "-p",
          "__ow_user",
          "_",
          "-p",
          "accesstoken",
          "TOKEN",
          "-p",
          "apidoc",
          """{"namespace":"_","gatewayBasePath":"/ApiGwRoutemgmtActionTests_bp","gatewayPath":"ApiGwRoutemgmtActionTests_rp","gatewayMethod":"get","action":{"backendMethod":"post","backendUrl":"URL"}}""")),
      (
        "/whisk.system/apimgmt/createApi",
        ANY_ERROR_EXIT,
        "action is missing the name field",
        Seq(
          "-p",
          "__ow_user",
          "_",
          "-p",
          "accesstoken",
          "TOKEN",
          "-p",
          "apidoc",
          """{"namespace":"_","gatewayBasePath":"/ApiGwRoutemgmtActionTests_bp","gatewayPath":"ApiGwRoutemgmtActionTests_rp","gatewayMethod":"get","action":{"backendMethod":"post","backendUrl":"URL","namespace":"_"}}""")),
      (
        "/whisk.system/apimgmt/createApi",
        ANY_ERROR_EXIT,
        "action is missing the authkey field",
        Seq(
          "-p",
          "__ow_user",
          "_",
          "-p",
          "accesstoken",
          "TOKEN",
          "-p",
          "apidoc",
          """{"namespace":"_","gatewayBasePath":"/ApiGwRoutemgmtActionTests_bp","gatewayPath":"ApiGwRoutemgmtActionTests_rp","gatewayMethod":"get","action":{"backendMethod":"post","backendUrl":"URL","namespace":"_","name":"N"}}""")),
      (
        "/whisk.system/apimgmt/createApi",
        ANY_ERROR_EXIT,
        "swagger and gatewayBasePath are mutually exclusive and cannot be specified together",
        Seq(
          "-p",
          "__ow_user",
          "_",
          "-p",
          "accesstoken",
          "TOKEN",
          "-p",
          "apidoc",
          """{"namespace":"_","gatewayBasePath":"/ApiGwRoutemgmtActionTests_bp","gatewayPath":"ApiGwRoutemgmtActionTests_rp","gatewayMethod":"get","action":{"backendMethod":"post","backendUrl":"URL","namespace":"_","name":"N","authkey":"XXXX"},"swagger":{}}""")),
      (
        "/whisk.system/apimgmt/createApi",
        ANY_ERROR_EXIT,
        "apidoc field cannot be parsed. Ensure it is valid JSON",
        Seq("-p", "__ow_user", "_", "-p", "accesstoken", "TOKEN", "-p", "apidoc", "{1:[}}}")))

    invalidArgs foreach {
      case (action: String, exitcode: Int, errmsg: String, params: Seq[String]) =>
        val cmd: Seq[String] = Seq(
          "action",
          "invoke",
          action,
          "-i",
          "-b",
          "-r",
          "--apihost",
          wskprops.apihost,
          "--auth",
          wskprops.authKey) ++ params
        val rr = wsk.cli(cmd, expectedExitCode = exitcode)
        rr.stderr should include regex (errmsg)
    }
  }

  it should "reject an API created with a non-existent action" in {
    val testName = "CLI_APIGWTEST15"
    val testbasepath = "/" + testName + "_bp"
    val testrelpath = "/path"
    val testnewrelpath = "/path_new"
    val testurlop = "get"
    val testapiname = testName + " API Name"
    val actionName = testName + "_action"
    try {
      val rr = apiCreate(
        basepath = Some(testbasepath),
        relpath = Some(testrelpath),
        operation = Some(testurlop),
        action = Some(actionName),
        apiname = Some(testapiname),
        expectedExitCode = ANY_ERROR_EXIT)
      rr.stderr should include("does not exist")
    } finally {
      apiDelete(basepathOrApiName = testbasepath, expectedExitCode = DONTCARE_EXIT)
    }
  }

  it should "reject an API created with an action that is not a web action" in {
    val testName = "CLI_APIGWTEST16"
    val testbasepath = "/" + testName + "_bp"
    val testrelpath = "/path"
    val testnewrelpath = "/path_new"
    val testurlop = "get"
    val testapiname = testName + " API Name"
    val actionName = testName + "_action"
    try {
      // Create the action for the API.  It must NOT be a "web-action" action for this test
      val file = TestUtils.getTestActionFilename(s"echo.js")
      wsk.action.create(name = actionName, artifact = Some(file), expectedExitCode = SUCCESS_EXIT)

      val rr = apiCreate(
        basepath = Some(testbasepath),
        relpath = Some(testrelpath),
        operation = Some(testurlop),
        action = Some(actionName),
        apiname = Some(testapiname),
        expectedExitCode = ANY_ERROR_EXIT)
      rr.stderr should include("is not a web action")
    } finally {
      wsk.action.delete(name = actionName, expectedExitCode = DONTCARE_EXIT)
      apiDelete(basepathOrApiName = testbasepath, expectedExitCode = DONTCARE_EXIT)
    }
  }

  it should "reject API export when export type is invalid" in {
    val testName = "CLI_APIGWTEST18"
    val testbasepath = "/" + testName + "_bp"

    val rr = apiGet(basepathOrApiName = Some(testbasepath), format = Some("BadType"), expectedExitCode = ANY_ERROR_EXIT)
    rr.stderr should include("Invalid format type")
  }

  it should "list api alphabetically by Base/Rel/Verb" in {
    val baseName = "/BaseTestPathApiList"
    val actionName = "actionName"
    val file = TestUtils.getTestActionFilename(s"echo-web-http.js")
    try {
      // Create Action for apis
      var action =
        wsk.action.create(name = actionName, artifact = Some(file), expectedExitCode = SUCCESS_EXIT, web = Some("true"))
      println("action creation: " + action.stdout)
      // Create apis
      for (i <- 1 to 3) {
        val base = s"$baseName$i"
        var api = apiCreate(
          basepath = Some(base),
          relpath = Some("/relPath"),
          operation = Some("GET"),
          action = Some(actionName))
        println("api creation: " + api.stdout)
      }
      val original = apiList(nameSort = Some(true))
      val originalFull = apiList(full = Some(true), nameSort = Some(true))
      val scalaSorted = List(s"${baseName}1" + "/", s"${baseName}2" + "/", s"${baseName}3" + "/")

      val regex = s"${baseName}[1-3]/".r
      val list = (regex.findAllMatchIn(original.stdout)).toList
      val listFull = (regex.findAllMatchIn(originalFull.stdout)).toList

      scalaSorted.toString shouldEqual list.toString
      scalaSorted.toString shouldEqual listFull.toString

    } finally {
      // Clean up Apis
      for (i <- 1 to 3) {
        apiDelete(basepathOrApiName = s"${baseName}$i", expectedExitCode = DONTCARE_EXIT)
      }
      wsk.action.delete(name = actionName, expectedExitCode = DONTCARE_EXIT)
    }
  }

  it should "successfully export an API in YAML format" in {
    val testName = "CLI_APIGWTEST19"
    val testbasepath = "/" + testName + "_bp"
    val testrelpath = "/path"
    val testnewrelpath = "/path_new"
    val testurlop = "get"
    val testapiname = testName + " API Name"
    val actionName = testName + "_action"
    val responseType = "http"
    try {
      // Create the action for the API.  It must be a "web-action" action.
      val file = TestUtils.getTestActionFilename(s"echo.js")
      wsk.action.create(name = actionName, artifact = Some(file), expectedExitCode = SUCCESS_EXIT, web = Some("true"))

      var rr = apiCreate(
        basepath = Some(testbasepath),
        relpath = Some(testrelpath),
        operation = Some(testurlop),
        action = Some(actionName),
        apiname = Some(testapiname),
        responsetype = Some(responseType))
      rr.stdout should include("ok: created API")

      rr = apiGet(basepathOrApiName = Some(testapiname), format = Some("yaml"))
      rr.stdout should include(s"basePath: ${testbasepath}")
    } finally {
      wsk.action.delete(name = actionName, expectedExitCode = DONTCARE_EXIT)
      apiDelete(basepathOrApiName = testbasepath, expectedExitCode = DONTCARE_EXIT)
    }
  }

  it should "successfully export an API when JSON format is explcitly specified" in {
    val testName = "CLI_APIGWTEST20"
    val testbasepath = "/" + testName + "_bp"
    val testrelpath = "/path"
    val testnewrelpath = "/path_new"
    val testurlop = "get"
    val testapiname = testName + " API Name"
    val actionName = testName + "_action"
    val responseType = "http"
    try {
      // Create the action for the API.  It must be a "web-action" action.
      val file = TestUtils.getTestActionFilename(s"echo.js")
      wsk.action.create(name = actionName, artifact = Some(file), expectedExitCode = SUCCESS_EXIT, web = Some("true"))

      var rr = apiCreate(
        basepath = Some(testbasepath),
        relpath = Some(testrelpath),
        operation = Some(testurlop),
        action = Some(actionName),
        apiname = Some(testapiname),
        responsetype = Some(responseType))
      rr.stdout should include("ok: created API")

      rr = apiGet(basepathOrApiName = Some(testapiname), format = Some("json"))
      rr.stdout should include(testbasepath)
      rr.stdout should include(s"${actionName}")
      rr.stdout should include regex (""""cors":\s*\{\s*\n\s*"enabled":\s*true""")
      rr.stdout should include regex (s""""target-url":\\s+.*${actionName}.${responseType}""")
    } finally {
      wsk.action.delete(name = actionName, expectedExitCode = DONTCARE_EXIT)
      apiDelete(basepathOrApiName = testbasepath, expectedExitCode = DONTCARE_EXIT)
    }
  }

  it should "successfully create an API from a YAML formatted API configuration file" in {
    val testName = "CLI_APIGWTEST21"
    val testbasepath = "/bp"
    val testrelpath = "/rp"
    val testurlop = "get"
    val testapiname = testbasepath
    val actionName = "webhttpecho"
    val swaggerPath = TestUtils.getTestApiGwFilename(s"local.api.yaml")
    try {
      var rr = apiCreate(swagger = Some(swaggerPath))
      println("api create stdout: " + rr.stdout)
      println("api create stderror: " + rr.stderr)
      rr.stdout should include("ok: created API")
      rr = apiList(basepathOrApiName = Some(testbasepath))
      rr.stdout should include("ok: APIs")
      rr.stdout should include regex (s"/[@\\w._\\-]+/${actionName}\\s+${testurlop}\\s+${testapiname}\\s+")
      rr.stdout should include(testbasepath + testrelpath)
    } finally {
      apiDelete(basepathOrApiName = testbasepath, expectedExitCode = DONTCARE_EXIT)
    }
  }

  it should "reject creation of an API from invalid YAML formatted API configuration file" in {
    val testName = "CLI_APIGWTEST22"
    val testbasepath = "/" + testName + "_bp"
    val swaggerPath = TestUtils.getTestApiGwFilename(s"local.api.bad.yaml")
    try {
      val rr = apiCreate(swagger = Some(swaggerPath), expectedExitCode = ANY_ERROR_EXIT)
      println("api create stdout: " + rr.stdout)
      println("api create stderror: " + rr.stderr)
      rr.stderr should include("Unable to parse YAML configuration file")
    } finally {
      apiDelete(basepathOrApiName = testbasepath, expectedExitCode = DONTCARE_EXIT)
    }
  }
}
