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

package packages;

import static common.Pair.make;
import static org.junit.Assert.assertTrue;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import com.google.code.tempusfugit.concurrency.ParallelRunner;

import common.Pair;
import common.TestUtils;
import common.WskCli;

/**
 * Test actions in samples package.
 */
@RunWith(ParallelRunner.class)
public class SamplesTests {
    private static final Boolean usePythonCLI = true;
    private final WskCli wsk = new WskCli(usePythonCLI);

    @BeforeClass
    public static void setUp() throws Exception {
    }

    /**
     * Test the greeting action.
     */
    @Test
    public void testGreeting() throws Exception {
        String action = "/whisk.system/samples/greeting";

        Pair<String, String> response = wsk.invokeBlocking(action, TestUtils.makeParameter("dummy", "dummy"));
        assertTrue("Wrong default parameters", response.snd.contains("Hello, stranger from somewhere!"));

        response = wsk.invokeBlocking(action, TestUtils.makeParameter("name", "Mork"));
        assertTrue("Wrong name", response.snd.contains("Hello, Mork from somewhere!"));

        response = wsk.invokeBlocking(action, TestUtils.makeParameter(make("name", "Mork"), make("place", "Ork")));
        assertTrue("Wrong name and place", response.snd.contains("Hello, Mork from Ork!"));
    }

}
