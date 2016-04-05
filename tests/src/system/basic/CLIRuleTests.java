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
import static common.WskCli.Item.Activation;
import static common.WskCli.Item.Rule;
import static common.WskCli.Item.Trigger;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import com.google.code.tempusfugit.concurrency.ParallelRunner;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import common.TestUtils;
import common.WskCli;

/**
 * Tests for rules using command line interface
 */
@RunWith(ParallelRunner.class)
public class CLIRuleTests {

    private static final WskCli wsk = new WskCli();
    private static final int RULE_DELAY = 30;
    private static final int DELAY = 90;
    // NEGATIVE_DELAY is used for tests when checking that something doesn't
    // show up in activations
    private static final int NEGATIVE_DELAY = 30;

    @BeforeClass
    public static void setUp() throws Exception {
    }

    /**
     * rule test with one trigger and one action
     */
    @Test
    public void rule1to1() throws Exception {
        try {
            wsk.sanitize(Action, "A_121");
            wsk.sanitize(Trigger, "T_121");
            wsk.sanitize(Rule, "R_121");

            wsk.createAction("A_121", TestUtils.getCatalogFilename("samples/wc.js"));
            wsk.createTrigger("T_121");
            wsk.createRule("R_121", "T_121", "A_121");
            wsk.trigger("T_121", "bob 121");

            String expected = "The message 'bob 121' has";
            List<String> activationIds = wsk.waitForActivations("A_121", 1, DELAY);
            assertTrue("Not enough activation ids found", activationIds != null);
            // the most recent id
            String activationId = activationIds.get(0);
            assertTrue("Expected message not found: " + expected, wsk.logsForActivationContain(activationId, expected, DELAY));
        } finally {
            wsk.delete(Action, "A_121");
            wsk.delete(Trigger, "T_121");
            wsk.delete(Rule, "R_121");
        }
    }

    /**
     * rule test with two triggers and one action
     */
    @Test
    public void rule2to1() throws Exception {
        try {
            wsk.sanitize(Action, "A_221");
            wsk.sanitize(Trigger, "T1_221");
            wsk.sanitize(Trigger, "T2_221");
            wsk.sanitize(Rule, "R1_221");
            wsk.sanitize(Rule, "R2_221");

            wsk.createAction("A_221", TestUtils.getCatalogFilename("samples/wc.js"));
            wsk.createTrigger("T1_221");
            wsk.createTrigger("T2_221");
            wsk.createRule("R1_221", "T1_221", "A_221");
            wsk.createRule("R2_221", "T2_221", "A_221");

            wsk.trigger("T2_221", "i'll be back");
            wsk.trigger("T1_221", "terminator");

            String expected1 = "The message 'terminator' has";
            assertTrue("Expected message not found: " + expected1, wsk.logsForActionContain("A_221", expected1, DELAY));

            String expected2 = "The message 'i'll be back' has";
            assertTrue("Expected message not found: " + expected1, wsk.logsForActionContain("A_221", expected2, DELAY));

        } finally {
            wsk.delete(Action, "A_221");
            wsk.delete(Trigger, "T1_221");
            wsk.delete(Trigger, "T2_221");
            wsk.delete(Rule, "R1_221");
            wsk.delete(Rule, "R2_221");
        }
    }

    /**
     * rule test with one trigger and two actions
     */
    @Test
    public void rule1to2() throws Exception {
        try {
            wsk.sanitize(Action, "A1_122");
            wsk.sanitize(Action, "A2_122");
            wsk.sanitize(Trigger, "T1_122");
            wsk.sanitize(Rule, "R1_122");
            wsk.sanitize(Rule, "R2_122");

            wsk.createAction("A1_122", TestUtils.getCatalogFilename("samples/wc.js"));
            wsk.createAction("A2_122", TestUtils.getCatalogFilename("samples/hello.js"));
            wsk.createTrigger("T1_122");
            wsk.createRule("R1_122", "T1_122", "A1_122");
            wsk.createRule("R2_122", "T1_122", "A2_122");

            wsk.trigger("T1_122", "put a fork in it");

            String expected1 = "The message 'put a fork in it' has";
            assertTrue("Expected message not found: " + expected1, wsk.logsForActionContain("A1_122", expected1, DELAY));

            String expected2 = "hello put a fork in it";
            assertTrue("Expected message not found: " + expected2, wsk.logsForActionContain("A2_122", expected2, DELAY));

        } finally {
            wsk.delete(Action, "A1_122");
            wsk.delete(Action, "A2_122");
            wsk.delete(Trigger, "T1_122");
            wsk.delete(Rule, "R1_122");
            wsk.delete(Rule, "R2_122");
        }
    }

    /**
     * rule test with two triggers and two actions
     */
    @Test
    public void rule2to2() throws Exception {
        long startMilli = System.currentTimeMillis();
        try {
            wsk.sanitize(Action, "A1_222");
            wsk.sanitize(Action, "A2_222");
            wsk.sanitize(Trigger, "T1_222");
            wsk.sanitize(Trigger, "T2_222");
            wsk.sanitize(Rule, "Alpha");
            wsk.sanitize(Rule, "Beta");
            wsk.sanitize(Rule, "Gamma");
            wsk.sanitize(Rule, "Delta");
            long endMilli = System.currentTimeMillis();
            System.out.format("rule2to2: %.1f seconds to sanitize\n", (endMilli - startMilli) / 1000.0);
            startMilli = endMilli;

            wsk.createAction("A1_222", TestUtils.getCatalogFilename("samples/wc.js"));
            wsk.createAction("A2_222", TestUtils.getCatalogFilename("samples/hello.js"));
            wsk.createTrigger("T1_222");
            wsk.createTrigger("T2_222");
            wsk.createRule("Alpha", "T1_222", "A1_222");
            wsk.createRule("Beta", "T1_222", "A2_222");
            wsk.createRule("Gamma", "T2_222", "A1_222");
            wsk.createRule("Delta", "T2_222", "A2_222");
            endMilli = System.currentTimeMillis();
            System.out.format("rule2to2: %.1f seconds to create actions and rules\n", (endMilli - startMilli) / 1000.0);
            startMilli = endMilli;

            wsk.trigger("T1_222", "XXX");
            wsk.trigger("T2_222", "YYY");
            endMilli = System.currentTimeMillis();
            System.out.format("rule2to2: %.1f seconds to trigger\n", (endMilli - startMilli) / 1000.0);
            startMilli = endMilli;

            List<String> activations = wsk.waitForActivations("A1_222", 2, DELAY);
            assertTrue("Not enough activation ids found", activations != null);

            String expected1 = "The message 'XXX' has";
            assertTrue("Expected message not found: " + expected1, wsk.logsForActionContain("A1_222", expected1, DELAY));
            String expected2 = "The message 'YYY' has";
            assertTrue("Expected message not found: " + expected2, wsk.logsForActionContain("A1_222", expected2, DELAY));
            endMilli = System.currentTimeMillis();
            System.out.format("rule2to2: %.1f seconds for first check\n", (endMilli - startMilli) / 1000.0);

            startMilli = endMilli;

            expected1 = "hello XXX";
            expected2 = "hello YYY";
            assertTrue("Expected message not found: " + expected1, wsk.logsForActionContain("A2_222", expected1, DELAY));
            assertTrue("Expected message not found: " + expected2, wsk.logsForActionContain("A2_222", expected2, DELAY));
            endMilli = System.currentTimeMillis();
            System.out.format("rule2to2: %.1f seconds for second check\n", (endMilli - startMilli) / 1000.0);

            startMilli = endMilli;
        } finally {
            wsk.delete(Action, "A1_222");
            wsk.delete(Action, "A2_222");
            wsk.delete(Trigger, "T1_222");
            wsk.delete(Trigger, "T2_222");
            wsk.delete(Rule, "Alpha");
            wsk.delete(Rule, "Beta");
            wsk.delete(Rule, "Gamma");
            wsk.delete(Rule, "Delta");
            long endMilli = System.currentTimeMillis();
            System.out.format("rule2to2: %.1f seconds for cleanup\n", (endMilli - startMilli) / 1000.0);
        }
    }

    /**
     * rule test to make sure deleting a rule also disables it.
     */
    @Test
    public void ruleDisable() throws Exception {
        long startMilli = System.currentTimeMillis();
        try {
            wsk.sanitize(Action, "A_321");
            wsk.sanitize(Trigger, "T_321");
            wsk.sanitize(Rule, "R_321");
            long endMilli = System.currentTimeMillis();
            System.out.format("ruleDisable: %.1f seconds to sanitize\n", (endMilli - startMilli) / 1000.0);
            startMilli = endMilli;

            wsk.createAction("A_321", TestUtils.getCatalogFilename("samples/wc.js"));
            wsk.createTrigger("T_321");
            wsk.createRule("R_321", "T_321", "A_321");
            wsk.delete(Rule, "R_321");
            endMilli = System.currentTimeMillis();
            System.out.format("ruleDisable: %.1f seconds to create and delete rule/action\n", (endMilli - startMilli) / 1000.0);
            startMilli = endMilli;
            wsk.trigger("T_321", "ralph");
            endMilli = System.currentTimeMillis();
            System.out.format("ruleDisable: %.1f seconds to trigger\n", (endMilli - startMilli) / 1000.0);
            startMilli = endMilli;

            // retrieve activation ids; wait for at least one
            List<String> activations = wsk.waitForActivations("A_321", 1, NEGATIVE_DELAY);
            assertTrue("Unexpected activation id found: ", activations == null || activations.size() == 0);
            endMilli = System.currentTimeMillis();
            System.out.format("ruleDisable: %.1f seconds to get activation\n", (endMilli - startMilli) / 1000.0);
            startMilli = endMilli;
        } finally {
            wsk.delete(Action, "A_321");
            wsk.delete(Trigger, "T_321");
            wsk.sanitize(Rule, "R_321");
            long endMilli = System.currentTimeMillis();
            System.out.format("ruleDisable: %.1f seconds to cleanup\n", (endMilli - startMilli) / 1000.0);
        }
    }

    /**
     * rule test to check if a rule can be deleted and recreated with the same
     * name.
     */
    @Test
    public void ruleRecreate() throws Exception {
        try {
            wsk.sanitize(Action, "A_421");
            wsk.sanitize(Trigger, "T_421");
            wsk.sanitize(Trigger, "T_422");
            wsk.sanitize(Rule, "R_421");

            wsk.createAction("A_421", TestUtils.getCatalogFilename("samples/wc.js"));
            wsk.createTrigger("T_421");
            wsk.createRule("R_421", "T_421", "A_421");
            wsk.delete(Rule, "R_421");
            wsk.createTrigger("T_422");
            wsk.createRule("R_421", "T_422", "A_421");
            wsk.trigger("T_422", "david");

            List<String> activations = wsk.waitForActivations("A_421", 1, NEGATIVE_DELAY);
            if (activations == null || activations.size() == 0) {
                assertFalse("Did not find any activations for A_421", true);
                return;
            }
            String activationId = activations.get(0);
            String expected = "The message 'david' has";
            assertTrue("Expected message found: " + expected, wsk.logsForActivationContain(activationId, expected, DELAY));
        } finally {
            wsk.delete(Action, "A_421");
            wsk.delete(Trigger, "T_421");
            wsk.delete(Trigger, "T_422");
            wsk.sanitize(Rule, "R_421");
        }
    }

    /**
     * rule test to check if a rule can be disable and enabled.
     */
    @Test
    public void ruleDisableEnable() throws Exception {
        try {
            wsk.sanitize(Action, "A_621");
            wsk.sanitize(Trigger, "T_621");
            wsk.sanitize(Rule, "R_621");

            wsk.createAction("A_621", TestUtils.getCatalogFilename("samples/wc.js"));
            wsk.createTrigger("T_621");
            wsk.createRule("R_621", "T_621", "A_621");

            wsk.disableRule("R_621", RULE_DELAY);
            wsk.trigger("T_621", "batman");

            wsk.enableRule("R_621", RULE_DELAY);
            wsk.trigger("T_621", "bruce wayne");

            List<String> activations = wsk.waitForActivations("A_621", 1, DELAY);
            assertTrue("Not enough activation ids found", activations != null);

            String activationId = activations.get(0);
            String expected = "The message 'bruce wayne' has";
            assertTrue("Expected message not found: " + expected, wsk.logsForActivationContain(activationId, expected, DELAY));

            String unexpected = "The message 'batman' has";
            assertFalse("Unexpected message found: " + unexpected, wsk.logsForActivationContain(activationId, unexpected, NEGATIVE_DELAY));
        } finally {
            wsk.delete(Action, "A_621");
            wsk.delete(Trigger, "T_621");
            wsk.sanitize(Rule, "R_621");
        }
    }

    /**
     * Test for presence of activation records for trigger, rule, and action.
     */
    @Test
    public void activations() throws Exception {
        int nameSuffix = ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE);
        String action = "ACT_A_" + nameSuffix;
        String rule = "ACT_R_" + nameSuffix;
        String trigger = "ACT_T_" + nameSuffix;

        final class ActivationInfo {
            public String activationId;
            public String cause;

            public ActivationInfo(String activationId, String cause) {
                this.activationId = activationId;
                this.cause = cause;
            }

            @Override
            public String toString() {
                return "ActivationInfo [activationId=" + activationId + ", cause=" + cause + "]";
            }
        }

        try {
            wsk.sanitize(Action, action);
            wsk.sanitize(Trigger, trigger);
            wsk.sanitize(Rule, rule);

            wsk.createAction(action, TestUtils.getCatalogFilename("utils/date.js"));
            wsk.createTrigger(trigger);
            wsk.createRule(rule, trigger, action);
            wsk.trigger(trigger, "bobby 121");

            // Get activations
            Set<String> entities = new HashSet<String>(Arrays.asList(new String[] { trigger, action, rule }));
            Map<String, ActivationInfo> activations = TestUtils.waitfor(() -> {
                String list = wsk.list(Activation);
                String[] lines = list.split("\\r?\\n");
                Map<String, ActivationInfo> infos = new HashMap<String, ActivationInfo>();
                for (String line : lines) {
                    String[] words = line.split("\\s+");
                    if (words.length == 2) {
                        String entityName = words[1];
                        String activationId = words[0];
                        if (entities.contains(entityName)) {
                            String activation = wsk.get(Activation, activationId);
                            // remove "ok" line in stdout; leaving json
                            activation = activation.substring(activation.indexOf(System.getProperty("line.separator")) + 1);
                            JsonObject json = new JsonParser().parse(activation).getAsJsonObject();
                            String cause = json.get("cause") != null ? json.get("cause").getAsString() : "";
                            infos.put(entityName, new ActivationInfo(activationId, cause));
                        }
                    }
                }
                return infos.size() == 3 ? infos : null;
            } , 8, 1, DELAY);

            // Check that activations exist.
            assertTrue("Activation not found for " + trigger, activations.containsKey(trigger));
            assertTrue("Activation not found for " + rule, activations.containsKey(rule));
            assertTrue("Activation not found for " + action, activations.containsKey(action));

            // Check that activation cause is correct.
            assertTrue("Wrong cause found for " + trigger, activations.get(trigger).cause.equals(""));
            assertTrue("Wrong cause found for " + rule, activations.get(rule).cause.equals(activations.get(trigger).activationId));
            assertTrue("Wrong cause found for " + action, activations.get(action).cause.equals(activations.get(rule).activationId));

        } finally {
            wsk.delete(Rule, rule);
            wsk.delete(Action, action);
            wsk.delete(Trigger, trigger);
        }
    }

}
