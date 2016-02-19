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

package services;

import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;

import common.WhiskProperties;
import common.TestUtils;

/**
 * Basic tests for consul related components
 */
public class ConsulTests {

    @Rule
    public TestRule watcher = TestUtils.makeTestWatcher();

    /**
     * check put and get for the key value store
     * @return
     * @throws IllegalArgumentException
     * @throws IOException
     */
    @Test
    public void testKeyValueStore() throws IllegalArgumentException, IOException {
        String host = WhiskProperties.getConsulServerHost();
        int port = WhiskProperties.getConsulKVPort();
        String key = "consul";
        String value = "'up-and-running'";
        String stdout = TestUtils.runCmd(TestUtils.SUCCESS_EXIT, WhiskProperties.getFileRelativeToWhiskHome("."),
                "/usr/bin/curl", "-XPUT", String.format("http://%s:%d/v1/kv/%s", host, port, key), "-d", value).fst;
        System.out.println(stdout);
        assertTrue(stdout, stdout.contains("true"));
        stdout = TestUtils.runCmd(TestUtils.SUCCESS_EXIT, WhiskProperties.getFileRelativeToWhiskHome("."),
                "/usr/bin/curl", "-XGET", String.format("http://%s:%d/v1/kv/%s?raw", host, port, key)).fst;
        System.out.println(stdout);
        assertTrue(stdout, stdout.contains("up-and-running"));
    }

   
}
