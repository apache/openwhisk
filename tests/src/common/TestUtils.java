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

package common;

import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import junit.runner.Version;

/**
 * Miscellaneous utilities used in whisk test suite
 */
public class TestUtils {
    protected static final Logger logger = Logger.getLogger("basic");

    public static final int SUCCESS_EXIT   = 0;
    public static final int ERROR_EXIT     = 1;
    public static final int MISUSE_EXIT    = 2;
    public static final int DONTCARE_EXIT  = -1;  // any value is ok
    public static final int ANY_ERROR_EXIT = -2;  // any non-zero value is ok

    public static final int ACCEPTED       = 202; // 202
    public static final int BAD_REQUEST    = 144; // 400 - 256 = 144
    public static final int UNAUTHORIZED   = 145; // 401 - 256 = 145
    public static final int FORBIDDEN      = 147; // 403 - 256 = 147
    public static final int NOT_FOUND      = 148; // 404 - 256 = 148
    public static final int NOT_ALLOWED    = 149; // 405 - 256 = 149
    public static final int CONFLICT       = 153; // 409 - 256 = 153
    public static final int TOO_LARGE      = 157; // 413 - 256 = 157
    public static final int THROTTLED      = 173; // 429 (TOO_MANY_REQUESTS) - 256 = 173
    public static final int TIMEOUT        = 246; // 502 (GATEWAY_TIMEOUT) - 256 = 246

    private static final File catalogDir = WhiskProperties.getFileRelativeToWhiskHome("catalog");
    private static final File testActionsDir = WhiskProperties.getFileRelativeToWhiskHome("tests/dat/actions");
    private static final File vcapFile = WhiskProperties.getVCAPServicesFile();
    private static final String envServices = System.getenv("VCAP_SERVICES");
    private static final String loggerLevel = System.getProperty("LOG_LEVEL", Level.WARNING.toString());

    static {
        logger.setLevel(Level.parse(loggerLevel.trim()));
        System.out.println("JUnit version is: " + Version.id());
    }

    /**
     * Gets path to file relative to catalog directory.
     *
     * (@)deprecated this method will be removed in future version; use {@link #getTestActionFilename()} instead
     * @param name relative filename
     */
    public static String getCatalogFilename(String name) {
        return new File(catalogDir, name).toString();
    }

    /**
     * Gets path to test action file relative to test catalog directory.
     *
     * @param name the filename of the test action
     * @return
     */
    public static String getTestActionFilename(String name) {
        return new File(testActionsDir, name).toString();
    }

    /**
     * Gets the value of VCAP_SERVICES.
     *
     * @return VCAP_SERVICES as a JSON object
     */
    public static JsonObject getVCAPServices() {
        try {
            if (envServices != null) {
                return new JsonParser().parse(envServices).getAsJsonObject();
            } else {
                return new JsonParser().parse(new FileReader(vcapFile)).getAsJsonObject();
            }
        } catch (Throwable t) {
            System.out.println("failed to parse VCAP" + t);
            return new JsonObject();
        }
    }

    /**
     * Gets a VCAP_SERVICES credentials.
     *
     * @return VCAP credentials as a <String, String> map for each <property,
     * value> pair in credentials
     */
    public static Map<String, String> getVCAPcredentials(String vcapService) {
        try {
            JsonArray vcapArray = getVCAPServices().get(vcapService).getAsJsonArray();
            JsonObject vcapObject = vcapArray.get(0).getAsJsonObject();
            JsonObject credentials = vcapObject.get("credentials").getAsJsonObject();
            Map<String, String> map = new HashMap<String, String>();
            for (Map.Entry<String, JsonElement> entry : credentials.entrySet()) {
                map.put(entry.getKey(), credentials.get(entry.getKey()).getAsString());
            }

            return map;
        } catch (Throwable t) {
            System.out.println("failed to parse VCAP" + t);
            return Collections.emptyMap();
        }
    }

    /**
     * Creates a JUnit test watcher. Use with @rule.
     * @return a junit {@link TestWatcher} that prints a message when each test starts and ends
     */
    public static TestWatcher makeTestWatcher() {
        return new TestWatcher() {
            protected void starting(Description description) {
                System.out.format("\nStarting test %s at %s\n", description.getMethodName(), getDateTime());
            }

            protected void finished(Description description) {
                System.out.format("Finished test %s at %s\n\n", description.getMethodName(), getDateTime());
            }
        };
    }

    /**
     * @return a formatted string representing the date and time
     */
    public static String getDateTime() {
        Date date = new Date();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        return sdf.format(date);
    }

    /**
     * @return a formatted string representing the time of day
     */
    public static String getTime() {
        Date date = new Date();
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS");
        return sdf.format(date);
    }

    /**
     * Encapsulates the result of running a native command, providing:
     * exitCode the exit code of the process
     * stdout the messages printed to standard out
     * stderr the messages printed to standard error
     */
    public static class RunResult {
        public final int exitCode;
        public final String stdout;
        public final String stderr;

        private RunResult(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        public Pair<String, String> logs() {
            return Pair.make(stdout, stderr);
        }

        public void validateExitCode(int expectedExitCode) {
            if (expectedExitCode == TestUtils.DONTCARE_EXIT)
                return;
            boolean ok = (exitCode == expectedExitCode) || (expectedExitCode == TestUtils.ANY_ERROR_EXIT && exitCode != 0);
            if (!ok) {
                System.out.format("expected exit code = %d\n%s", expectedExitCode, toString());
                assertTrue("Exit code:" + exitCode, exitCode == expectedExitCode);
            }
        }

        @Override
        public String toString() {
            StringBuilder fmt = new StringBuilder();
            fmt.append(String.format("exit code = %d\n", exitCode));
            fmt.append(String.format("stdout: %s\n", stdout));
            fmt.append(String.format("stderr: %s\n", stderr));
            return fmt.toString();
        }
    }

    /**
     * Runs a command in another process.
     *
     * @param expectedExitCode the expected exit code for the process
     * @param dir the working directory the command runs with
     * @param params the parameters (including executable) to run
     * @return RunResult instance
     * @throws IOException
     */
    public static RunResult runCmd(int expectedExitCode, File dir, String... params) throws IOException {
        return runCmd(expectedExitCode, dir, logger, null, params);
    }

    /**
     * Runs a command in another process.
     *
     * @param expectedExitCode the exit code expected from the command when it exists
     * @param dir the working directory the command runs with
     * @param logger the object to manage logging message
     * @param env an environment map
     * @param params the parameters (including executable) to run
     * @return RunResult instance
     * @throws IOException
     */
    public static RunResult runCmd(int expectedExitCode, File dir, Logger logger, Map<String, String> env, String... params) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(params);
        pb.directory(dir);
        if (env != null) {
            pb.environment().putAll(env);
        }
        Process p = pb.start();

        String stdout = inputStreamToString(p.getInputStream());
        String stderr = inputStreamToString(p.getErrorStream());

        try {
            p.waitFor();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        RunResult rr = new RunResult(p.exitValue(), stdout, stderr);
        if (logger != null) {
            logger.info("RunResult: " + rr);
        }
        rr.validateExitCode(expectedExitCode);
        return rr;
    }

    private static String inputStreamToString(InputStream in) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        StringBuilder builder = new StringBuilder();
        String line = null;
        while ((line = reader.readLine()) != null) {
            builder.append(line);
            builder.append(System.getProperty("line.separator"));
        }
        return builder.toString();
    }
}
