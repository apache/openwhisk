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

    static String[] testSentences = new String[] {
        "Mary had a little lamb, little lamb, little lamb, da.",
        "It was the best of times.  It was the worst of times.",
        "To be or not to be" };


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

