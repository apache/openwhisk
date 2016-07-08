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

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.Collections;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.google.gson.JsonElement;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

import junit.runner.Version;

/**
 * Miscellaneous utilities used in whisk test suite
 */
public class TestUtils {
    protected static final Logger logger = Logger.getLogger("basic");

    public static final int SUCCESS_EXIT = 0;
    public static final int ERROR_EXIT = 1;
    public static final int MISUSE_EXIT = 2;
    public static final int ACCEPTED = 202;      // 202
    public static final int BAD_REQUEST = 144;   // 400 - 256 = 144
    public static final int UNAUTHORIZED = 145;  // 401 - 256 = 145
    public static final int FORBIDDEN = 147;     // 403 - 256 = 147
    public static final int NOT_FOUND = 148;     // 404 - 256 = 148
    public static final int NOTALLOWED = 149;    // 405 - 256 = 149
    public static final int CONFLICT = 153;      // 409 - 256 = 153
    public static final int THROTTLED = 173;     // 429 (TOO_MANY_REQUESTS) - 256 = 173
    public static final int TIMEOUT = 246;       // 502 (GATEWAY_TIMEOUT)   - 256 = 246
    public static final int DONTCARE_EXIT = -1;  // any value is ok
    public static final int ANY_ERROR_EXIT = -2; // any non-zero value is ok

    private static final File catalogDir = WhiskProperties.getFileRelativeToWhiskHome("catalog");
    private static final File testActionsDir = WhiskProperties.getFileRelativeToWhiskHome("tests/dat/actions");
    private static final File vcapFile = WhiskProperties.getVCAPServicesFile();
    private static final String envServices = System.getenv("VCAP_SERVICES");


    static {
        logger.setLevel(Level.WARNING);
        System.out.println("JUnit version is: " + Version.id());
    }

    /**
     * Gets path to file relative to catalog directory.
     *
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
     * Gets the value of VCAP_SERVICES
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

    /* Gets a VCAP_SERVICES credentials
     * @return VCAP credentials as a <String, String> map for each <property, value> pair in credentials
     */
    public static Map<String, String> getVCAPcredentials(String vcapService) {
        try {
            JsonArray vcapArray = getVCAPServices().get(vcapService).getAsJsonArray();
            JsonObject vcapObject = vcapArray.get(0).getAsJsonObject();
            JsonObject credentials = vcapObject.get("credentials").getAsJsonObject();
            Map<String,String> map = new HashMap<String,String>();
            for(Map.Entry<String, JsonElement> entry : credentials.entrySet()) {
                map.put(entry.getKey(), credentials.get(entry.getKey()).getAsString());
            }

            return map;
        } catch (Throwable t) {
            System.out.println("failed to parse VCAP" + t);
            return Collections.emptyMap();
        }
    }

    /**
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
     * Sets the number of concurrent tests to run based on system property if it
     * is defined.
     */
    public static void setForkJoinConcurrency() {
        int count = WhiskProperties.concurrentTestCount;
        if (count > 0) {
            setForkJoinConcurrency(count);
        }
    }

    /**
     * Sets the number of concurrent tests to run based on value provided.
     * @throws IllegalStateException if count < 1
     */
    public static void setForkJoinConcurrency(int count) {
        if (count > 0) {
            System.out.format("concurrent test threads %d\n", count);
            System.setProperty("java.util.concurrent.ForkJoinPool.common.parallelism", Integer.toString(count));
        } else throw new IllegalStateException("test thread count must be positive");
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

    public static int sleep(int secs) {
        if (secs > 0) try {
            Thread.sleep(secs*1000);
        } catch (InterruptedException e) {}
        return secs;
    }

    public static interface Once<T> {
        /**
         * a method that returns a T when some condition is satisfied,
         * and otherwise returns null.
         *
         * The intention is that this will be called until satisfied once, and then no more.
         */
        T once() throws IOException;
    }

    /**
     * wait up to totalWait seconds for a 'step' to return value.
     */
    public static boolean waitfor(Once<Boolean> step, int totalWait) throws IOException {
        Boolean result = waitfor(step, 0, 1, totalWait);
        return result != null && result.booleanValue();
    }

    /**
     * wait up to totalWait seconds for a 'step' to return value.
     */
    public static <T> T waitfor(Once<T> step, int initialWait, int pollPeriod, int totalWait) throws IOException {
        // Often tests call this routine immediately after starting work.
        // Perform an initial wait before hitting the log for the first time.
        long endTime = System.currentTimeMillis() + totalWait * 1000;
        sleep(initialWait);
        while (System.currentTimeMillis() < endTime) {
            T satisfied = step.once();
            if (satisfied != null && !(satisfied instanceof Boolean)) {
                return satisfied;
            } else if (satisfied != null && (Boolean) satisfied == true) {
                return satisfied;
            } else if (System.currentTimeMillis() >= endTime) {
                // Make sure we are prompt for the no wait case.
                break;
            } else {
                sleep(pollPeriod);
            }
        }
        return null;
    }

    @SafeVarargs
    public static Map<String, String> makeParameter(Pair<String, String>... params) {
        Map<String, String> map = new HashMap<String, String>();
        if (params != null) {
            for (Pair<String, String> p : params) {
                if (p != null && p.fst != null)
                    map.put(p.fst, p.snd);
            }
        }
        return map;
    }

    public static Map<String, String> makeParameter(String name, String value) {
        return makeParameter(Pair.make(name, value));
    }

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

    public static RunResult runCmd(File dir, String... params) throws IllegalArgumentException, IOException {
        return runCmd(TestUtils.DONTCARE_EXIT, dir, logger, null, params);
    }

    public static Pair<String, String> runCmd(int expectedExitCode, File dir, String... params) throws IllegalArgumentException, IOException {
        return runCmd(expectedExitCode, dir, logger, null, params).logs();
    }

    public static Pair<String, String> runQuietly(int expectedExitCode, File dir, String... params) throws IllegalArgumentException, IOException {
        return runCmd(expectedExitCode, dir, null, null, params).logs();
    }

    /*
     * Run with no timeout.
     */
    public static RunResult runCmd(int expectedExitCode, File dir, Logger logger,
                                   Map<String, String> env, String... params) throws IllegalArgumentException, IOException {
        return runCmd(expectedExitCode, 0, dir, logger, env, params);
    }

    /**
     * Run a command in another process (exec())
     *
     * @param expectedExitCode the exit code expected from the command when it exists
     * @param timeoutMilli kill the underlying process after this amount of time (0 if no timeout)
     * @param dir the working directory the command runs with
     * @param logger object to manage logging message
     * @param env TODO
     * @param params parameters to pass on the command line to the spawnded command
     * @return
     */
    public static RunResult runCmd(int expectedExitCode, int timeoutMilli, File dir, Logger logger,
                                   Map<String, String> env, String... params) throws IllegalArgumentException, IOException {

        BasicLauncher bl = new BasicLauncher(true, true, logger);
        bl.setCmd(params);
        bl.setWorkingDir(dir);
        bl.setEnv(env);

        int exitCode = bl.launch(timeoutMilli);
        RunResult rr = new RunResult(exitCode, new String(bl.getStdout()), new String(bl.getStderr()));
        rr.validateExitCode(expectedExitCode);
        return rr;
    }
}
