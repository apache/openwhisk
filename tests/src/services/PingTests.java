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

package services;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;

import com.jayway.restassured.RestAssured;

import common.Pair;
import common.TestUtils;
import common.WhiskProperties;

/**
 * Basic tests to check that a Whisk installation is healthy in that all
 * components respond to a heartbeat ping
 */
public class PingTests {
    static final File bin = WhiskProperties.getFileRelativeToWhiskHome("tools/health");

    @Rule
    public TestRule watcher = TestUtils.makeTestWatcher();

    /**
     * Check that the docker REST interface at endpoint is up. var is the
     * environment variable from which we obtain.
     */
    public void pingDocker(String var, String endpoint) {
        assertTrue(var + " not set", endpoint != null);
        assertTrue(var + " not set", endpoint.length() > 0);

        String response = RestAssured.given().port(4243).baseUri("http://" + endpoint).get("/info").body().asString();
        System.out.println("GET /info");
        System.out.println(response);
        assertTrue(response.contains("Containers"));
    }

    /**
     * Check that the main docker endpoint is functioning.
     */
    @Test
    public void pingMainDocker() {
        pingDocker("main.docker.endpoint", WhiskProperties.getMainDockerEndpoint());
    }

    /**
     * Check the kafka docker endpoint is functioning.
     */
    @Test
    public void pingKafkaDocker() {
        pingDocker("kafka.docker.endpoint", WhiskProperties.getKafkaDockerEndpoint());
    }

    /**
     * Check that the zookeeper endpoint is up and running
     */
    @Test
    public void pingZookeeper() throws IOException {
        isAlive("zookeeper", WhiskProperties.getFileRelativeToWhiskHome(".").getAbsolutePath());
    }

    /**
     * Check that the kafka endpoint is up and running
     */
    @Test
    public void pingKafka() throws IOException {
        isAlive("kafka", WhiskProperties.getFileRelativeToWhiskHome(".").getAbsolutePath());
    }

    /**
     * Check that the activator endpoint is up and running
     */
    @Test
    public void pingActivator() throws IOException {
        PingTests.isAlive("activator", WhiskProperties.getFileRelativeToWhiskHome(".").getAbsolutePath());
    }

    /**
     * Check that the invoker endpoints are up and running TODO: the # of
     * invokers should not be hardcoded
     */
    @Test
    public void pingInvoker() throws IOException {
        for (int i = 0; i < WhiskProperties.numberOfInvokers(); i++) {
            PingTests.isAlive("invoker" + i, WhiskProperties.getFileRelativeToWhiskHome(".").getAbsolutePath());
        }
    }

    /**
     * Check that the loadbalancer endpoint is up and running
     */
    @Test
    public void pingLoadBalancer() throws IOException {
        PingTests.isAlive("loadbalancer", WhiskProperties.getFileRelativeToWhiskHome(".").getAbsolutePath());
    }

    /**
     * Check that the controller endpoint is up and running
     */
    @Test
    public void pingController() throws IOException {
        PingTests.isAlive("controller", WhiskProperties.getFileRelativeToWhiskHome(".").getAbsolutePath());
    }

    public static Pair<String, String> isAlive(String name, String whiskPropertyFile) throws IOException {
        return TestUtils.runCmd(TestUtils.SUCCESS_EXIT, bin, WhiskProperties.python, "isAlive", "-d", whiskPropertyFile, name);
    }
}
