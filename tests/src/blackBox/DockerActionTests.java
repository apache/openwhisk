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

package blackBox;

import static common.WskCli.Item.Action;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.logging.Logger;

import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jayway.restassured.RestAssured;
import com.jayway.restassured.config.RestAssuredConfig;
import com.jayway.restassured.specification.RequestSpecification;

import common.BasicLauncher;
import common.Pair;
import common.TestUtils;
import common.Util;
import common.WhiskProperties;
import common.WskCli;

/**
 * Basic tests of docker containers as actions.
 */
public class DockerActionTests {

    public final static String helloPythonImage = "sjfink/hellopython";

    @Rule
    public TestRule watcher = TestUtils.makeTestWatcher();

    private String makeContainerPayload(JsonElement payload) throws Exception {
        String tmp = "{\"value\":" + payload.toString() + "}";
        return new String(tmp.getBytes(), "UTF-8");
    }

    /**
     * Run some tests on the hellopython container
     */
    @Test(timeout = 120 * 1000)
    public void testHelloPython() throws Exception {
        // kill and remove the container (if for some reason it's still there)
        String containerName = "hellopython";
        try {
            // setup/start the hellopython container
            RequestSpecification request = setupContainer(containerName, helloPythonImage, true).snd;

            // TODO: Let it come up by using POST /init.
            // Don't use POST /state which is dead.
            Thread.sleep(5000);

            // use POST /run to invoke the action
            JsonObject rec = new JsonObject();
            rec.addProperty("payload", "Dolly");
            String body = makeContainerPayload(rec);
            String response = request.contentType("application/json").body(body).when().post("/run").asString();
            System.out.println(response);

            String logs = docker(true, "logs", containerName).fst;
            System.out.println(logs);
            assertTrue(logs.contains("Hello,  Dolly !"));
        } finally {
            // kill/rm the container
            docker(false, "kill", containerName);
            docker(false, "rm", containerName);
        }
    }

    /**
     * Demo sequence with the hello python container as an action.
     */
    @Ignore("/init protocol undergoing change")
    @Test(timeout = 240 * 1000)
    public void helloPythonDemo() throws Exception {
        WskCli wsk = new WskCli();
        // SJF -- reduced to one iteration for now
        for (int i = 0; i < 1; i++) {
            String name = "HELLO_PYTHON_" + i;
            try {
                wsk.sanitize(Action, name);
                String payload = "anaconda";
                String expected = String.format("Hello,  %s !", payload);
                String result = wsk.createDockerAction(name, helloPythonImage, false);
                assertTrue(result, result.contains("ok: created"));
                String activationId = wsk.invoke(name, TestUtils.makeParameter("payload", payload));
                boolean found = wsk.logsForActivationContain(activationId, expected, 80);
                // this could be different from last retrieval
                String logs = wsk.getLogsForActivation(activationId).stdout;
                assertTrue("Expected message '" + expected + "'not found in activation " + activationId + ":\n" + logs, found);
            } catch (Exception e) {
                System.out.println("Error during test sequence: " + e);
            } finally {
                wsk.sanitize(Action, name);
            }
        }
    }

    /*
     * Set up a container for direct testing.
     */
    public static Pair<String, RequestSpecification> setupContainer(String containerName, String imageName, boolean daemon) throws Exception {
        docker(false, "kill", containerName);
        docker(false, "rm", containerName);
        docker(false, "pull", containerName);
        String stdout = daemon ? docker(true, "run", "--name", containerName, "-d", imageName).fst : docker(true, "run", "--name", containerName, imageName).fst;

        // find the ip address of the container
        String out = docker(false, "inspect", "--format", "\"'{{ .NetworkSettings.IPAddress }}'\"", containerName).fst;
        String ipaddress = out.replaceAll("\"", "").replaceAll("'", "").trim();
        System.out.println("IP: " + ipaddress);

        // set up globals for rest-assured
        String baseURI = "http://" + ipaddress;
        int port = 8080;
        RestAssuredConfig config = new RestAssuredConfig().encoderConfig(new RestAssuredConfig().getEncoderConfig().defaultContentCharset("UTF-8"));
        return Pair.make(stdout, RestAssured.given().config(config).port(port).baseUri(baseURI));
    }

    /**
     * Run a command docker [params] where the arguments come in as an array.
     * Use this instead of the other form with quoting.
     *
     * @param checkExitCode
     *            if true, assert exit code is 0
     * @return <stdout,sterr>
     */
    public static Pair<String, String> docker(boolean checkExitCode, String... params) throws IllegalArgumentException, IOException {
        BasicLauncher bl = new BasicLauncher(true, true, Logger.getLogger("basic"));

        String dockerBin1 = "/usr/bin/docker";
        String dockerBin2 = "/usr/local/bin/docker";

        String dockerBin = null;

        if (new File(dockerBin1).isFile()) {
            dockerBin = dockerBin1;
        } else if (new File(dockerBin2).isFile()) {
            dockerBin = dockerBin2;
        }

        assertTrue("Couldn't locate docker executable in standard paths.", dockerBin != null);

        String[] cmd = new String[] { dockerBin };
        if (WhiskProperties.onMacOSX()) {
            cmd = Util.concat(cmd, new String[] { "--host", String.format("tcp://%s", WhiskProperties.getMainDockerEndpoint()) });
        }
        cmd = Util.concat(cmd, params);
        bl.setCmd(cmd);

        int exitCode = bl.launch();
        if (checkExitCode) {
            if (exitCode != 0) {
                // debug information
                System.out.println("stdout:\n " + new String(bl.getStdout()));
                System.out.println("stderr:\n " + new String(bl.getStderr()));
            }
            assertTrue("Exit code:" + exitCode, exitCode == 0);
        }
        return Pair.make(new String(bl.getStdout()), new String(bl.getStderr()));
    }

}
