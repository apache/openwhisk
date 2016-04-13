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
import static org.junit.Assert.assertTrue;

import java.util.HashMap;

import org.junit.Test;
import org.junit.runner.RunWith;

import com.google.code.tempusfugit.concurrency.ParallelRunner;

import common.Pair;
import common.TestUtils;
import common.WskCli;

@RunWith(ParallelRunner.class)
public class CLIJavaTests {

    private static final WskCli wsk = new WskCli();
    private static final int DEFAULT_WAIT = 60;

    /**
     * Test the Java "hello world" demo sequence
     */
    @Test(timeout = 120 * 1000)
    public void helloWorldJava() throws Exception {
        final String actionName = "helloJava";

        try {
            wsk.sanitize(Action, actionName);
            wsk.createAction(actionName, TestUtils.getCatalogFilename("samples/helloJava/build/libs/helloJava.jar"));

            final String activationIds[] = { null, null };
            activationIds[0] = wsk.invoke(actionName, new HashMap<String, String>());
            activationIds[1] = wsk.invoke(actionName, TestUtils.makeParameter(Pair.make("name", "Sir")));

            final String expected[] = { "Hello stranger!", "Hello Sir!" };

            for (int i = 0; i < activationIds.length; i++) {
                boolean pass = wsk.checkResultFor(activationIds[i], expected[i], DEFAULT_WAIT);
                assertTrue("Did not find " + expected[i] + " in activation " + activationIds[i], pass);
            }

        } finally {
            wsk.delete(Action, actionName);
        }
    }
}
