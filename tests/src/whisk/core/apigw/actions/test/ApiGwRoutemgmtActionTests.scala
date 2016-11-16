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
import scala.concurrent.Await
import scala.concurrent.duration._

import common.TestHelpers
import common.TestUtils.ANY_ERROR_EXIT
import common.Wsk
import common.WskActorSystem
import common.WskProps
import common.WskTestHelpers
import whisk.common.TransactionId
import whisk.core.WhiskConfig
import whisk.core.entity.Subject
import whisk.core.entity.WhiskEntityStore
import whisk.core.entity.WhiskAuth
import whisk.core.entity.WhiskAuthStore

/**
 * Tests for basic CLI usage. Some of these tests require a deployed backend.
 */
@RunWith(classOf[JUnitRunner])
class ApiGwRoutemgmtActionTests
    extends TestHelpers
    with BeforeAndAfterAll
    with WskActorSystem
    with WskTestHelpers {

    implicit val wskprops = WskProps()
    val wsk = new Wsk

    // Need the whisk.system authentication id and pwd for invoking whisk.system private actions
    val config = new WhiskConfig(WhiskEntityStore.requiredProperties)
    val authstore = WhiskAuthStore.datastore(config)
    val whiskkey = Await.result(WhiskAuth.get(authstore, Subject("whisk.system"),false)(TransactionId.unknown), 5.seconds)
    val whisksystemAuthId = whiskkey.authkey.uuid.toString
    val whisksystemAuthPwd = whiskkey.authkey.key.toString

    override def afterAll() {
        println("Shutting down authstore connection")
        authstore.shutdown()
        super.afterAll()
    }

    behavior of "API Gateway routemgmt action parameter validation"

    it should "reject routemgmt actions that are invoked with not enough parameters" in {
        val invalidArgs = Seq(
            //getApi
            ("/whisk.system/routemgmt/getApi", ANY_ERROR_EXIT, "namespace is required", Seq()),

            //deleteApi
            ("/whisk.system/routemgmt/deleteApi", ANY_ERROR_EXIT, "namespace is required", Seq("-p", "basepath", "/ApiGwRoutemgmtActionTests_bp")),
            ("/whisk.system/routemgmt/deleteApi", ANY_ERROR_EXIT, "basepath is required", Seq("-p", "namespace", "_")),
            ("/whisk.system/routemgmt/deleteApi", ANY_ERROR_EXIT, "When specifying an operation, the relpath is required",
              Seq("-p", "namespace", "_", "-p", "basepath", "/ApiGwRoutemgmtActionTests_bp", "-p", "operation", "get")),

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
                  "-p", "action", "{\"backendUrl\":\"URL\",\"backendMethod\":\"get\",\"namespace\":\"_\",\"name\":\"myaction\",\"authkey\":\"XXXXX\"}")),
            ("/whisk.system/routemgmt/createApi", ANY_ERROR_EXIT, "basepath is required when swagger is not specified",
              Seq(
                  "-p", "namespace", "_",
                  //"-p", "basepath", "/ApiGwRoutemgmtActionTests_bp",
                  "-p", "relpath", "/ApiGwRoutemgmtActionTests_rp",
                  "-p", "operation", "get",
                  "-p", "action", "{\"backendUrl\":\"URL\",\"backendMethod\":\"get\",\"namespace\":\"_\",\"name\":\"myaction\",\"authkey\":\"XXXXX\"}")),
            ("/whisk.system/routemgmt/createApi", ANY_ERROR_EXIT, "relpath is required when swagger is not specified",
              Seq(
                  "-p", "namespace", "_",
                  "-p", "basepath", "/ApiGwRoutemgmtActionTests_bp",
                  //"-p", "relpath", "/ApiGwRoutemgmtActionTests_rp",
                  "-p", "operation", "get",
                  "-p", "action", "{\"backendUrl\":\"URL\",\"backendMethod\":\"get\",\"namespace\":\"_\",\"name\":\"myaction\",\"authkey\":\"XXXXX\"}")),
            ("/whisk.system/routemgmt/createApi", ANY_ERROR_EXIT, "operation is required when swagger is not specified",
              Seq(
                  "-p", "namespace", "_",
                  "-p", "basepath", "/ApiGwRoutemgmtActionTests_bp",
                  "-p", "relpath", "/ApiGwRoutemgmtActionTests_rp",
                  //"-p", "operation", "get",
                  "-p", "action", "{\"backendUrl\":\"URL\",\"backendMethod\":\"get\",\"namespace\":\"_\",\"name\":\"myaction\",\"authkey\":\"XXXXX\"}")),
            ("/whisk.system/routemgmt/createApi", ANY_ERROR_EXIT, "action is required when swagger is not specified",
              Seq(
                  "-p", "namespace", "_",
                  "-p", "basepath", "/ApiGwRoutemgmtActionTests_bp",
                  "-p", "relpath", "/ApiGwRoutemgmtActionTests_rp",
                  "-p", "operation", "get"
                  //"-p", "action", "{\"backendUrl\":\"URL\",\"backendMethod\":\"get\",\"namespace\":\"_\",\"name\":\"myaction\",\"authkey\":\"XXXXX\"}"
              )),
            ("/whisk.system/routemgmt/createApi", ANY_ERROR_EXIT, "action is missing the backendUrl field",
              Seq(
                  "-p", "namespace", "_",
                  "-p", "basepath", "/ApiGwRoutemgmtActionTests_bp",
                  "-p", "relpath", "/ApiGwRoutemgmtActionTests_rp",
                  "-p", "operation", "get",
                  "-p", "action", "{\"backendMethod\":\"get\",\"namespace\":\"_\",\"name\":\"myaction\",\"authkey\":\"XXXXX\"}")),
            ("/whisk.system/routemgmt/createApi", ANY_ERROR_EXIT, "action is missing the backendMethod field",
              Seq(
                  "-p", "namespace", "_",
                  "-p", "basepath", "/ApiGwRoutemgmtActionTests_bp",
                  "-p", "relpath", "/ApiGwRoutemgmtActionTests_rp",
                  "-p", "operation", "get",
                  "-p", "action", "{\"backendUrl\":\"URL\",\"namespace\":\"_\",\"name\":\"myaction\",\"authkey\":\"XXXXX\"}")),
            ("/whisk.system/routemgmt/createApi", ANY_ERROR_EXIT, "action is missing the namespace field",
              Seq(
                  "-p", "namespace", "_",
                  "-p", "basepath", "/ApiGwRoutemgmtActionTests_bp",
                  "-p", "relpath", "/ApiGwRoutemgmtActionTests_rp",
                  "-p", "operation", "get",
                  "-p", "action", "{\"backendUrl\":\"URL\",\"backendMethod\":\"get\",\"name\":\"myaction\",\"authkey\":\"XXXXX\"}")),
            ("/whisk.system/routemgmt/createApi", ANY_ERROR_EXIT, "action is missing the name field",
              Seq(
                  "-p", "namespace", "_",
                  "-p", "basepath", "/ApiGwRoutemgmtActionTests_bp",
                  "-p", "relpath", "/ApiGwRoutemgmtActionTests_rp",
                  "-p", "operation", "get",
                  "-p", "action", "{\"backendUrl\":\"URL\",\"backendMethod\":\"get\",\"namespace\":\"_\",\"authkey\":\"XXXXX\"}")),
            ("/whisk.system/routemgmt/createApi", ANY_ERROR_EXIT, "action is missing the authkey field",
              Seq(
                  "-p", "namespace", "_",
                  "-p", "basepath", "/ApiGwRoutemgmtActionTests_bp",
                  "-p", "relpath", "/ApiGwRoutemgmtActionTests_rp",
                  "-p", "operation", "get",
                  "-p", "action", "{\"backendUrl\":\"URL\",\"backendMethod\":\"get\",\"namespace\":\"_\",\"name\":\"myaction\"}")),
            ("/whisk.system/routemgmt/createApi", ANY_ERROR_EXIT, "swagger field cannot be parsed. Ensure it is valid JSON",
              Seq("-p", "namespace", "_", "-p", "swagger", "{1:[}}}")),
            ("/whisk.system/routemgmt/createApi", ANY_ERROR_EXIT, "swagger and basepath are mutually exclusive and cannot be specified together",
              Seq("-p", "namespace", "_", "-p", "basepath", "/ApiGwRoutemgmtActionTests_bp", "-p", "swagger", "{}")),

            //createRoute
            ("/whisk.system/routemgmt/createRoute", ANY_ERROR_EXIT, "apidoc is required", Seq()),
            ("/whisk.system/routemgmt/createRoute", ANY_ERROR_EXIT, "apidoc is missing the namespace field",
              Seq("-p", "apidoc", "{}")),
            ("/whisk.system/routemgmt/createRoute", ANY_ERROR_EXIT, "apidoc is missing the gatewayBasePath field",
              Seq("-p", "apidoc", "{\"namespace\":\"_\"}")),
            ("/whisk.system/routemgmt/createRoute", ANY_ERROR_EXIT, "apidoc is missing the gatewayPath field",
              Seq("-p", "apidoc", "{\"namespace\":\"_\",\"gatewayBasePath\":\"/ApiGwRoutemgmtActionTests_bp\"}")),
            ("/whisk.system/routemgmt/createRoute", ANY_ERROR_EXIT, "apidoc is missing the gatewayMethod field",
              Seq("-p", "apidoc", "{\"namespace\":\"_\",\"gatewayBasePath\":\"/ApiGwRoutemgmtActionTests_bp\",\"gatewayPath\":\"ApiGwRoutemgmtActionTests_rp\"}")),
            ("/whisk.system/routemgmt/createRoute", ANY_ERROR_EXIT, "apidoc is missing the action field",
              Seq("-p", "apidoc", "{\"namespace\":\"_\",\"gatewayBasePath\":\"/ApiGwRoutemgmtActionTests_bp\",\"gatewayPath\":\"ApiGwRoutemgmtActionTests_rp\",\"gatewayMethod\":\"get\"}")),
            ("/whisk.system/routemgmt/createRoute", ANY_ERROR_EXIT, "action is missing the backendMethod field",
              Seq("-p", "apidoc", "{\"namespace\":\"_\",\"gatewayBasePath\":\"/ApiGwRoutemgmtActionTests_bp\",\"gatewayPath\":\"ApiGwRoutemgmtActionTests_rp\",\"gatewayMethod\":\"get\",\"action\":{}}")),
            ("/whisk.system/routemgmt/createRoute", ANY_ERROR_EXIT, "action is missing the backendUrl field",
              Seq("-p", "apidoc", "{\"namespace\":\"_\",\"gatewayBasePath\":\"/ApiGwRoutemgmtActionTests_bp\",\"gatewayPath\":\"ApiGwRoutemgmtActionTests_rp\",\"gatewayMethod\":\"get\",\"action\":{\"backendMethod\":\"post\"}}")),
            ("/whisk.system/routemgmt/createRoute", ANY_ERROR_EXIT, "action is missing the namespace field",
              Seq("-p", "apidoc", "{\"namespace\":\"_\",\"gatewayBasePath\":\"/ApiGwRoutemgmtActionTests_bp\",\"gatewayPath\":\"ApiGwRoutemgmtActionTests_rp\",\"gatewayMethod\":\"get\",\"action\":{\"backendMethod\":\"post\",\"backendUrl\":\"URL\"}}")),
            ("/whisk.system/routemgmt/createRoute", ANY_ERROR_EXIT, "action is missing the name field",
              Seq("-p", "apidoc", "{\"namespace\":\"_\",\"gatewayBasePath\":\"/ApiGwRoutemgmtActionTests_bp\",\"gatewayPath\":\"ApiGwRoutemgmtActionTests_rp\",\"gatewayMethod\":\"get\",\"action\":{\"backendMethod\":\"post\",\"backendUrl\":\"URL\",\"namespace\":\"_\"}}")),
            ("/whisk.system/routemgmt/createRoute", ANY_ERROR_EXIT, "action is missing the authkey field",
              Seq("-p", "apidoc", "{\"namespace\":\"_\",\"gatewayBasePath\":\"/ApiGwRoutemgmtActionTests_bp\",\"gatewayPath\":\"ApiGwRoutemgmtActionTests_rp\",\"gatewayMethod\":\"get\",\"action\":{\"backendMethod\":\"post\",\"backendUrl\":\"URL\",\"namespace\":\"_\",\"name\":\"N\"}}")),
            ("/whisk.system/routemgmt/createRoute", ANY_ERROR_EXIT, "swagger and gatewayBasePath are mutually exclusive and cannot be specified together",
              Seq("-p", "apidoc", "{\"namespace\":\"_\",\"gatewayBasePath\":\"/ApiGwRoutemgmtActionTests_bp\",\"gatewayPath\":\"ApiGwRoutemgmtActionTests_rp\",\"gatewayMethod\":\"get\",\"action\":{\"backendMethod\":\"post\",\"backendUrl\":\"URL\",\"namespace\":\"_\",\"name\":\"N\",\"authkey\":\"XXXX\"},\"swagger\":{}}")),

            ("/whisk.system/routemgmt/createRoute", ANY_ERROR_EXIT, "apidoc field cannot be parsed. Ensure it is valid JSON",
              Seq("-p", "namespace", "_", "-p", "apidoc", "{1:[}}}"))
        )

        invalidArgs foreach {
            case (action:String, exitcode:Int, errmsg:String, params:Seq[String]) =>
                var cmd: Seq[String] = Seq("action",
                    "invoke",
                    action,
                    "-i", "-b", "-r",
                    "--apihost", wskprops.apihost,
                    "--auth", whisksystemAuthId+":"+whisksystemAuthPwd
                ) ++ params
                val rr = wsk.cli(cmd, expectedExitCode = exitcode)
                rr.stderr should include regex (errmsg)
        }
    }
}
