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

package system.basic;

import static common.WskCli.Item.Action;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import com.google.code.tempusfugit.concurrency.ParallelRunner;

import common.Pair;
import common.TestUtils;
import common.TestUtils.RunResult;
import common.WskCli;

/**
 * Tests for rules using command line interface
 */
@RunWith(ParallelRunner.class)
public class CLIActionTests {
    private static final Boolean usePythonCLI = true;
    private static final WskCli wsk = new WskCli(usePythonCLI);

    public static final String[] sampleTestWords = new String[] { "SHERLOCK", "WATSON", "LESTRADE" };
    private static final int DEFAULT_WAIT = 100;  // Wait this long for logs to show up

    @BeforeClass
    public static void setUp() throws Exception {
    }

    /**
     * Test the hello world demo sequence.
     */
    @Test(timeout=120*1000)
    public void helloWorldDemo() throws Exception {
        long duration = helloWorldHelper(wsk, "helloWorldDemo", 2*DEFAULT_WAIT);
        assertTrue("Test took " + duration + "ms -- this is too slow.", duration < 2*DEFAULT_WAIT*1000);
    }

    /**
     * Test the hello world demo sequence
     */
    @Test(timeout=120*1000)
    public void helloWorldDemoWithSpace() throws Exception {
        long duration = helloWorldHelper(wsk, "hello World demo with space", 2*DEFAULT_WAIT);
        assertTrue("Test took " + duration + "ms -- this is too slow.", duration < 2*DEFAULT_WAIT*1000);
    }

    // Convenience method for when short test word vector is ok
    public static long helloWorldHelper(WskCli wsk, String actionName, int waitTime) throws Exception {
        return helloWorldHelper(wsk, actionName, waitTime, sampleTestWords, false);
    }

    /**
     * Static method extracted for reuse.
     *
     * @param wsk the WskCLI instance to use so we can select the auth
     * @param actionName name of the action to install and test
     * @param waitTime an upper bound on how long do we expect this test to take
     * @param testWords a list of testwords to use
     */
    public static long helloWorldHelper(WskCli wsk, String actionName, int waitTime,
                                        String[] testWords, boolean skipCheck) throws Exception {
        try {
            long start = System.currentTimeMillis();

            wsk.sanitize(Action, actionName);
            wsk.createAction(actionName, TestUtils.getCatalogFilename("samples/hello.js"));

            // keep track of invoke ids and use them to check the output of the activation
            List<String> activationIds = new ArrayList<String>();
            for (String tw : testWords) {
                String activationId = wsk.invoke(actionName, TestUtils.makeParameter("payload", tw+actionName));
                activationIds.add(activationId);
            }
            for (int i =0; i < activationIds.size(); i++) {
                String tw = testWords[i];
                String activationId = activationIds.get(i);
                String expected = tw+actionName;
                if (!wsk.logsForActivationContain(activationId, expected, waitTime)) {
                    if (skipCheck)
                        System.out.println("Did not find " + expected + " in activation " + activationId);
                    else
                        assertFalse("Did not find " + expected + " in activation " + activationId, true);
                }
            }
            return System.currentTimeMillis() - start;
        } finally {
            wsk.delete(Action, actionName);
        }
    }

    private static String[] testSentences = new String[] {
            "Mary had a little lamb, little lamb, little lamb, da.",
            "It was the best of times.  It was the worst of times.",
            "To be or not to be" };

    @Test(timeout=240*1000)
    public void twoAction() throws Exception {
        try {
            wsk.sanitize(Action, "twoAction1");
            wsk.sanitize(Action, "twoAction2");
            wsk.createAction("twoAction1", TestUtils.getCatalogFilename("samples/hello.js"));
            wsk.createAction("twoAction2", TestUtils.getCatalogFilename("samples/wc.js"));
            ArrayList<String> expected = new ArrayList<String>();
            ArrayList<String> activationIds = new ArrayList<String>();
            for (String s : testSentences) {
                String word = s.substring(0, s.indexOf(' '));
                String activationId = wsk.invoke("twoAction1", TestUtils.makeParameter("payload", word));
                expected.add(word);
                activationIds.add(activationId);
                activationId = wsk.invoke("twoAction2", TestUtils.makeParameter("payload", s));
                expected.add(s);
                activationIds.add(activationId);
            }
            for (int i = 0; i < expected.size(); i++) {
                String exp = expected.get(i);
                String activationId = activationIds.get(i);
                boolean present = wsk.logsForActivationContain(activationId, exp, DEFAULT_WAIT);
                assertTrue("Expected '" + exp + "' which is missing in log for activation " + activationId, present);
            }
        } finally {
            wsk.delete(Action, "twoAction1");
            wsk.delete(Action, "twoAction2");
        }
    }

    @Test(timeout=120*1000)
    public void invokeAction() throws Exception {
        String action = "invokeAction";
        try {
            wsk.sanitize(Action, action);
            wsk.createAction(action, TestUtils.getCatalogFilename("samples/wc.js"));
            String now = String.format("%s %s", action, new Date().toString());
            String activationId = wsk.invoke(action, TestUtils.makeParameter("payload", now));
            String expected = String.format("The message '%s' has", now);
            boolean present = wsk.logsForActivationContain(activationId, expected, DEFAULT_WAIT);
            assertTrue("Expected '" + expected + "' which is missing in log for activation " + activationId, present);
            String result = wsk.getResult(activationId).stdout.trim();
            assertTrue("Expected result for " + activationId + " to be non-empty but is '" + result + "'", result.contains("7"));
            assertTrue("Expected result to not contain error", !result.contains("error"));
        } finally {
            wsk.delete(Action, action);
        }
    }

    @Test(timeout=120*1000)
    public void incorrectActionInvoke() throws Exception {
        String payload = "bob";
        String[] cmd = { "action", "invoke", "/whisk.system/samples/helloWorld", payload };
        RunResult rr = wsk.runCmd(cmd);

        if (usePythonCLI) {
            assertTrue("Expect a cli error exit code", rr.exitCode == 2);
            assertTrue("Expect a cli usage message", rr.stderr.contains("usage: wsk [-h] [-v]"));
            assertTrue("Expect a cli error message", rr.stderr.contains("wsk: error: unrecognized arguments: " + payload));
        } else {
            assertTrue("Expect a cli error exit code", rr.exitCode == 1);
            assertTrue("Expect a cli usage message", rr.stderr.contains("Run 'wsk --help' for usage."));
            assertTrue("Expect a cli error message", rr.stderr.contains("error: Invalid argument list."));
        }
    }

    @Test(timeout=120*1000)
    public void invokeAsyncAction() throws Exception {
        String action = "invokeAsyncAction";
        try {
            wsk.sanitize(Action, action);
            wsk.createAction(action, TestUtils.getCatalogFilename("samples/helloAsync.js"));
            String now = String.format("%s %s", action, new Date().toString());
            String activationId = wsk.invoke(action, TestUtils.makeParameter("payload", now));
            String expected = String.format("The message '%s' has", now);
            boolean present = wsk.logsForActivationContain(activationId, expected, DEFAULT_WAIT);
            assertTrue("Expected '" + expected + "' which is missing in log for activation " + activationId, present);
            String result = wsk.getResult(activationId).stdout.trim();
            assertTrue("Expected result for " + activationId + " to be non-empty but is '" + result + "'", result.contains("7"));
            assertTrue("Expected result to not contain error but is '" + result + "'", !result.contains("error"));
        } finally {
            wsk.delete(Action, action);
        }
    }

    @Test(timeout=120*1000)
    public void invokeMonkeySyncDoneTwice() throws Exception {
        String action = "invokeMonkeySyncDoneTwice";
        try {
            wsk.sanitize(Action, action);
            wsk.createAction(action, TestUtils.getTestActionFilename("helloSyncDoneTwice.js"));
            String now = String.format("%s %s", action, new Date().toString());
            String activationId = wsk.invoke(action, TestUtils.makeParameter("payload", now));
            String expected = String.format("The message '%s' has", now);
            boolean present = wsk.logsForActivationContain(activationId, expected, DEFAULT_WAIT);
            assertTrue("Expected '" + expected + "' which is missing in log for activation " + activationId, present);
            String result = wsk.getResult(activationId).stdout.trim();
            assertTrue("Expected result for " + activationId + " to be non-empty but is '" + result + "'", result.contains("7"));
            assertTrue("Expected result to be positive", !result.contains("-1"));
            assertTrue("Expected result to not contain error", !result.contains("error"));
        } finally {
            wsk.delete(Action, action);
        }
    }

    @Test(timeout=120*1000)
    public void invokeMonkeyAsyncDoneTwice() throws Exception {
        String action = "invokeMonkeyAsyncDoneTwice";
        try {
            wsk.sanitize(Action, action);
            wsk.createAction(action, TestUtils.getTestActionFilename("helloAsyncDoneTwice.js"));
            String now = String.format("%s %s", action, new Date().toString());
            String activationId = wsk.invoke(action, TestUtils.makeParameter("payload", now));
            String expected = String.format("The message '%s' has", now);
            boolean present = wsk.logsForActivationContain(activationId, expected, DEFAULT_WAIT);
            assertTrue("Expected '" + expected + "' which is missing in log for activation " + activationId, present);
            String result = wsk.getResult(activationId).stdout.trim();
            assertTrue("Expected result of " + activationId + " to be non-empty but is '" + result + "'", result.contains("7"));
            assertTrue("Expected result to be positive", !result.contains("-1"));
            assertTrue("Expected result to not contain error", !result.contains("error"));
        } finally {
            wsk.delete(Action, action);
        }
    }

    @Test(timeout=120*1000)
    public void blockingInvokeAsyncAction() throws Exception {
        String action = "blockingInvokeAsyncAction";
        try {
            wsk.sanitize(Action, action);
            wsk.createAction(action, TestUtils.getCatalogFilename("samples/helloAsync.js"));
            String now = String.format("%s %s", action, new Date().toString(), 120*1000);
            int numWords = now.trim().split("\\s").length;
            Pair<String, String> pair = wsk.invokeBlocking(action, TestUtils.makeParameter("payload", now));
            String activationId = pair.fst;
            String result = pair.snd;
            String expected = String.format("The message '%s' has", now);
            boolean present = wsk.logsForActivationContain(activationId, expected, DEFAULT_WAIT);
            assertTrue("Expected '" + expected + "' which is missing in log for activation " + activationId, present);
            assertTrue("Result should have number of words", result.contains(String.valueOf(numWords)));
            assertTrue("Expected result to not contain error", !result.contains("error"));
        } finally {
            wsk.delete(Action, action);
        }
    }

    @Test(timeout=120*1000)
    public void invokeNestedBlockingAction() throws Exception {
        String parentAction = "nestedBlockingAction";
        String childAction = "wc";
        try {
            wsk.sanitize(Action, childAction);
            wsk.sanitize(Action, parentAction);
            wsk.createAction(childAction, TestUtils.getCatalogFilename("samples/wc.js"));
            wsk.createAction(parentAction, TestUtils.getCatalogFilename("samples/wcbin.js"), 120*1000);
            String now = String.format("%s %s", parentAction, new Date().toString());
            int numWords = now.trim().split("\\s").length;
            String numWordsBinary = Integer.toBinaryString(numWords) + " (base 2)";
            Pair<String, String> pair = wsk.invokeBlocking(parentAction, TestUtils.makeParameter("payload", now));
            String activationId = pair.fst;
            String result = pair.snd;
            assertTrue("Result "+activationId+" should have number of words", result.contains(numWordsBinary));
            assertTrue("Expected result to not contain error", !result.contains("error"));
        } finally {
            wsk.delete(Action, childAction);
            wsk.delete(Action, parentAction);
        }
    }

    @Test(timeout=120*1000)
    public void copyAction() throws Exception {
        String action = "copyAction";
        try {
            wsk.sanitize(Action, action);
            wsk.copyAction(action, "/whisk.system/samples/wordCount");
            String now = String.format("%s %s", action, new Date().toString());
            String activationId = wsk.invoke(action, TestUtils.makeParameter("payload", now));
            String expected = String.format("The message '%s' has", now);
            boolean present = wsk.logsForActivationContain(activationId, expected, DEFAULT_WAIT);
            assertTrue("Expected '" + expected + "' which is missing in log for activation " + activationId, present);
            String result = wsk.getResult(activationId).stdout.trim();
            assertTrue("Expected result of " + activationId + " to be non-empty", result.contains("7"));
            assertTrue("Expected result to not contain error", !result.contains("error"));
        } finally {
            wsk.delete(Action, action);
        }
    }

    @Test(timeout=120*1000)
    public void updateAction() throws Exception {
        String action = "updateAction";
        String publishFalseStr = "\"publish\": false";
        String publishTrueStr = "\"publish\": true";
        try {
            wsk.sanitize(Action, action);
            // create private action then update it to public
            wsk.createAction(action, TestUtils.getCatalogFilename("samples/wc.js"), false, false);
            String item = wsk.get(Action, action);
            assertTrue("Expect action to not be shared", item.contains(publishFalseStr));
            wsk.updateAction(action, true);
            item = wsk.get(Action, action);
            assertTrue("Expect action to be shared", item.contains(publishTrueStr));
        } finally {
            wsk.delete(Action, action);
        }
    }

    @Test(timeout=120*1000)
    public void invokeActionWithSpace() throws Exception {
        String name = "WORD COUNT";
        try {
            wsk.sanitize(Action, name);
            wsk.createAction(name, TestUtils.getCatalogFilename("samples/wc.js"));
            String activationId = wsk.invoke(name, TestUtils.makeParameter("payload", "bob barker"));
            String expected = "The message 'bob barker' has";
            boolean present = wsk.logsForActivationContain(activationId, expected, DEFAULT_WAIT);
            assertTrue("Expected '" + expected + "' which is missing in log for activation " + activationId, present);
        } finally {
            wsk.delete(Action, name);
        }
    }

    @Test(timeout=120*1000)
    public void parameterBinding() throws Exception {
        try {
            wsk.sanitize(Action, "PB_PRINT");
            @SuppressWarnings("serial")
            Map<String, String> boundParams = new HashMap<String, String>() {{
                put("bar", "test1");
                put("foo", "test2");
            }};
            wsk.createAction("PB_PRINT", TestUtils.getCatalogFilename("samples/printParams.js"), boundParams);

            // Check if bound parameters get passed to action.
            String now = new Date().toString();
            String activationId = wsk.invoke("PB_PRINT", TestUtils.makeParameter("payload", now));
            String expected = String.format(".*bar: test1.*foo: test2.*payload: %s", now);
            boolean present = wsk.logsForActivationContain(activationId, expected, DEFAULT_WAIT);
            assertTrue("Expected '" + expected + "' which is missing in log for activation " + activationId, present);

            // Check if run time parameter overrides bound parameter.
            activationId = wsk.invoke("PB_PRINT", TestUtils.makeParameter("foo", now));
            expected = String.format(".*bar: test1.*foo: %s", now);
            present = wsk.logsForActivationContain(activationId, expected, DEFAULT_WAIT);
            assertTrue("Expected '" + expected + "' which is missing in log for activation " + activationId, present);
        } finally {
            wsk.delete(Action, "PB_PRINT");
        }
    }

    @Test(timeout=240*1000)
    public void recreateAndInvokeAction() throws Exception {
      String action = "recreateAction";
      try {
          String now = String.format("%s %s", action, new Date().toString());
          String expectedFirstResult = String.format("The message '%s' has", now);
          String expectedSecondResult = String.format("hello %s!", now);

          wsk.sanitize(Action, action);
          wsk.createAction(action, TestUtils.getCatalogFilename("samples/wc.js"));
          String activationId = wsk.invoke(action, TestUtils.makeParameter("payload", now));
          boolean present = wsk.logsForActivationContain(activationId, expectedFirstResult, DEFAULT_WAIT);
          assertTrue("Expected '" + expectedFirstResult + "' which is missing in log for activation " + activationId, present);

          String result = wsk.getResult(activationId).stdout.trim();
          assertTrue("Expected result for " + activationId + " to be non-empty but is '" + result + "'", result.contains("7"));
          assertTrue("Expected result to not contain error", !result.contains("error"));

          wsk.delete(Action, action);
          wsk.createAction(action, TestUtils.getCatalogFilename("samples/hello.js"));

          activationId = wsk.invoke(action, TestUtils.makeParameter("payload", now));
          present = wsk.logsForActivationContain(activationId, expectedFirstResult, DEFAULT_WAIT);
          assertTrue("Unexpected '" + expectedFirstResult + "' which is in log for activation " + activationId, !present);
          present = wsk.logsForActivationContain(activationId, expectedSecondResult, DEFAULT_WAIT);
          assertTrue("Expected '" + expectedSecondResult + "' which is missing in log for activation " + activationId, present);
      } finally {
          wsk.delete(Action, action);
      }
    }

    @Ignore
    @Test(timeout=120*1000)
    public void invokeActionWithSpecialCharacters() throws Exception {
        String action = "invokeActionWithUTF8";
        try {
            wsk.sanitize(Action, action);
            wsk.createAction(action, TestUtils.getCatalogFilename("samples/hello.js"));
            String msg = "«ταБЬℓσö»: 1<2 & 4+1>³, now 20%€§$ off!";
            String expected = "hello " + msg;
            String activationId = wsk.invoke(action, TestUtils.makeParameter("payload", msg));
            boolean present = wsk.logsForActivationContain(activationId, expected, DEFAULT_WAIT);
            assertTrue("Expected '" + expected + "' which is missing in log for activation " + activationId, present);
        } finally {
            wsk.delete(Action, action);
        }
    }

    @Test(timeout = 120 * 1000)
    public void testPingNotAllowed() throws Exception {
        String action = "testPingNotAllowed";
        try {
            wsk.sanitize(Action, action);
            wsk.createAction(action, TestUtils.getTestActionFilename("ping.js"));
            String hostToPing = "google.com";
            String activationId = wsk.invoke(action, TestUtils.makeParameter("payload", hostToPing));
            String expected = "ping: icmp open socket: Operation not permitted";
            wsk.activationsContain(action, activationId, DEFAULT_WAIT);
            String result = wsk.getResult(activationId).stdout.trim();
            assertTrue("Expected result of " + activationId + " to be '" + expected + "' non-empty but is '" + result + "'", result.contains(expected));
        } finally {
            wsk.delete(Action, action);
        }
    }

    /**
     * Test creating an action from a js file always returning an application error.
     * The invocation should succeed, the activation result should contain an error as
     * defined in the action and the logs should be empty.
     */
    @Test
    public void applicationError() throws Exception {
        for(int i = 1; i <= 2; i++) {
            String actionName = "APPLICATIONERROR" + i;
            String result;

            wsk.sanitize(Action, actionName);
            result = wsk.createAction(actionName,  TestUtils.getTestActionFilename("applicationError" + i + ".js"));
            assertTrue(result, result.contains("ok: created"));

            String activationId = wsk.invoke(actionName, Collections.<String,String>emptyMap());

            boolean bool = wsk.checkResultFor(activationId, "error thrown on purpose", 45);
            assertTrue("Expected 'error' which is missing in result for activation " + activationId, bool);

            RunResult logs = wsk.getLogsForActivation(activationId);

            String stdout = logs.stdout.trim();
            String stderr = logs.stderr.trim();

            assertTrue("stdout should be empty, was:\n" + stdout, stdout.isEmpty());
            assertTrue("stderr should be empty, was:\n" + stderr, stderr.isEmpty());

            result = wsk.delete(Action, actionName);
            assertTrue(result, result.contains("ok: deleted"));
        }
    }
}
