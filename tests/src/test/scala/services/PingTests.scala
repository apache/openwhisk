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

package services

import java.io.File

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt
import scala.util.Try

import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner

import com.jayway.restassured.RestAssured

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import akka.stream.Materializer
import common.TestUtils
import common.TestUtils.RunResult
import common.WhiskProperties
import common.WskActorSystem
import common.WskTestHelpers
import akka.http.scaladsl.model.StatusCodes

/**
 * Basic tests to check that a Whisk installation is healthy in that all
 * components respond to a heartbeat ping
 */
object PingTests {
    val bin: File = WhiskProperties.getFileRelativeToWhiskHome("tools/health")

    def isAliveScript(name: String, whiskPropertyFile: String): RunResult = {
        TestUtils.runCmd(TestUtils.SUCCESS_EXIT, bin, WhiskProperties.python, "isAlive", "-d", whiskPropertyFile, "--wait", "30", name)
    }

    def ping(host: String, port: Int)(implicit actorSystem: ActorSystem, ec: ExecutionContext, materializer: Materializer) = {
        val response = Try { Await.result(Http().singleRequest(HttpRequest(uri = s"http://$host:$port/ping")), 10.seconds) }.toOption

        response.map { res =>
            (res.status, Await.result(Unmarshal(res).to[String], 10.seconds))
        }
    }
}

@RunWith(classOf[JUnitRunner])
class PingTests extends FlatSpec
    with Matchers
    with WskTestHelpers
    with ScalaFutures
    with WskActorSystem {

    implicit val materializer = ActorMaterializer()

    /**
     * Check that the docker REST interface at endpoint is up. envVar is the
     * environment variable from which we obtain.
     */
    def pingDocker(envVar: String, endpoint: String): Unit = {
        assertTrue(envVar + " not set", endpoint != null)
        assertTrue(envVar + " not set", endpoint.length > 0)

        val response: String = RestAssured.given.port(4243).baseUri("http://" + endpoint).get("/info").body.asString
        println("GET /info")
        println(response)
        assertTrue(response.contains("Containers"))
    }

    behavior of "PingTest"

    it should "check that the main docker endpoint is functioning" in {
        pingDocker("main.docker.endpoint", WhiskProperties.getMainDockerEndpoint)
    }

    it should "Check the kafka docker endpoint is functioning" in {
        pingDocker("kafka.docker.endpoint", WhiskProperties.getKafkaDockerEndpoint)
    }

    it should "check that the zookeeper endpoint is up and running" in {
        PingTests.isAliveScript("zookeeper", WhiskProperties.getFileRelativeToWhiskHome(".").getAbsolutePath)
    }

    it should "Check that the invoker endpoints are up and running" in {
        val basePort = WhiskProperties.getProperty("invoker.hosts.baseport").toInt
        val invokers = WhiskProperties.getInvokerHosts.zipWithIndex.map {
            case (invoker, instance) =>
                val res = PingTests.ping(invoker, basePort + instance)

                res shouldBe defined
                res.get._1 shouldBe StatusCodes.OK
                res.get._2 shouldBe "pong"
        }
    }

    it should "check that the controller endpoint is up and running" in {
        val basePort = WhiskProperties.getControllerBasePort()
        val controllers = WhiskProperties.getControllerHosts().split(",").zipWithIndex.map {
            case (controller, instance) =>
                val res = PingTests.ping(controller, basePort + instance)

                res shouldBe defined
                res.get._1 shouldBe StatusCodes.OK
                res.get._2 shouldBe "pong"
        }
    }
}
