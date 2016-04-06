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

import static common.Pair.make;
import static common.TestUtils.SUCCESS_EXIT;
import static common.WskCli.Item.Action;
import static common.WskCli.Item.Package;
import static org.junit.Assert.assertTrue;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import com.google.code.tempusfugit.concurrency.ParallelRunner;

import common.TestUtils;
import common.WskCli;

/**
 * Tests for packages using command line interface
 */
@RunWith(ParallelRunner.class)
public class CLIPackageTests {

    private static final WskCli wsk = new WskCli();

    private static final int DELAY = 80;  // Wait this long for logs to show up

    @BeforeClass
    public static void setUp() throws Exception {
    }

    /**
     * Test the creation of a package.
     */
    @Test(timeout=120*1000)
    public void simpleCreatePackage() throws Exception {
        String packageName = "simplepackage";
        try {
            wsk.sanitize(Package, packageName);
            wsk.createPackage(packageName, new HashMap<String, String>());
        } finally {
            wsk.delete(Package, packageName);
        }
    }

    /**
     * Test the copy of the package.
     */
    @Test(timeout=120*1000)
    public void simpleCreatePackageWithParameter() throws Exception {
        String packageName = "simplepackagewithparams";
        try {
            wsk.sanitize(Package, packageName);
            HashMap<String, String> params = new HashMap<String, String>();
            params.put("p1", "v1");
            params.put("p2", "");
            wsk.createPackage(packageName, params);
        } finally {
            wsk.delete(Package, packageName);
        }
    }

    /**
     * Test the update of a package.
     */
    @Test(timeout=120*1000)
    public void simpleUpdatePackage() throws Exception {
        String packageName = "simplepackagetoupdate";
        try {
            wsk.sanitize(Package, packageName);
            HashMap<String, String> params = new HashMap<String, String>();
            params.put("p1", "v1");
            params.put("p2", "");
            wsk.createPackage(packageName, params );
            params.put("p2", "v2");
            params.put("p3", "v3");
            wsk.updatePackage(packageName, params );
        } finally {
            wsk.delete(Package, packageName);
        }
    }

    /**
     * Test the binding of a package.
     */
    @Test(timeout=120*1000)
    public void simpleBindPackage() throws Exception {
        String packageName = "simplepackagetobind";
        String bindName = "simplebind";
        try {
            wsk.sanitize(Package, packageName);
            HashMap<String, String> params = new HashMap<String, String>();
            params.put("p1", "v1");
            params.put("p2", "");
            wsk.createPackage(packageName, params );
            params.put("p2", "v2");
            params.put("p3", "v3");
            wsk.bindPackage(SUCCESS_EXIT, packageName, bindName, params );
        } finally {
            wsk.delete(Package, packageName);
            wsk.delete(Package, bindName);
        }
    }


    /**
     * Test package binding parameter inheritance.
     */
    @Test(timeout=120*1000)
    @SuppressWarnings("unchecked")
    public void parameterInheritance() throws Exception {
        String packageName = "package1";
        String bindName = "package2";
        String actionName = "print";
        String packageActionName = packageName + "/" + actionName;
        String bindActionName = bindName + "/" + actionName;
        try {
            wsk.sanitize(Package, bindName);
            wsk.sanitize(Action, packageActionName);
            wsk.sanitize(Package, packageName);

            Map<String, String> packageParams = TestUtils.makeParameter(make("key1a", "value1a"), make("key1b", "value1b"));
            Map<String, String> bindParams = TestUtils.makeParameter(make("key2a", "value2a"), make("key1b", "value2b"));
            Map<String, String> actionParams = TestUtils.makeParameter(make("key0", "value0"));

            wsk.createPackage(packageName, packageParams);
            wsk.createAction(packageActionName, TestUtils.getCatalogFilename("samples/printParams.js"), actionParams);
            wsk.bindPackage(SUCCESS_EXIT, packageName, bindName, bindParams);

            // Check that the description of packages and actions includes all the inherited parameters.
            String packageDescription = wsk.get(Package, packageName);
            String bindDescription = wsk.get(Package, bindName);
            String packageActionDescription = wsk.get(Action, packageActionName);
            String bindActionDescription = wsk.get(Action, bindActionName);
            checkForParameters(packageDescription, packageParams);
            checkForParameters(bindDescription, packageParams, bindParams);
            checkForParameters(packageActionDescription, packageParams, actionParams);
            checkForParameters(bindActionDescription, packageParams, bindParams, actionParams);

            // Check that inherited parameters are passed to the action.
            String now = new Date().toString();
            String activationId = wsk.invoke(bindActionName, TestUtils.makeParameter("payload", now));
            String expected = String.format(".*key0: value0.*key1a: value1a.*key1b: value2b.*key2a: value2a.*payload: %s", now);
            assertTrue("Expected message not found: " + expected + " in activation " + activationId, wsk.logsForActivationContain(activationId, expected, DELAY));
        } finally {
            wsk.sanitize(Package, bindName);
            wsk.sanitize(Action, packageActionName);
            wsk.sanitize(Package, packageName);
        }
    }


    /**
     * Check that a description of an item includes the specified parameters. Parameters keys in later parameter maps override earlier ones.
     */
    private void checkForParameters(String itemDescription, @SuppressWarnings("unchecked") Map<String, String>... paramSets) {
        // Merge and the parameters handling overrides.
        Map<String, String> mergedParams = new HashMap<String, String>();
        for (Map<String, String> params: paramSets) {
            for (Entry<String, String> entry : params.entrySet()) {
                mergedParams.put(entry.getKey(), entry.getValue());
            }
        }

        String flatDescription = itemDescription.replace("\n", "").replace("\r", "");
        for (Entry<String, String> entry : mergedParams.entrySet()) {
            assertTrue("Expected key-value not found: " + entry, descriptionContainsKeyValue(flatDescription, entry.getKey(), entry.getValue()));
        }
    }

    private boolean descriptionContainsKeyValue(String description, String key, String value) {
        String toFind = String.format("`key`: `%s`,            `value`: `%s`", key, value).replace("`",  "\"");
        boolean found = description.contains(toFind);
        return found;
    }
}
