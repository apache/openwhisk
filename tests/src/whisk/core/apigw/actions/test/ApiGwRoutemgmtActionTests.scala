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

package whisk.core.apigw.actions.test

import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfterAll
import org.scalatest.junit.JUnitRunner
import spray.json.DefaultJsonProtocol._
import spray.json._

import common.JsHelpers
import common.TestHelpers
import common.TestUtils.ANY_ERROR_EXIT
import common.TestUtils.DONTCARE_EXIT
import common.TestUtils.SUCCESS_EXIT
import common.TestUtils.RunResult
import common.Wsk
import common.WskAdmin
import common.WskActorSystem
import common.WskProps
import common.WskTestHelpers
import whisk.core.WhiskConfig

case class ApiAction(
    name: String,
    namespace: String,
    backendMethod: String = "POST",
    backendUrl: String,
    authkey: String) {
    def toJson(): JsObject = {
        return JsObject(
            "name" -> name.toJson,
            "namespace" -> namespace.toJson,
            "backendMethod" -> backendMethod.toJson,
            "backendUrl" -> backendUrl.toJson,
            "authkey" -> authkey.toJson
        )
    }
}

/**
 * Tests for basic CLI usage. Some of these tests require a deployed backend.
 */
@RunWith(classOf[JUnitRunner])
class ApiGwRoutemgmtActionTests
    extends TestHelpers
    with BeforeAndAfterAll
    with WskActorSystem
    with WskTestHelpers
    with JsHelpers {

    implicit val wskprops = WskProps()
    val wsk = new Wsk
    val (cliuser, clinamespace) = WskAdmin.getUser(wskprops.authKey)

    // Use the whisk.system authentication id and pwd for invoking whisk.system private actions
    val config = new WhiskConfig(Map(WhiskConfig.systemKey -> null))
    val systemKey = config.systemKey

    def getApis(
        bpOrName: Option[String],
        relpath: Option[String] = None,
        operation: Option[String] = None,
        docid: Option[String] = None): Vector[JsValue] = {
        val parms = Map[String, JsValue]() ++
            Map("__ow_meta_namespace" -> clinamespace.toJson) ++
            { bpOrName map { b => Map("basepath" -> b.toJson) } getOrElse Map[String, JsValue]() } ++
            { relpath map { r => Map("relpath" -> r.toJson) } getOrElse Map[String, JsValue]() } ++
            { operation map { o => Map("operation" -> o.toJson) } getOrElse Map[String, JsValue]() } ++
            { docid map { d => Map("docid" -> d.toJson) } getOrElse Map[String, JsValue]() }
        val wskprops = new WskProps(authKey = systemKey)
        val rr = wsk.action.invoke(
            name = "routemgmt/getApi",
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
        return apiJsArray.elements
    }

    def createApi(
        namespace: Option[String] = Some("_"),
        basepath: Option[String] = Some("/"),
        relpath: Option[String],
        operation: Option[String],
        apiname: Option[String],
        action: Option[ApiAction],
        swagger: Option[String] = None,
        expectedExitCode: Int = SUCCESS_EXIT): RunResult = {
        val parms = Map[String, JsValue]() ++
            { namespace map { n => Map("namespace" -> n.toJson) } getOrElse Map[String, JsValue]() } ++
            { basepath map { b => Map("basepath" -> b.toJson) } getOrElse Map[String, JsValue]() } ++
            { relpath map { r => Map("relpath" -> r.toJson) } getOrElse Map[String, JsValue]() } ++
            { operation map { o => Map("operation" -> o.toJson) } getOrElse Map[String, JsValue]() } ++
            { apiname map { an => Map("apiname" -> an.toJson) } getOrElse Map[String, JsValue]() } ++
            { action map { a => Map("action" -> a.toJson) } getOrElse Map[String, JsValue]() } ++
            { swagger map { s => Map("swagger" -> s.toJson) } getOrElse Map[String, JsValue]() }
        val wskprops = new WskProps(authKey = systemKey)
        val rr = wsk.action.invoke(
            name = "routemgmt/createApi",
            parameters = parms,
            blocking = true,
            result = true,
            expectedExitCode = expectedExitCode)(wskprops)
        return rr
    }

    def createRoute(
        namespace: Option[String] = Some("_"),
        basepath: Option[String] = Some("/"),
        relpath: Option[String],
        operation: Option[String],
        apiname: Option[String],
        action: Option[ApiAction],
        swagger: Option[String] = None,
        expectedExitCode: Int = SUCCESS_EXIT): RunResult = {
        val parms = Map[String, JsValue]() ++
            { namespace map { n => Map("namespace" -> n.toJson) } getOrElse Map[String, JsValue]() } ++
            { basepath map { b => Map("gatewayBasePath" -> b.toJson) } getOrElse Map[String, JsValue]() } ++
            { relpath map { r => Map("gatewayPath" -> r.toJson) } getOrElse Map[String, JsValue]() } ++
            { operation map { o => Map("gatewayMethod" -> o.toJson) } getOrElse Map[String, JsValue]() } ++
            { apiname map { an => Map("apiName" -> an.toJson) } getOrElse Map[String, JsValue]() } ++
            { action map { a => Map("action" -> a.toJson) } getOrElse Map[String, JsValue]() } ++
            { swagger map { s => Map("swagger" -> s.toJson) } getOrElse Map[String, JsValue]() }
        val parm = Map[String, JsValue]("apidoc" -> JsObject(parms)) ++
            { namespace map { n => Map("__ow_meta_namespace" -> n.toJson) } getOrElse Map[String, JsValue]() }
        val wskprops = new WskProps(authKey = systemKey)
        val rr = wsk.action.invoke(
            name = "routemgmt/createRoute",
            parameters = parm,
            blocking = true,
            result = true,
            expectedExitCode = expectedExitCode)(wskprops)
        return rr
    }

    def deleteApi(
        namespace: Option[String] = Some("_"),
        basepath: Option[String] = Some("/"),
        relpath: Option[String] = None,
        operation: Option[String] = None,
        apiname: Option[String] = None,
        force: Boolean = true,
        expectedExitCode: Int = SUCCESS_EXIT): RunResult = {
        val parms = Map[String, JsValue]() ++
            Map("force" -> force.toJson) ++
            { namespace map { n => Map("__ow_meta_namespace" -> n.toJson) } getOrElse Map[String, JsValue]() } ++
            { basepath map { b => Map("basepath" -> b.toJson) } getOrElse Map[String, JsValue]() } ++
            { relpath map { r => Map("relpath" -> r.toJson) } getOrElse Map[String, JsValue]() } ++
            { operation map { o => Map("operation" -> o.toJson) } getOrElse Map[String, JsValue]() } ++
            { apiname map { an => Map("apiname" -> an.toJson) } getOrElse Map[String, JsValue]() }
        val wskprops = new WskProps(authKey = systemKey)
        val rr = wsk.action.invoke(
            name = "routemgmt/deleteApi",
            parameters = parms,
            blocking = true,
            result = true,
            expectedExitCode = expectedExitCode)(wskprops)
        return rr
    }

    def apiMatch(
        apiarr: Vector[JsValue],
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
                val basepathMatches = (JsObjectHelper(api.asJsObject).getFieldPath("value", "apidoc", "basePath").get.convertTo[String] == basepath)
                if (basepathMatches) {
                    System.out.println("basePath matches: " + basepath)
                    val apinameExists = JsObjectHelper(api.asJsObject).fieldPathExists("value", "apidoc", "info", "title")
                    if (apinameExists) {
                        System.out.println("api name exists")
                        val apinameMatches = (JsObjectHelper(api.asJsObject).getFieldPath("value", "apidoc", "info", "title").get.convertTo[String] == apiname)
                        if (apinameMatches) {
                            System.out.println("api name matches: " + apiname)
                            val endpointMatches = JsObjectHelper(api.asJsObject).fieldPathExists("value", "apidoc", "paths", relpath, operation)
                            if (endpointMatches) {
                                System.out.println("endpoint exists/matches : " + relpath + "  " + operation)
                                val actionConfig = JsObjectHelper(api.asJsObject).getFieldPath("value", "apidoc", "paths", relpath, operation, "x-ibm-op-ext").get.asJsObject
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
        return matches
    }

    def actionMatch(
        jsAction: JsObject,
        action: ApiAction): Boolean = {
        val matches = jsAction.fields("backendMethod").convertTo[String] == action.backendMethod &&
            jsAction.fields("backendUrl").convertTo[String] == action.backendUrl &&
            jsAction.fields("actionNamespace").convertTo[String] == action.namespace &&
            jsAction.fields("actionName").convertTo[String] == action.name
        return matches
    }

    behavior of "API Gateway routemgmt action parameter validation"

    it should "verify successful creation of a new API" in {
        val testName = "APIGWTEST1"
        val testbasepath = "/" + testName + "_bp"
        val testrelpath = "/path"
        val testurlop = "get"
        val testapiname = testName + " API Name"
        val actionName = testName + "_action"
        val actionNamespace = clinamespace
        val actionUrl = "http://some.whisk.host/api/v1/namespaces/" + actionNamespace + "/actions/" + actionName
        val actionAuthKey = testName + "_authkey"
        val testaction = ApiAction(name = actionName, namespace = actionNamespace, backendUrl = actionUrl, authkey = actionAuthKey)

        try {
            val createResult = createRoute(namespace = Some(clinamespace), basepath = Some(testbasepath), relpath = Some(testrelpath),
                operation = Some(testurlop), apiname = Some(testapiname), action = Some(testaction))
            JsObjectHelper(createResult.stdout.parseJson.asJsObject).fieldPathExists("apidoc") should be(true)
            val apiVector = getApis(bpOrName = Some(testbasepath), relpath = Some(testrelpath), operation = Some(testurlop))
            apiVector.size should be > 0
            apiMatch(apiVector, testbasepath, testrelpath, testurlop, testapiname, testaction) should be(true)
        } finally {
            val deleteResult = deleteApi(namespace = Some(clinamespace), basepath = Some(testbasepath), force = true, expectedExitCode = DONTCARE_EXIT)
        }
    }

    it should "verify successful API deletion using basepath" in {
        val testName = "APIGWTEST2"
        val testbasepath = "/" + testName + "_bp"
        val testrelpath = "/path"
        val testurlop = "get"
        val testapiname = testName + " API Name"
        val actionName = testName + "_action"
        val actionNamespace = clinamespace
        val actionUrl = "http://some.whisk.host/api/v1/namespaces/" + actionNamespace + "/actions/" + actionName
        val actionAuthKey = testName + "_authkey"
        val testaction = ApiAction(name = actionName, namespace = actionNamespace, backendUrl = actionUrl, authkey = actionAuthKey)

        try {
            val createResult = createRoute(namespace = Some(clinamespace), basepath = Some(testbasepath), relpath = Some(testrelpath),
                operation = Some(testurlop), apiname = Some(testapiname), action = Some(testaction))
            JsObjectHelper(createResult.stdout.parseJson.asJsObject).fieldPathExists("apidoc") should be(true)
            var apiVector = getApis(bpOrName = Some(testbasepath), relpath = Some(testrelpath), operation = Some(testurlop))
            apiVector.size should be > 0
            apiMatch(apiVector, testbasepath, testrelpath, testurlop, testapiname, testaction) should be(true)
            val deleteResult = deleteApi(namespace = Some(clinamespace), basepath = Some(testbasepath), force = true)
            apiVector = getApis(bpOrName = Some(testbasepath), relpath = Some(testrelpath), operation = Some(testurlop))
            apiMatch(apiVector, testbasepath, testrelpath, testurlop, testapiname, testaction) should be(false)
        } finally {
            val deleteResult = deleteApi(namespace = Some(clinamespace), basepath = Some(testbasepath), force = true, expectedExitCode = DONTCARE_EXIT)
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
        val actionNamespace = clinamespace
        val actionUrl = "http://some.whisk.host/api/v1/namespaces/" + actionNamespace + "/actions/" + actionName
        val actionAuthKey = testName + "_authkey"
        val testaction = ApiAction(name = actionName, namespace = actionNamespace, backendUrl = actionUrl, authkey = actionAuthKey)

        try {
            var createResult = createRoute(namespace = Some(clinamespace), basepath = Some(testbasepath), relpath = Some(testrelpath),
                operation = Some(testurlop), apiname = Some(testapiname), action = Some(testaction))
            createResult = createRoute(namespace = Some(clinamespace), basepath = Some(testbasepath), relpath = Some(testnewrelpath),
                operation = Some(testnewurlop), apiname = Some(testapiname), action = Some(testaction))
            JsObjectHelper(createResult.stdout.parseJson.asJsObject).fieldPathExists("apidoc") should be(true)
            var apiVector = getApis(bpOrName = Some(testbasepath))
            apiVector.size should be > 0
            apiMatch(apiVector, testbasepath, testrelpath, testurlop, testapiname, testaction) should be(true)
            apiMatch(apiVector, testbasepath, testnewrelpath, testnewurlop, testapiname, testaction) should be(true)
        } finally {
            val deleteResult = deleteApi(namespace = Some(clinamespace), basepath = Some(testbasepath), force = true, expectedExitCode = DONTCARE_EXIT)
        }
    }

    it should "reject routemgmt actions that are invoked with not enough parameters" in {
        val invalidArgs = Seq(
            //getApi
            ("/whisk.system/routemgmt/getApi", ANY_ERROR_EXIT, "namespace is required", Seq()),

            //deleteApi
            ("/whisk.system/routemgmt/deleteApi", ANY_ERROR_EXIT, "namespace is required", Seq("-p", "basepath", "/ApiGwRoutemgmtActionTests_bp")),
            ("/whisk.system/routemgmt/deleteApi", ANY_ERROR_EXIT, "basepath is required", Seq("-p", "__ow_meta_namespace", "_")),
            ("/whisk.system/routemgmt/deleteApi", ANY_ERROR_EXIT, "When specifying an operation, the relpath is required",
                Seq("-p", "__ow_meta_namespace", "_", "-p", "basepath", "/ApiGwRoutemgmtActionTests_bp", "-p", "operation", "get")),

            //deactivateApi
            ("/whisk.system/routemgmt/deactivateApi", ANY_ERROR_EXIT, "namespace is required", Seq("-p", "basepath", "/ApiGwRoutemgmtActionTests_bp")),
            ("/whisk.system/routemgmt/deactivateApi", ANY_ERROR_EXIT, "basepath is required", Seq("-p", "namespace", "_")),

            //activateApi
            ("/whisk.system/routemgmt/activateApi", ANY_ERROR_EXIT, "namespace is required", Seq("-p", "basepath", "/ApiGwRoutemgmtActionTests_bp")),

            //createApi
            ("/whisk.system/routemgmt/createApi", ANY_ERROR_EXIT, "namespace is required",
                Seq(
                    //"-p", "namespace", "_",
                    "-p", "basepath", "/ApiGwRoutemgmtActionTests_bp",
                    "-p", "relpath", "/ApiGwRoutemgmtActionTests_rp",
                    "-p", "operation", "get",
                    "-p", "action", """{"backendUrl":"URL","backendMethod":"get","namespace":"_","name":"myaction","authkey":"XXXXX"}""")),
            ("/whisk.system/routemgmt/createApi", ANY_ERROR_EXIT, "basepath is required when swagger is not specified",
                Seq(
                    "-p", "namespace", "_",
                    //"-p", "basepath", "/ApiGwRoutemgmtActionTests_bp",
                    "-p", "relpath", "/ApiGwRoutemgmtActionTests_rp",
                    "-p", "operation", "get",
                    "-p", "action", """{"backendUrl":"URL","backendMethod":"get","namespace":"_","name":"myaction","authkey":"XXXXX"}""")),
            ("/whisk.system/routemgmt/createApi", ANY_ERROR_EXIT, "relpath is required when swagger is not specified",
                Seq(
                    "-p", "namespace", "_",
                    "-p", "basepath", "/ApiGwRoutemgmtActionTests_bp",
                    //"-p", "relpath", "/ApiGwRoutemgmtActionTests_rp",
                    "-p", "operation", "get",
                    "-p", "action", """{"backendUrl":"URL","backendMethod":"get","namespace":"_","name":"myaction","authkey":"XXXXX"}""")),
            ("/whisk.system/routemgmt/createApi", ANY_ERROR_EXIT, "operation is required when swagger is not specified",
                Seq(
                    "-p", "namespace", "_",
                    "-p", "basepath", "/ApiGwRoutemgmtActionTests_bp",
                    "-p", "relpath", "/ApiGwRoutemgmtActionTests_rp",
                    //"-p", "operation", "get",
                    "-p", "action", """{"backendUrl":"URL","backendMethod":"get","namespace":"_","name":"myaction","authkey":"XXXXX"}""")),
            ("/whisk.system/routemgmt/createApi", ANY_ERROR_EXIT, "action is required when swagger is not specified",
                Seq(
                    "-p", "namespace", "_",
                    "-p", "basepath", "/ApiGwRoutemgmtActionTests_bp",
                    "-p", "relpath", "/ApiGwRoutemgmtActionTests_rp",
                    "-p", "operation", "get"
                //"-p", "action", """{"backendUrl":"URL","backendMethod":"get","namespace":"_","name":"myaction","authkey":"XXXXX"}"""
                )),
            ("/whisk.system/routemgmt/createApi", ANY_ERROR_EXIT, "action is missing the backendUrl field",
                Seq(
                    "-p", "namespace", "_",
                    "-p", "basepath", "/ApiGwRoutemgmtActionTests_bp",
                    "-p", "relpath", "/ApiGwRoutemgmtActionTests_rp",
                    "-p", "operation", "get",
                    "-p", "action", """{"backendMethod":"get","namespace":"_","name":"myaction","authkey":"XXXXX"}""")),
            ("/whisk.system/routemgmt/createApi", ANY_ERROR_EXIT, "action is missing the backendMethod field",
                Seq(
                    "-p", "namespace", "_",
                    "-p", "basepath", "/ApiGwRoutemgmtActionTests_bp",
                    "-p", "relpath", "/ApiGwRoutemgmtActionTests_rp",
                    "-p", "operation", "get",
                    "-p", "action", """{"backendUrl":"URL","namespace":"_","name":"myaction","authkey":"XXXXX"}""")),
            ("/whisk.system/routemgmt/createApi", ANY_ERROR_EXIT, "action is missing the namespace field",
                Seq(
                    "-p", "namespace", "_",
                    "-p", "basepath", "/ApiGwRoutemgmtActionTests_bp",
                    "-p", "relpath", "/ApiGwRoutemgmtActionTests_rp",
                    "-p", "operation", "get",
                    "-p", "action", """{"backendUrl":"URL","backendMethod":"get","name":"myaction","authkey":"XXXXX"}""")),
            ("/whisk.system/routemgmt/createApi", ANY_ERROR_EXIT, "action is missing the name field",
                Seq(
                    "-p", "namespace", "_",
                    "-p", "basepath", "/ApiGwRoutemgmtActionTests_bp",
                    "-p", "relpath", "/ApiGwRoutemgmtActionTests_rp",
                    "-p", "operation", "get",
                    "-p", "action", """{"backendUrl":"URL","backendMethod":"get","namespace":"_","authkey":"XXXXX"}""")),
            ("/whisk.system/routemgmt/createApi", ANY_ERROR_EXIT, "action is missing the authkey field",
                Seq(
                    "-p", "namespace", "_",
                    "-p", "basepath", "/ApiGwRoutemgmtActionTests_bp",
                    "-p", "relpath", "/ApiGwRoutemgmtActionTests_rp",
                    "-p", "operation", "get",
                    "-p", "action", """{"backendUrl":"URL","backendMethod":"get","namespace":"_","name":"myaction"}""")),
            ("/whisk.system/routemgmt/createApi", ANY_ERROR_EXIT, "swagger field cannot be parsed. Ensure it is valid JSON",
                Seq("-p", "namespace", "_", "-p", "swagger", "{1:[}}}")),
            ("/whisk.system/routemgmt/createApi", ANY_ERROR_EXIT, "swagger and basepath are mutually exclusive and cannot be specified together",
                Seq("-p", "namespace", "_", "-p", "basepath", "/ApiGwRoutemgmtActionTests_bp", "-p", "swagger", "{}")),

            //createRoute
            ("/whisk.system/routemgmt/createRoute", ANY_ERROR_EXIT, "apidoc is required", Seq("-p", "__ow_meta_namespace", "_")),
            ("/whisk.system/routemgmt/createRoute", ANY_ERROR_EXIT, "apidoc is missing the namespace field",
                Seq("-p", "__ow_meta_namespace", "_", "-p", "apidoc", "{}")),
            ("/whisk.system/routemgmt/createRoute", ANY_ERROR_EXIT, "apidoc is missing the gatewayBasePath field",
                Seq("-p", "__ow_meta_namespace", "_", "-p", "apidoc", """{"namespace":"_"}""")),
            ("/whisk.system/routemgmt/createRoute", ANY_ERROR_EXIT, "apidoc is missing the gatewayPath field",
                Seq("-p", "__ow_meta_namespace", "_", "-p", "apidoc", """{"namespace":"_","gatewayBasePath":"/ApiGwRoutemgmtActionTests_bp"}""")),
            ("/whisk.system/routemgmt/createRoute", ANY_ERROR_EXIT, "apidoc is missing the gatewayMethod field",
                Seq("-p", "__ow_meta_namespace", "_", "-p", "apidoc", """{"namespace":"_","gatewayBasePath":"/ApiGwRoutemgmtActionTests_bp","gatewayPath":"ApiGwRoutemgmtActionTests_rp"}""")),
            ("/whisk.system/routemgmt/createRoute", ANY_ERROR_EXIT, "apidoc is missing the action field",
                Seq("-p", "__ow_meta_namespace", "_", "-p", "apidoc", """{"namespace":"_","gatewayBasePath":"/ApiGwRoutemgmtActionTests_bp","gatewayPath":"ApiGwRoutemgmtActionTests_rp","gatewayMethod":"get"}""")),
            ("/whisk.system/routemgmt/createRoute", ANY_ERROR_EXIT, "action is missing the backendMethod field",
                Seq("-p", "__ow_meta_namespace", "_", "-p", "apidoc", """{"namespace":"_","gatewayBasePath":"/ApiGwRoutemgmtActionTests_bp","gatewayPath":"ApiGwRoutemgmtActionTests_rp","gatewayMethod":"get","action":{}}""")),
            ("/whisk.system/routemgmt/createRoute", ANY_ERROR_EXIT, "action is missing the backendUrl field",
                Seq("-p", "__ow_meta_namespace", "_", "-p", "apidoc", """{"namespace":"_","gatewayBasePath":"/ApiGwRoutemgmtActionTests_bp","gatewayPath":"ApiGwRoutemgmtActionTests_rp","gatewayMethod":"get","action":{"backendMethod":"post"}}""")),
            ("/whisk.system/routemgmt/createRoute", ANY_ERROR_EXIT, "action is missing the namespace field",
                Seq("-p", "__ow_meta_namespace", "_", "-p", "apidoc", """{"namespace":"_","gatewayBasePath":"/ApiGwRoutemgmtActionTests_bp","gatewayPath":"ApiGwRoutemgmtActionTests_rp","gatewayMethod":"get","action":{"backendMethod":"post","backendUrl":"URL"}}""")),
            ("/whisk.system/routemgmt/createRoute", ANY_ERROR_EXIT, "action is missing the name field",
                Seq("-p", "__ow_meta_namespace", "_", "-p", "apidoc", """{"namespace":"_","gatewayBasePath":"/ApiGwRoutemgmtActionTests_bp","gatewayPath":"ApiGwRoutemgmtActionTests_rp","gatewayMethod":"get","action":{"backendMethod":"post","backendUrl":"URL","namespace":"_"}}""")),
            ("/whisk.system/routemgmt/createRoute", ANY_ERROR_EXIT, "action is missing the authkey field",
                Seq("-p", "__ow_meta_namespace", "_", "-p", "apidoc", """{"namespace":"_","gatewayBasePath":"/ApiGwRoutemgmtActionTests_bp","gatewayPath":"ApiGwRoutemgmtActionTests_rp","gatewayMethod":"get","action":{"backendMethod":"post","backendUrl":"URL","namespace":"_","name":"N"}}""")),
            ("/whisk.system/routemgmt/createRoute", ANY_ERROR_EXIT, "swagger and gatewayBasePath are mutually exclusive and cannot be specified together",
                Seq("-p", "__ow_meta_namespace", "_", "-p", "apidoc", """{"namespace":"_","gatewayBasePath":"/ApiGwRoutemgmtActionTests_bp","gatewayPath":"ApiGwRoutemgmtActionTests_rp","gatewayMethod":"get","action":{"backendMethod":"post","backendUrl":"URL","namespace":"_","name":"N","authkey":"XXXX"},"swagger":{}}""")),

            ("/whisk.system/routemgmt/createRoute", ANY_ERROR_EXIT, "apidoc field cannot be parsed. Ensure it is valid JSON",
                Seq("-p", "__ow_meta_namespace", "_", "-p", "apidoc", "{1:[}}}"))
        )

        invalidArgs foreach {
            case (action: String, exitcode: Int, errmsg: String, params: Seq[String]) =>
                var cmd: Seq[String] = Seq("action",
                    "invoke",
                    action,
                    "-i", "-b", "-r",
                    "--apihost", wskprops.apihost,
                    "--auth", systemKey
                ) ++ params
                val rr = wsk.cli(cmd, expectedExitCode = exitcode)
                rr.stderr should include regex (errmsg)
        }
    }
}
