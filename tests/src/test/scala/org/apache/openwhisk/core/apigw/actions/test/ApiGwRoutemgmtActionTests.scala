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

package org.apache.openwhisk.core.apigw.actions.test

import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfterAll
import org.scalatest.junit.JUnitRunner

import common.JsHelpers
import common.StreamLogging
import common.TestHelpers
import common.TestUtils.DONTCARE_EXIT
import common.TestUtils.RunResult
import common.TestUtils.SUCCESS_EXIT
import common.WskOperations
import common.WskActorSystem
import common.WskAdmin
import common.WskProps
import common.rest.ApiAction
import common.WskTestHelpers
import spray.json._
import spray.json.DefaultJsonProtocol._

/**
 * Tests for basic CLI usage. Some of these tests require a deployed backend.
 */
@RunWith(classOf[JUnitRunner])
abstract class ApiGwRoutemgmtActionTests
    extends TestHelpers
    with BeforeAndAfterAll
    with WskActorSystem
    with WskTestHelpers
    with JsHelpers
    with StreamLogging {

  val systemId = "whisk.system"
  implicit val wskprops = WskProps(authKey = WskAdmin.listKeys(systemId)(0)._1, namespace = systemId)
  val wsk: WskOperations

  def getApis(bpOrName: Option[String],
              relpath: Option[String] = None,
              operation: Option[String] = None,
              docid: Option[String] = None,
              accesstoken: Option[String] = Some("AnAccessToken"),
              spaceguid: Option[String] = Some("ASpaceGuid")): Vector[JsValue] = {
    val parms = Map[String, JsValue]() ++
      Map("__ow_user" -> wskprops.namespace.toJson) ++ {
      bpOrName map { b =>
        Map("basepath" -> b.toJson)
      } getOrElse Map[String, JsValue]()
    } ++ {
      relpath map { r =>
        Map("relpath" -> r.toJson)
      } getOrElse Map[String, JsValue]()
    } ++ {
      operation map { o =>
        Map("operation" -> o.toJson)
      } getOrElse Map[String, JsValue]()
    } ++ {
      docid map { d =>
        Map("docid" -> d.toJson)
      } getOrElse Map[String, JsValue]()
    } ++ {
      accesstoken map { t =>
        Map("accesstoken" -> t.toJson)
      } getOrElse Map[String, JsValue]()
    } ++ {
      spaceguid map { s =>
        Map("spaceguid" -> s.toJson)
      } getOrElse Map[String, JsValue]()
    }

    val rr = wsk.action.invoke(
      name = "apimgmt/getApi",
      parameters = parms,
      blocking = true,
      result = true,
      expectedExitCode = SUCCESS_EXIT)(wskprops)
    var apiJsArray: JsArray =
      try {
        var apisobj = rr.stdout.parseJson.asJsObject.fields("apis")
        apisobj.convertTo[JsArray]
      } catch {
        case e: Exception =>
          JsArray.empty
      }
    apiJsArray.elements
  }

  def createApi(namespace: Option[String] = Some("_"),
                basepath: Option[String] = Some("/"),
                relpath: Option[String],
                operation: Option[String],
                apiname: Option[String],
                action: Option[ApiAction],
                swagger: Option[String] = None,
                accesstoken: Option[String] = Some("AnAccessToken"),
                spaceguid: Option[String] = Some("ASpaceGuid"),
                expectedExitCode: Int = SUCCESS_EXIT): RunResult = {
    val parms = Map[String, JsValue]() ++ {
      namespace map { n =>
        Map("namespace" -> n.toJson)
      } getOrElse Map[String, JsValue]()
    } ++ {
      basepath map { b =>
        Map("gatewayBasePath" -> b.toJson)
      } getOrElse Map[String, JsValue]()
    } ++ {
      relpath map { r =>
        Map("gatewayPath" -> r.toJson)
      } getOrElse Map[String, JsValue]()
    } ++ {
      operation map { o =>
        Map("gatewayMethod" -> o.toJson)
      } getOrElse Map[String, JsValue]()
    } ++ {
      apiname map { an =>
        Map("apiName" -> an.toJson)
      } getOrElse Map[String, JsValue]()
    } ++ {
      action map { a =>
        Map("action" -> a.toJson)
      } getOrElse Map[String, JsValue]()
    } ++ {
      swagger map { s =>
        Map("swagger" -> s.toJson)
      } getOrElse Map[String, JsValue]()
    }
    val parm = Map[String, JsValue]("apidoc" -> JsObject(parms)) ++ {
      namespace map { n =>
        Map("__ow_user" -> n.toJson)
      } getOrElse Map[String, JsValue]()
    } ++ {
      accesstoken map { t =>
        Map("accesstoken" -> t.toJson)
      } getOrElse Map[String, JsValue]()
    } ++ {
      spaceguid map { s =>
        Map("spaceguid" -> s.toJson)
      } getOrElse Map[String, JsValue]()
    }

    wsk.action.invoke(
      name = "apimgmt/createApi",
      parameters = parm,
      blocking = true,
      result = true,
      expectedExitCode = expectedExitCode)(wskprops)
  }

  def deleteApi(namespace: Option[String] = Some("_"),
                basepath: Option[String] = Some("/"),
                relpath: Option[String] = None,
                operation: Option[String] = None,
                apiname: Option[String] = None,
                accesstoken: Option[String] = Some("AnAccessToken"),
                spaceguid: Option[String] = Some("ASpaceGuid"),
                expectedExitCode: Int = SUCCESS_EXIT): RunResult = {
    val parms = Map[String, JsValue]() ++ {
      namespace map { n =>
        Map("__ow_user" -> n.toJson)
      } getOrElse Map[String, JsValue]()
    } ++ {
      basepath map { b =>
        Map("basepath" -> b.toJson)
      } getOrElse Map[String, JsValue]()
    } ++ {
      relpath map { r =>
        Map("relpath" -> r.toJson)
      } getOrElse Map[String, JsValue]()
    } ++ {
      operation map { o =>
        Map("operation" -> o.toJson)
      } getOrElse Map[String, JsValue]()
    } ++ {
      apiname map { an =>
        Map("apiname" -> an.toJson)
      } getOrElse Map[String, JsValue]()
    } ++ {
      accesstoken map { t =>
        Map("accesstoken" -> t.toJson)
      } getOrElse Map[String, JsValue]()
    } ++ {
      spaceguid map { s =>
        Map("spaceguid" -> s.toJson)
      } getOrElse Map[String, JsValue]()
    }

    wsk.action.invoke(
      name = "apimgmt/deleteApi",
      parameters = parms,
      blocking = true,
      result = true,
      expectedExitCode = expectedExitCode)(wskprops)
  }

  def apiMatch(apiarr: Vector[JsValue],
               basepath: String = "/",
               relpath: String = "",
               operation: String = "",
               apiname: String = "",
               action: ApiAction = null): Boolean = {
    var matches: Boolean = false
    for (api <- apiarr) {
      val basepathExists = JsObjectHelper(api.asJsObject).fieldPathExists("value", "apidoc", "basePath")
      if (basepathExists) {
        System.out.println("basePath exists")
        val basepathMatches = (JsObjectHelper(api.asJsObject)
          .getFieldPath("value", "apidoc", "basePath")
          .get
          .convertTo[String] == basepath)
        if (basepathMatches) {
          System.out.println("basePath matches: " + basepath)
          val apinameExists = JsObjectHelper(api.asJsObject).fieldPathExists("value", "apidoc", "info", "title")
          if (apinameExists) {
            System.out.println("api name exists")
            val apinameMatches = (JsObjectHelper(api.asJsObject)
              .getFieldPath("value", "apidoc", "info", "title")
              .get
              .convertTo[String] == apiname)
            if (apinameMatches) {
              System.out.println("api name matches: " + apiname)
              val endpointMatches =
                JsObjectHelper(api.asJsObject).fieldPathExists("value", "apidoc", "paths", relpath, operation)
              if (endpointMatches) {
                System.out.println("endpoint exists/matches : " + relpath + "  " + operation)
                val actionConfig = JsObjectHelper(api.asJsObject)
                  .getFieldPath("value", "apidoc", "paths", relpath, operation, "x-openwhisk")
                  .get
                  .asJsObject
                val actionMatches = actionMatch(actionConfig, action)
                if (actionMatches) {
                  System.out.println("endpoint action matches")
                  matches = true;
                }
              }
            }
          }
        }
      }
    }

    matches
  }

  def actionMatch(jsAction: JsObject, action: ApiAction): Boolean = {
    System.out.println(
      "actionMatch: url " + jsAction.fields("url").convertTo[String] + "; backendUrl " + action.backendUrl)
    System.out.println(
      "actionMatch: namespace " + jsAction.fields("namespace").convertTo[String] + "; namespace " + action.namespace)
    System.out.println("actionMatch: action " + jsAction.fields("action").convertTo[String] + "; action " + action.name)

    jsAction.fields("url").convertTo[String] == action.backendUrl &&
    jsAction.fields("namespace").convertTo[String] == action.namespace &&
    jsAction.fields("action").convertTo[String] == action.name
  }

  behavior of "API Gateway apimgmt action parameter validation"

  it should "verify successful creation of a new API" in {
    val testName = "APIGWTEST1"
    val testbasepath = "/" + testName + "_bp"
    val testrelpath = "/path"
    val testurlop = "get"
    val testapiname = testName + " API Name"
    val actionName = testName + "_action"
    val actionNamespace = wskprops.namespace
    val actionUrl = "https://some.whisk.host/api/v1/web/" + actionNamespace + "/default/" + actionName + ".json"
    val actionAuthKey = testName + "_authkey"
    val testaction =
      new ApiAction(name = actionName, namespace = actionNamespace, backendUrl = actionUrl, authkey = actionAuthKey)

    try {
      val createResult = createApi(
        namespace = Some(wskprops.namespace),
        basepath = Some(testbasepath),
        relpath = Some(testrelpath),
        operation = Some(testurlop),
        apiname = Some(testapiname),
        action = Some(testaction))
      JsObjectHelper(createResult.stdout.parseJson.asJsObject).fieldPathExists("apidoc") should be(true)
      val apiVector = getApis(bpOrName = Some(testbasepath), relpath = Some(testrelpath), operation = Some(testurlop))
      apiVector.size should be > 0
      apiMatch(apiVector, testbasepath, testrelpath, testurlop, testapiname, testaction) should be(true)
    } finally {
      val deleteResult =
        deleteApi(namespace = Some(wskprops.namespace), basepath = Some(testbasepath), expectedExitCode = DONTCARE_EXIT)
    }
  }

  it should "verify successful API deletion using basepath" in {
    val testName = "APIGWTEST2"
    val testbasepath = "/" + testName + "_bp"
    val testrelpath = "/path"
    val testurlop = "get"
    val testapiname = testName + " API Name"
    val actionName = testName + "_action"
    val actionNamespace = wskprops.namespace
    val actionUrl = "https://some.whisk.host/api/v1/web/" + actionNamespace + "/default/" + actionName + ".json"
    val actionAuthKey = testName + "_authkey"
    val testaction =
      new ApiAction(name = actionName, namespace = actionNamespace, backendUrl = actionUrl, authkey = actionAuthKey)

    try {
      val createResult = createApi(
        namespace = Some(wskprops.namespace),
        basepath = Some(testbasepath),
        relpath = Some(testrelpath),
        operation = Some(testurlop),
        apiname = Some(testapiname),
        action = Some(testaction))
      JsObjectHelper(createResult.stdout.parseJson.asJsObject).fieldPathExists("apidoc") should be(true)
      var apiVector = getApis(bpOrName = Some(testbasepath), relpath = Some(testrelpath), operation = Some(testurlop))
      apiVector.size should be > 0
      apiMatch(apiVector, testbasepath, testrelpath, testurlop, testapiname, testaction) should be(true)
      val deleteResult = deleteApi(namespace = Some(wskprops.namespace), basepath = Some(testbasepath))
      apiVector = getApis(bpOrName = Some(testbasepath), relpath = Some(testrelpath), operation = Some(testurlop))
      apiMatch(apiVector, testbasepath, testrelpath, testurlop, testapiname, testaction) should be(false)
    } finally {
      val deleteResult =
        deleteApi(namespace = Some(wskprops.namespace), basepath = Some(testbasepath), expectedExitCode = DONTCARE_EXIT)
    }
  }

  it should "verify successful addition of new relative path to existing API" in {
    val testName = "APIGWTEST3"
    val testbasepath = "/" + testName + "_bp"
    val testrelpath = "/path"
    val testnewrelpath = "/path_new"
    val testurlop = "get"
    val testnewurlop = "delete"
    val testapiname = testName + " API Name"
    val actionName = testName + "_action"
    val actionNamespace = wskprops.namespace
    val actionUrl = "https://some.whisk.host/api/v1/web/" + actionNamespace + "/default/" + actionName + ".json"
    val actionAuthKey = testName + "_authkey"
    val testaction =
      new ApiAction(name = actionName, namespace = actionNamespace, backendUrl = actionUrl, authkey = actionAuthKey)

    try {
      var createResult = createApi(
        namespace = Some(wskprops.namespace),
        basepath = Some(testbasepath),
        relpath = Some(testrelpath),
        operation = Some(testurlop),
        apiname = Some(testapiname),
        action = Some(testaction))
      createResult = createApi(
        namespace = Some(wskprops.namespace),
        basepath = Some(testbasepath),
        relpath = Some(testnewrelpath),
        operation = Some(testnewurlop),
        apiname = Some(testapiname),
        action = Some(testaction))
      JsObjectHelper(createResult.stdout.parseJson.asJsObject).fieldPathExists("apidoc") should be(true)
      var apiVector = getApis(bpOrName = Some(testbasepath))
      apiVector.size should be > 0
      apiMatch(apiVector, testbasepath, testrelpath, testurlop, testapiname, testaction) should be(true)
      apiMatch(apiVector, testbasepath, testnewrelpath, testnewurlop, testapiname, testaction) should be(true)
    } finally {
      val deleteResult =
        deleteApi(namespace = Some(wskprops.namespace), basepath = Some(testbasepath), expectedExitCode = DONTCARE_EXIT)
    }
  }
}
