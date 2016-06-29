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
import static common.WskCli.Item.Rule;
import static common.WskCli.Item.Trigger;
import static org.junit.Assert.assertTrue;

import org.junit.BeforeClass;
import org.junit.Test;

import common.TestUtils;
import common.WskCli;

/**
 * This tests suite does not use a parallel junit runner. This is intended for
 * testing system invariants such as concurrent activation counts and
 * thresholds.
 */
public class CLISequentialTests {
    private static final Boolean usePythonCLI = false;
    private static final WskCli wsk = new WskCli(usePythonCLI);

    @BeforeClass
    public static void setUp() throws Exception {
    }

    /**
     * Tests that a trigger connected to a deleted action does not cause a
     * normal action to fail due to lingering errors such as limits not
     * correctly tracking.
     *
     * To test that loadbalancer/invoker activation count is consistent, we emit
     * slow enough not to be throttled by the controller.
     *
     * The concurrency limit of 100 is hardcoded for now because there is no
     * query API.
     */
    @Test
    public void ruleDeletedAction() throws Exception {
        try {
            wsk.sanitize(Action, "A_normal");
            wsk.sanitize(Action, "A_del");
            wsk.sanitize(Trigger, "T_del");
            wsk.sanitize(Rule, "R_del");

            wsk.createAction("A_normal", TestUtils.getCatalogFilename("samples/hello.js"));
            wsk.createAction("A_del", TestUtils.getCatalogFilename("samples/wc.js"));
            wsk.createTrigger("T_del");
            wsk.createRule("R_del", "T_del", "A_del");
            wsk.delete(Action, "A_del");

            // Try to trip inconsistency in concurrent activation count of a
            // broken trigger but not rate throttler
            // The numbers are hardcoded because we currently have no API for
            // setting or querying all limits.
            // The threads are used so this test doesn't take too long (> 3 min)
            // but we can't run too fast either (< 1 min).
            final int LIMIT = 100; // concurrent limit
            final int THREADS = 5;
            Thread[] threads = new Thread[THREADS];
            for (int i = 0; i < threads.length; i++) {
                final int index = i; // for "closure"
                threads[i] = new Thread() {
                    public void run() {
                        try {
                            int ITERATIONS = 1 + (LIMIT / THREADS);
                            System.out.println("Thread " + index + ". Running part 1 at " + System.currentTimeMillis());
                            for (int j = 0; j < 1 + ITERATIONS / 2; j++)
                                wsk.triggerNoCheck("T_del", "deletePayload_1_" + j);
                            Thread.sleep(30 * 1000); // so as to not trigger
                                                     // per-minute throttle
                            System.out.println("Thread " + index + ". Running part 2 at " + System.currentTimeMillis());
                            for (int j = 0; j < 1 + ITERATIONS / 2; j++)
                                wsk.triggerNoCheck("T_del", "deletePayload_2_" + j);
                            System.out.println("Thread " + index + ". Done at " + System.currentTimeMillis());
                        } catch (Exception e) {
                            System.out.println("Exception: " + e);
                        }
                    }
                };
                threads[i].start();
            }
            for (int i = 0; i < threads.length; i++) {
                threads[i].join();
            }
            Thread.sleep(5 * 1000); // allow triggers and counts to propagate so
                                    // we can test throttle

            // Check that it's working normally
            String expected = "A_normal_payload";
            System.out.println("Now running unrelated activation at " + System.currentTimeMillis());
            String activationId = wsk.invoke("A_normal", TestUtils.makeParameter("payload", expected));
            boolean found = wsk.logsForActivationContain(activationId, expected, 45);
            System.out.println("Log: " + wsk.getLogsForActivation(activationId).stdout);
            assertTrue("Did not find " + expected + " in activation " + activationId, found);

        } finally {
            wsk.sanitize(Action, "A_normal");
            wsk.sanitize(Action, "A_del");
            wsk.sanitize(Trigger, "T_del");
            wsk.sanitize(Rule, "R_del");
        }
    }

}
