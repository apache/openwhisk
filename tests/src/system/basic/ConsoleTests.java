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

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;

import common.Pair;
import common.TestUtils;
import common.WskCli;

/**
 * Tests of the text console
 */
public class ConsoleTests {
    private static final WskCli wsk = new WskCli();

    @Rule
    public TestRule watcher = TestUtils.makeTestWatcher();

    /**
     * Start the console process
     */
    private Future<String> startConsole(final int seconds) throws IOException {
        Callable<String> task = () -> {
            return wsk.pollActivations(seconds);
        };
        ExecutorService executor = Executors.newFixedThreadPool(1);
        Future<String> future = executor.submit(task);
        return future;
    }

    /**
     * Check that a particular message appears in the console
     *
     * @throws ExecutionException
     * @throws InterruptedException
     */
    private void checkContains(Future<String> f, String msg) throws InterruptedException, ExecutionException {
        System.out.println("assert that the message \"" + msg + "\" appears.");
        System.out.println(f.get());
        assertTrue(f.get().contains(msg));
    }

    @Test
    public void helloWorld() throws Exception {
        Future<String> f = startConsole(20);

        Thread.sleep(3000); // sleep a little before starting the action

        String body = "from the console!";
        body = new String(body.getBytes(), "UTF-8");

        String id = wsk.invoke("/whisk.system/samples/helloWorld", TestUtils.makeParameter("payload", body));
        System.out.println("invoked helloWorld with id " + id);

        checkContains(f, body);
    }

    @Test
    public void countdown() throws Exception {
        String actionName = "countdown";
        Future<String> f = startConsole(50);

        Thread.sleep(3000); // sleep a little before starting the action

        try {
            wsk.sanitize(Action, actionName);
            wsk.createAction(actionName, TestUtils.getCatalogFilename("samples/countdown.js"));

            String n = new String("3".getBytes(), "UTF-8");
            String id = wsk.invoke("countdown", TestUtils.makeParameter(Pair.make("n", n)));
            System.out.println("invoked countdown with id " + id);

            checkContains(f, "Happy New Year");
        } finally {
            wsk.delete(Action, actionName);
        }
    }
}
