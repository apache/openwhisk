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

import static common.TestUtils.ANY_ERROR_EXIT;
import static common.TestUtils.BAD_REQUEST;
import static common.TestUtils.CONFLICT;
import static common.TestUtils.MISUSE_EXIT;
import static common.TestUtils.NOTALLOWED;
import static common.TestUtils.SUCCESS_EXIT;
import static common.TestUtils.UNAUTHORIZED;
import static common.WskCli.Item.Action;
import static common.WskCli.Item.Rule;
import static common.WskCli.Item.Trigger;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashMap;

import org.apache.commons.io.FileUtils;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;

import com.google.code.tempusfugit.concurrency.ParallelRunner;

import common.TestUtils;
import common.TestUtils.RunResult;
import common.WskCli;

/**
 * Basic tests of the command line interface
 */
@RunWith(ParallelRunner.class)
public class CLIBasicTests {

    private static final WskCli wsk = new WskCli();
    private static File wskProps = new File(System.getProperty("user.home") + "/.wskprops");
    private static File tmpProps = null;

    @Rule
    public TestRule watcher = TestUtils.makeTestWatcher();

    @BeforeClass
    public static void saveWskProps() throws IOException {
        if (wskProps.exists()) {
            tmpProps = File.createTempFile("wskProps", ".tmp");
            Files.copy(wskProps.toPath(), tmpProps.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @AfterClass
    public static void restoreWskProps() throws IOException {
        if (tmpProps != null && tmpProps.exists()) {
            Files.copy(tmpProps.toPath(), wskProps.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /*@Before
    public void cleanWskProps() throws IOException {
        Files.deleteIfExists(wskProps.toPath());
    }*/

    /**
     * Make sure the binary cli file exists
     */
    @Test
    public void testCLIBinary() throws Exception {
        wsk.checkExists();
    }

    /**
     * Test adding the same action twice with different cases.
     */
    @Test
    public void createTwiceCaseSensitive() throws Exception {
        wsk.sanitize(Action, "TWICE");
        wsk.sanitize(Action, "twice");
        try {
            String result = wsk.createAction("TWICE", TestUtils.getCatalogFilename("samples/hello.js"));
            assertTrue(result, result.contains("ok: created"));

            result = wsk.createAction("twice", TestUtils.getCatalogFilename("samples/hello.js"), null, false, false);
            assertTrue(result, result.contains("ok: created"));
        } finally {
            wsk.delete(Action, "TWICE");
            wsk.delete(Action, "twice");
        }
    }

    /**
     * Test adding the same action twice, forcing the second to update.
     */
    @Test
    public void createAndUpdate() throws Exception {
        String name = "createAndUpdate";
        wsk.sanitize(Action, name);
        try {
            String result = wsk.createAction(name, TestUtils.getCatalogFilename("samples/hello.js"));
            assertTrue(result, result.contains("ok: created"));

            result = wsk.createAction(name, TestUtils.getCatalogFilename("samples/hello.js"), true, false);
            assertTrue(result, result.contains("ok: updated"));
        } finally {
            wsk.delete(Action, name);
        }
    }

    /**
     * Test deleting something that doesn't exist. this should fail.
     */
    @Test
    public void deleteFantasy() throws Exception {
        String result = wsk.sanitize(Action, "deleteFantasy");
        assertTrue(result, result.contains("error: The requested resource does not exist."));
    }

    /**
     * Test creating an action from a file that doesn't exist. this should fail.
     */
    @Test
    public void missingFile() throws Exception {
        String result = wsk.createAction(MISUSE_EXIT, "missingFile", "notfound", null, false, false);
        assertTrue(result, result.contains("not a valid file"));
    }

    static String[] testSentences = new String[] {
        "Mary had a little lamb, little lamb, little lamb, da.",
        "It was the best of times.  It was the worst of times.",
        "To be or not to be" };

    /**
     * Test the help message
     */
    @Test
    public void help() throws Exception {
        String result = wsk.cli("-h").stdout;
        System.out.println("result:" + result);
        assertTrue(result, result.contains("optional arguments"));
        assertTrue(result, result.contains("available commands"));
        assertTrue(result, result.contains("-help"));
        assertTrue(result, result.contains("usage:"));
    }

    /**
     * Test the version message
     */
    @Test
    public void cliversion() throws Exception {
        String result = wsk.cli(SUCCESS_EXIT, "property", "get", "--cliversion").stdout;
        System.out.println("result:" + result);
        assertTrue(result, result.matches("whisk CLI version\\s+201.*\n"));
    }

    @Test
    public void apiversion() throws Exception {
        String result = wsk.cli(SUCCESS_EXIT, "property", "get", "--apiversion").stdout;
        System.out.println("result: " + result);
        assertTrue(result, result.matches("whisk API version\\s+v1\n"));
    }

    @Test
    public void apibuild() throws Exception {
        String result = wsk.cli(SUCCESS_EXIT, "property", "get", "--apibuild").stdout;
        System.out.println("result:" + result);
        assertTrue(result, result.matches("whisk API build*.*201.*\n"));
    }

    /**
     * Test updating of .wskprops
     */
    @Test
    public void auth() throws Exception {
        WskCli envCli = new WskCli();
        HashMap<String, String> env = new HashMap<String, String>();

        File wskprops = File.createTempFile("wskprops", ".tmp");
        env.put("WSK_CONFIG_FILE", wskprops.getAbsolutePath());
        envCli.setEnv(env);
        envCli.cli("property", "set", "--auth", "testKey");

        String fileContent = FileUtils.readFileToString(wskprops);
        assert(fileContent.contains("AUTH=testKey"));

        wskprops.delete();
    }

    /**
     * Test wsk list shared packages
     */
    @Test
    public void listSharedPackages() throws Exception {
        String result = wsk.cli(SUCCESS_EXIT, "package", "list", "/whisk.system", "--auth", wsk.authKey).stdout;
        assertStandardActions(result, "^.*/whisk.system/samples\\s+shared$", "^.*/whisk.system/util\\s+shared$");
    }

    /**
     * Test wsk list actions in shared packages
     */
    @Test
    public void listSharedActions() throws Exception {
        String result = wsk.cli(SUCCESS_EXIT, "action", "list", "/whisk.system/util", "--auth", wsk.authKey).stdout;
        assertStandardActions(result, "^.*/whisk.system/util/head\\s+shared$", "^.*/whisk.system/util/date\\s+shared$");
    }

    private void assertStandardActions(String result, String... matchTo) {
        String[] items = result.split("\n");
        int matched = 0;
        for (String item : items) {
            item = item.trim();
            if (item.isEmpty()) continue;
            for (String m: matchTo) {
                if (item.matches(m)) matched++;
            }
        }
        assertTrue(result, matched == matchTo.length);
    }

    /**
     * Test wsk list trigger
     */
    @Test
    public void listTriggers() throws Exception {
        String trigger = "listTriggers";
        try {
            wsk.sanitize(Trigger, trigger);
            wsk.createTrigger(trigger);
            String result = wsk.list(Trigger);
            assertTrue(result, result.contains(trigger));
        } finally {
            wsk.delete(Trigger, trigger);
        }
    }

    /**
     * Test wsk list rule
     */
    @Test
    public void listRules() throws Exception {
        String rule = "listRules";
        String trigger = "listRulesTrigger";
        String action = "listRulesAction";
        try {
            wsk.sanitize(Action, action);
            wsk.sanitize(Trigger, trigger);
            wsk.sanitize(Rule, rule);

            wsk.createAction(action, TestUtils.getCatalogFilename("samples/wc.js"));
            wsk.createTrigger(trigger);
            wsk.createRule(rule, trigger, action);

            String result = wsk.list(Rule);
            assertTrue(result, result.contains(rule));
        } finally {
            wsk.delete(Action, action);
            wsk.delete(Trigger, trigger);
            wsk.delete(Rule, rule);
        }
    }

    /**
     * Test wsk get action
     */
    @Test
    public void getAction() throws Exception {
        String result = wsk.get(Action, "/whisk.system/samples/wordCount");
        assertTrue(result, result.contains("words"));
    }

    /**
     * Test wsk get trigger
     */
    @Test
    public void getTrigger() throws Exception {
        String trigger = "getTrigger";
        try {
            wsk.sanitize(Trigger, trigger);
            wsk.createTrigger(trigger);
            String result = wsk.get(Trigger, trigger);
            assertTrue(result, result.contains(trigger));
        } finally {
            wsk.delete(Trigger, trigger);
        }
    }

    /**
     * Test wsk get rule
     */
    @Test
    public void getRule() throws Exception {
        String rule = "getRule";
        String trigger = "getRuleTrigger";
        String action = "getRuleAction";
        try {
            wsk.sanitize(Action, action);
            wsk.sanitize(Trigger, trigger);
            wsk.sanitize(Rule, rule);

            wsk.createAction(action, TestUtils.getCatalogFilename("samples/wc.js"));
            wsk.createTrigger(trigger);
            wsk.createRule(rule, trigger, action);

            String result = wsk.get(Rule, rule);
            assertTrue(result, result.contains(rule));
            assertTrue(result, result.contains(trigger));
            assertTrue(result, result.contains(action));
        } finally {
            wsk.delete(Action, action);
            wsk.delete(Trigger, trigger);
            wsk.delete(Rule, rule);
        }
    }

    /**
     * Test wsk duplicate create
     */
    @Test
    public void testDuplicateCreate() throws Exception {
        String name = "testDuplicateCreate";
        try {
            wsk.sanitize(Trigger, name);
            wsk.sanitize(Action, name);
            wsk.createTrigger(name);
            wsk.createAction(CONFLICT, name, TestUtils.getCatalogFilename("samples/wc.js"));
        } finally {
            wsk.delete(Trigger, name);
            wsk.sanitize(Action, name);
        }
    }

    /**
     * Test wsk cross delete
     */
    @Test
    public void testCrossDelete() throws Exception {
        String name = "testCrossDelete";
        try {
            wsk.sanitize(Trigger, name);
            wsk.createTrigger(name);
            wsk.delete(ANY_ERROR_EXIT, Action, name);
        } finally {
            wsk.delete(Trigger, name);
        }
    }

    /**
     * Test auto update of action version
     */
    @Test
    public void testAutoUpdateVersion() throws Exception {
        String name = "testAutoUpdateVersion";
        try {
            wsk.sanitize(Action, name);
            wsk.createAction(name, TestUtils.getCatalogFilename("samples/wc.js"));
            String result = wsk.get(Action, name);
            assertTrue(result, result.contains("\"publish\": false"));
            assertTrue(result, result.contains("0.0.1"));
            wsk.createAction(name, TestUtils.getCatalogFilename("samples/wc.js"), true, true);
            result = wsk.get(Action, name);
            assertTrue(result, result.contains("\"publish\": true"));
            assertTrue(result, result.contains("0.0.2"));
        } finally {
            wsk.delete(Action, name);
        }
    }

    /**
     * Test reject name
     */
    @Test
    public void testRejectCreateName() throws Exception {
        try {
            wsk.sanitize(Action, " ");
            wsk.sanitize(Rule, " ");
            wsk.sanitize(Trigger, "");
            wsk.sanitize(Action, "hi+there");
            wsk.sanitize(Rule, "$hola");
            wsk.sanitize(Trigger, "dora?");
            wsk.sanitize(Trigger, "|dora|&boots?");

            wsk.createAction(BAD_REQUEST, " ",TestUtils.getCatalogFilename("samples/wc.js"), null, false, false);
            wsk.createRule(BAD_REQUEST, " ", "t", "a");
            wsk.createTrigger(NOTALLOWED, "");
            wsk.createAction(BAD_REQUEST, "hi+there", TestUtils.getCatalogFilename("samples/wc.js"), null, false, false);
            wsk.createRule(BAD_REQUEST, "$hola", "t", "a");
            wsk.createTrigger(BAD_REQUEST, "dora?");
            wsk.createTrigger(BAD_REQUEST, "|dora|&boots?");
        } finally {
            wsk.sanitize(Action, " ");
            wsk.sanitize(Rule, " ");
            wsk.sanitize(Trigger, "");
            wsk.sanitize(Action, "hi+there");
            wsk.sanitize(Rule, "$hola");
            wsk.sanitize(Trigger, "dora?");
            wsk.sanitize(Trigger, "|dora|&boots?");
        }
    }

    /**
     * Test wsk list with a bad auth key
     */
    @Test
    public void listUnauthorized() throws Exception {
        String result = wsk.cli(UNAUTHORIZED, "namespace", "get", "--auth", wsk.authKey + "XXX").stdout;
        assertTrue(result, result.contains("The supplied authentication is invalid"));
    }

    /**
     * Test wsk namespace
     */
    @Test
    public void namespace() throws Exception {
        String result = wsk.cli(SUCCESS_EXIT, "namespace", "get", "--auth", wsk.authKey).stdout;
        assertTrue(result, result.contains("default"));
    }

    /**
     * Test wsk namespace with a bad auth key
     */
    @Test
    public void namespaceUnauthorized() throws Exception {
        String result = wsk.cli(UNAUTHORIZED, "namespace", "list", "--auth", wsk.authKey + "XXX").stdout;
        assertTrue(result, result.contains("The supplied authentication is invalid"));
    }

    /**
     * Test wsk bogus
     */
    @Test
    public void bogus() throws Exception {
        String err = wsk.cli(MISUSE_EXIT, "bogus").stderr;
        assertTrue(err, err.contains("usage:"));
    }

    /**
     * Test creating an action from an empty js file. This should fail with
     * empty exec/malformd exec field.
     */
    @Ignore("why should we disallow this? what's the difference between creating a text with empty exec or with ';' or '0' as the body?")
    @Test
    public void empty() throws Exception {
        wsk.sanitize(Action, "EMPTY");

        String result = wsk.createAction(BAD_REQUEST, "EMPTY", TestUtils.getTestActionFilename("empty.js"));
        assertTrue(result, result.contains("exec malformed"));
    }

    /**
     * Test creating an action from a malformed js file. This should fail in
     * some way - preferably when trying to create the action. If not, then
     * surely when it runs there should be some indication in the logs. Don't
     * think this is true currently.
     */
    @Test
    public void malformed() throws Exception {
        String actionName = "MALFORMED";
        wsk.sanitize(Action, actionName);


        String result = wsk.createAction(actionName, TestUtils.getTestActionFilename("malformed.js"));
        assertTrue(result, result.contains("ok: created"));

        // Improve the error message and the check here
        String expected = "ReferenceError";  // Something representing node giving an error when given malformed.js
        String activationId = wsk.invoke(actionName, TestUtils.makeParameter("payload", "whatever"));
        boolean pass1 = wsk.logsForActivationContain(activationId, expected, 45);
        boolean pass2 = wsk.checkResultFor(activationId, "error", 45);

        String logs = wsk.getLogsForActivation(activationId).stdout;
        System.out.println("malformed log: " + logs);
        assertTrue("Did not find " + expected + " in activation " + activationId + " log", pass1);
        assertTrue("Did not find 'error' in activation " + activationId + " result", pass2);

        result = wsk.delete(Action, actionName);
        assertTrue(result, result.contains("ok: deleted"));
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
            assertTrue("result must contain 'error'.", bool);

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

