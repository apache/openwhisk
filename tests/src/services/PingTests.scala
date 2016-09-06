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

package services

import org.junit.Assert.assertTrue

import java.io.File

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule

import com.jayway.restassured.RestAssured

import common.TestUtils
import common.WhiskProperties
import common.TestUtils.RunResult

/**
 * Basic tests to check that a Whisk installation is healthy in that all
 * components respond to a heartbeat ping
 */
object PingTests {
    val bin: File = WhiskProperties.getFileRelativeToWhiskHome("tools/health")

    def isAlive(name: String, whiskPropertyFile: String): RunResult = {
        TestUtils.runCmd(TestUtils.SUCCESS_EXIT, bin, WhiskProperties.python, "isAlive", "-d", whiskPropertyFile, "--wait", "30", name)
    }
}

class PingTests {
    @Rule
    def watcher(): TestRule = TestUtils.makeTestWatcher

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

    /**
     * Check that the main docker endpoint is functioning.
     */
    @Test
    def pingMainDocker(): Unit = {
        pingDocker("main.docker.endpoint", WhiskProperties.getMainDockerEndpoint)
    }

    /**
     * Check the kafka docker endpoint is functioning.
     */
    @Test
    def pingKafkaDocker(): Unit = {
        pingDocker("kafka.docker.endpoint", WhiskProperties.getKafkaDockerEndpoint)
    }

    /**
     * Check that the zookeeper endpoint is up and running
     */
    @Test
    def pingZookeeper(): Unit = {
        PingTests.isAlive("zookeeper", WhiskProperties.getFileRelativeToWhiskHome(".").getAbsolutePath)
    }

    /**
     * Check that the kafka endpoint is up and running
     */
    @Test
    def pingKafka(): Unit = {
        PingTests.isAlive("kafka", WhiskProperties.getFileRelativeToWhiskHome(".").getAbsolutePath)
    }

    /**
     * Check that the invoker endpoints are up and running
     */
    @Test
    def pingInvoker(): Unit = {
        for (i <- 0 until WhiskProperties.numberOfInvokers) {
            PingTests.isAlive("invoker" + i, WhiskProperties.getFileRelativeToWhiskHome(".").getAbsolutePath)
        }
    }

    /**
     * Check that the controller endpoint is up and running
     */
    @Test
    def pingController(): Unit = {
        PingTests.isAlive("controller", WhiskProperties.getFileRelativeToWhiskHome(".").getAbsolutePath)
    }
}
