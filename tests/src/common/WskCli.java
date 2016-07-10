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

package common;

import static common.TestUtils.DONTCARE_EXIT;
import static common.TestUtils.SUCCESS_EXIT;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import common.TestUtils.RunResult;

/**
 * An object that encapsulates configuration information needed to drive the
 * whisk CLI
 */
public class WskCli {

    private String cliPath;
    private String[] baseCmd;

    private final File binaryPath;
    public String subject;
    public final String authKey;

    private Map<String, String> env = null;

    private Boolean usePythonCLI = true;

    public static enum Item {
        Package("package"), Trigger("trigger"), Action("action"), Rule("rule"), Activation("activation");

        private final String name; // singular form of the noun used by the CLI

        private Item(String s) {
            this.name = s;
        }
    }

    public WskCli() {
        this(true, WhiskProperties.getAuthFileForTesting());
    }

    public WskCli(Boolean usePythonCLI) {
        this(usePythonCLI, WhiskProperties.getAuthFileForTesting());
    }

    public WskCli(Boolean usePythonCLI, String authKey) {
        this(usePythonCLI, "default", authKey);
    }

    protected WskCli(Boolean usePythonCLI, File authFile) {
        this(usePythonCLI, "default", authFile);
    }

    public WskCli(Boolean usePythonCLI, String subject, File authFile) {
        this(usePythonCLI, subject, WhiskProperties.readAuthKey(authFile));
    }

    public WskCli(Boolean usePythonCLI, String subject, String authKey) {
        if (usePythonCLI) {
            cliPath = WhiskProperties.useCLIDownload() ? getDownloadedPythonCLIPath() : WhiskProperties.getPythonCLIPath();
            baseCmd = new String[] {WhiskProperties.python, cliPath};
        } else {
            cliPath = WhiskProperties.useCLIDownload() ? getDownloadedGoCLIPath() : WhiskProperties.getGoCLIPath();
            baseCmd = new String[] {cliPath};
        }

        this.subject = subject;
        this.authKey = authKey;
        this.usePythonCLI = usePythonCLI;
        this.binaryPath = new File(cliPath);
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getSubject() {
        return this.subject;
    }

    public boolean checkExists() {
        assertTrue("did not find " + binaryPath, binaryPath.exists());
        return true;
    }

    /** What is the path to a downloaded CLI? **/
    private String getDownloadedPythonCLIPath() {
        String binary = System.getProperty("user.home") + File.separator + ".local" + File.separator + "bin" + File.separator + "wsk";
        return binary;
    }

    /** What is the path to a downloaded Go CLI? **/
    private String getDownloadedGoCLIPath() {
        String binary = System.getProperty("user.home") + File.separator + ".local" + File.separator + "bin" +
                File.separator + "go-cli" + File.separator + "wsk";
        return binary;
    }

    public String namespace() throws IOException {
        String result = cli("namespace", "list", "--auth", authKey).stdout;
        String[] lines = result.split("\n");
        assert (lines.length >= 2);
        return lines[1].trim();
    }

    public String downloadDocker(File destPath) throws IOException {
        return cli(true, SUCCESS_EXIT, destPath, "sdk", "install", "docker").stdout;
    }

    public String pollActivations(int seconds) throws IOException {
        return cli(SUCCESS_EXIT, "activation", "poll", "--auth", authKey, "--exit", Integer.toString(seconds)).stdout;
    }

    /**
     * Lists all items (not just recent ones).
     */
    public String listAll(Item item) throws IOException {
        return cli(SUCCESS_EXIT, item.name, "list", "--auth", authKey, "--limit", "0").stdout;
    }

    /**
     * Lists only recent items.
     */
    public String list(Item item) throws IOException {
        return list(item, null);
    }

    /**
     * Lists only recent items.
     */
    public String list(Item item, String namespace) throws IOException {
        if (item == Item.Activation) {
            return listActivations(namespace);
        } else {
            String[] cmd = { item.name, "list", "--auth", authKey };
            if (namespace != null)
                cmd = Util.concat(cmd, new String[] { namespace });
            return cli(SUCCESS_EXIT, cmd).stdout;
        }
    }

    /**
     * Lists only recent activations.
     *
     * @param namespace
     *            the namespace in which to list (null indicates default
     *            namespace)
     * @param full
     *            if true grab the full activation record
     * @return CLI stdout
     */
    public String listActivations(String namespace) throws IOException {
        // bump limit to return all activations
        String[] cmd = { Item.Activation.name, "list", "--auth", authKey, "--limit", "0" };
        if (namespace != null)
            cmd = Util.concat(cmd, new String[] { namespace });
        return cli(SUCCESS_EXIT, cmd).stdout;
    }

    /**
     * Gets an {@code Item} (e.g., action) identified by its {@code item} type
     * and and {@code name}. Asserts an error if item does not exist or if the
     * there is an error in the CLI.
     *
     * @param item
     *            the item type
     * @param name
     *            the name of the item
     * @return CLI stdout
     */
    public String get(Item item, String name) throws IOException {
        return get(SUCCESS_EXIT, item, name);
    }

    /**
     * Gets an {@code Item} (e.g., action) identified by its {@code item} type
     * and and {@code name}. Asserts an error if CLI response code does not
     * match {@code expectedExitCode}.
     *
     * @param expectedExitCode
     *            the expected CLI exit code
     * @param item
     *            the item type
     * @param name
     *            the name of the item
     * @return CLI stdout
     */
    public String get(int expectedExitCode, Item item, String name) throws IOException {
        return cli(DONTCARE_EXIT, item.name, "get", "--auth", authKey, name).stdout;
    }

    /**
     * Deletes an {@code Item} (e.g., action) identified by its {@code item}
     * type and and {@code name} if it exists.
     *
     * @param item
     *            the item type
     * @param name
     *            the name of the item
     * @return CLI stdout
     */
    public String sanitize(Item item, String name) throws IOException {
        return delete(DONTCARE_EXIT, item, name);
    }

    /**
     * Deletes an {@code Item} (e.g., action) identified by its {@code item}
     * type and and {@code name}. Asserts an error if item does not exist or if
     * the there is an error in the CLI.
     *
     * @param item
     *            the item type
     * @param name
     *            the name of the item
     * @return CLI stdout
     */
    public String delete(Item item, String name) throws IOException {
        return delete(SUCCESS_EXIT, item, name);
    }

    /**
     * Deletes an {@code Item} (e.g., action) identified by its {@code item}
     * type and and {@code name}. Asserts an error if CLI response code does not
     * match {@code expectedExitCode}.
     *
     * @param expectedExitCode
     *            the expected CLI exit code
     * @param item
     *            the item type
     * @param name
     *            the name of the item
     * @return CLI stdout
     */
    public String delete(int expectedExitCode, Item item, String name) throws IOException {
        if (item == Item.Rule) {
            try {
                disableRule(expectedExitCode, name, 30);
            } catch (Throwable t) {
                if (expectedExitCode == DONTCARE_EXIT) {
                } else
                    throw t;
            }
        }
        return cli(expectedExitCode, item.name, "delete", "--auth", authKey, name).stdout;
    }

    public String copyAction(String name, String existingAction) throws IOException {
        return createAction(SUCCESS_EXIT, name, existingAction, null, null, false, false, true, false, 0);
    }

    public String createAction(String name, String file) throws IOException {
        return createAction(name, file, null, false, false);
    }

    public String createAction(String name, String file, int timeoutMillis) throws IOException {
        return createAction(name, file, null, false, false, timeoutMillis);
    }

    public String createAction(int expectedCode, String name, String file) throws IOException {
        return createAction(expectedCode, name, file, null, false, false);
    }

    public String createAction(String name, String[] actions) throws IOException {
        String csv = String.join(",", actions);
        return createAction(SUCCESS_EXIT, name, csv, null, null, true, false, false, true, 0);
    }

    public String createAction(String name, String[] actions, int timeoutMillis) throws IOException {
        String csv = String.join(",", actions);
        return createAction(SUCCESS_EXIT, name, csv, null, null, true, false, false, true, timeoutMillis);
    }

    public String createAction(String name, String file, Map<String, String> params) throws IOException {
        return createAction(name, file, params, false, false);
    }

    public String createAction(String name, String file, String library) throws IOException {
        return createAction(SUCCESS_EXIT, name, file, library, null, false, false, false, false, 0);
    }

    public String createAction(String name, String file, String library, Map<String, String> params) throws IOException {
        return createAction(SUCCESS_EXIT, name, file, library, params, false, false, false, false, 0);
    }

    public String createAction(String name, String file, boolean update, boolean shared) throws IOException {
        return createAction(SUCCESS_EXIT, name, file, null, null, false, update, false, shared, 0);
    }

    public String createAction(String name, String file, Map<String, String> params, boolean update, boolean shared) throws IOException {
        return createAction(SUCCESS_EXIT, name, file, null, params, false, update, false, shared, 0);
    }

    public String createAction(String name, String file, Map<String, String> params, boolean update, boolean shared, int timeoutMillis) throws IOException {
        return createAction(SUCCESS_EXIT, name, file, null, params, false, update, false, shared, timeoutMillis);
    }

    public String updateAction(String name, boolean shared) throws IOException {
        return createAction(SUCCESS_EXIT, name, null, null, null, false, true, false, shared, 0);
    }

    public String createAction(int expectedCode, String name, String file, Map<String, String> params, boolean update, boolean shared) throws IOException {
        return createAction(expectedCode, name, file, null, params, false, update, false, shared, 0);

    }

    public String createAction(int expectedCode, String name, String artifact, String library, Map<String, String> params, boolean sequence, boolean update, boolean copy, boolean shared, int timeoutMillis) throws IOException {
        String[] cmd1 = { "action", "update", "--auth", authKey, name };
        String[] cmd2 = { "action", "create", "--auth", authKey, name, artifact };
        String[] cmd = update ? cmd1 : cmd2;

        if (update && artifact != null) {
            cmd = Util.concat(cmd, new String[] { artifact });
        }

        if (params != null) {
            for (String key : params.keySet()) {
                String value = params.get(key);
                cmd = Util.concat(cmd, new String[] { "--param", key, value });
            }
        }

        if (timeoutMillis > 0) {
            cmd = Util.concat(cmd, new String[] { "--timeout", Integer.toString(timeoutMillis) });
        }

        if (sequence) {
            cmd = Util.concat(cmd, new String[] { "--sequence" });
        }

        if (library != null) {
            cmd = Util.concat(cmd, new String[] { "--lib", library });
        }

        if (copy) {
            cmd = Util.concat(cmd, new String[] { "--copy" });
        }

        if (shared) {
            cmd = Util.concat(cmd, new String[] { "--shared", "yes"});
        }

        RunResult result = cli(expectedCode, cmd);
        String stdout = result.stdout;
        if (expectedCode == SUCCESS_EXIT && update) {
            assertTrue(stdout, stdout.contains("ok: updated action"));
        } else if (expectedCode == SUCCESS_EXIT) {
            assertTrue(stdout, stdout.contains("ok: created action"));
        }
        return expectedCode == SUCCESS_EXIT ? stdout : stdout.concat(result.stderr);
    }

    public String updatePackage(String name, Map<String, String> params) throws IOException {
        return createPackage(SUCCESS_EXIT, name, params, "", true, false);
    }

    public String createPackage(String name, Map<String, String> params) throws IOException {
        return createPackage(SUCCESS_EXIT, name, params, "", false, false);
    }

    public String createPackage(int expectedCode, String name, Map<String, String> params, String copyFrom, boolean update, boolean shared) throws IOException {
        String op = "create";
        if (update) {
            op = "update";
        }

        String[] cmd = { "package", op, "--auth", authKey, name };
        if (params != null) {
            for (String key : params.keySet()) {
                String value = params.get(key);
                cmd = Util.concat(cmd, new String[] { "--param", key, value });
            }
        }

        if (copyFrom != null && copyFrom.trim().length() > 0) {
            cmd = Util.concat(cmd, new String[] { "--copy", copyFrom });
        }

        if (shared) {
            cmd = Util.concat(cmd, new String[] { "--shared" });
        }

        RunResult result = cli(expectedCode, cmd);
        String stdout = result.stdout;
        if (expectedCode == SUCCESS_EXIT && update) {
            assertTrue(stdout, stdout.contains("ok: updated package"));
        } else if (expectedCode == SUCCESS_EXIT) {
            assertTrue(stdout, stdout.contains("ok: created package"));
        }
        return expectedCode == SUCCESS_EXIT ? stdout : stdout.concat(result.stderr);
    }

    public String bindPackage(int expectedCode, String packageName, String name, Map<String, String> params) throws IOException {
        String[] cmd = { "package", "bind", "--auth", authKey, packageName, name };
        if (params != null) {
            for (String key : params.keySet()) {
                String value = params.get(key);
                cmd = Util.concat(cmd, new String[] { "--param", key, value });
            }
        }

        RunResult result = cli(expectedCode, cmd);
        String stdout = result.stdout;
        if (expectedCode == SUCCESS_EXIT) {
            assertTrue(stdout, stdout.contains("ok: created binding " + name));
        }
        return expectedCode == SUCCESS_EXIT ? stdout : stdout.concat(result.stderr);
    }

    public String refreshPackages(int expectedCode, String namespace) throws IOException {
        String[] cmd = { "package", "refresh", "--auth", authKey, namespace };

        RunResult result = cli(expectedCode, cmd);
        String stdout = result.stdout;
        return expectedCode == SUCCESS_EXIT ? stdout : stdout.concat(result.stderr);
    }

    public String getContainerStatisticsForItem(String actionName) {

        return null;
    }

    public String createDockerAction(String name, String container, boolean update) throws IOException {
        String[] cmd = { "action", "create", "--auth", authKey, name, "--docker", container };
        if (update) {
            cmd = Util.concat(cmd, new String[] { "--update" });
        }

        String result = cli(cmd).stdout;
        assertTrue(result, result.contains("ok: created action"));
        return result;
    }

    public void createTrigger(String name) throws IOException {
        createTrigger(SUCCESS_EXIT, name);
    }

    public void createTrigger(int expectedCode, String name) throws IOException {
        String result = cli(expectedCode, "trigger", "create", "--auth", authKey, name).stdout;
        if (expectedCode == SUCCESS_EXIT) {
            assertTrue(result, result.contains("ok: created trigger"));
        }
    }

    public void createRule(String name, String trigger, String action) throws IOException {
        createRule(SUCCESS_EXIT, name, trigger, action);
    }

    public void createRule(int expectedCode, String name, String trigger, String action) throws IOException {
        String result = cli(expectedCode, "rule", "create", "--auth", authKey, name, trigger, action).stdout;
        if (expectedCode == SUCCESS_EXIT) {
            assertTrue(result, result.contains("ok: created rule"));
            enableRule(name, 30);
        }
    }

    public String enableRule(String name, int timeout) throws IOException {
        String result = cli("rule", "enable", "--auth", authKey, name).stdout;
        assertTrue(result, result.contains("ok:"));
        Boolean b = TestUtils.waitfor(() -> {
            return checkRuleState(name, true);
        } , 0, 1, timeout);
        assert (b);
        return result;
    }

    public String disableRule(String name, int timeout) throws IOException {
        return disableRule(SUCCESS_EXIT, name, timeout);
    }

    public String disableRule(int expectedCode, String name, int timeout) throws IOException {
        String result = cli(expectedCode, "rule", "disable", "--auth", authKey, name).stdout;
        assertTrue(result, result.contains("ok:"));
        Boolean b = TestUtils.waitfor(() -> {
            return checkRuleState(name, false);
        } , 0, 1, timeout);
        assert (b);
        return result;
    }

    private boolean checkRuleState(String name, boolean active) throws IOException {
        String result = cli("rule", "status", "--auth", authKey, name).stdout;
        return active ? result.contains("is active") : result.contains("is inactive");
    }

    /**
     * Fires a trigger.
     *
     * @param name
     *            the name of the trigger
     * @param arg
     *            the arguments to attach to the trigger
     * @return CLI stdout
     */
    public String trigger(String name, String arg) throws IOException {
        String result = cli("trigger", "fire", "--auth", authKey, name, arg).stdout;
        assertTrue(result, result.contains("ok: triggered"));
        return result;
    }

    /*
     * Same as trigger but return result without checking error code.
     */
    public RunResult triggerNoCheck(String name, String arg) throws IOException {
        return cli(DONTCARE_EXIT, "trigger", "fire", "--auth", authKey, name, arg);
    }

    public RunResult invokeNoCheck(String actionName, Map<String, String> params) throws IOException {
        return invoke(DONTCARE_EXIT, actionName, params, false);
    }

    public String invoke(String name, Map<String, String> params) throws IOException {
        String[] cmd = { "action", "invoke", "--auth", authKey, name };
        if (params != null) {
            for (String key : params.keySet()) {
                String value = params.get(key);
                cmd = Util.concat(cmd, new String[] { "--param", key, value });
            }
        }

        int expectedCode = SUCCESS_EXIT;
        RunResult result = cli(expectedCode, cmd);
        String stdout = result.stdout;
        if (expectedCode == SUCCESS_EXIT) {
            assertTrue(stdout, stdout.contains("ok:"));
            return extractActivationIdFromCliResult(stdout);
        } else
            return null;
    }

    // Returns the invocation ID and result.
    public Pair<String, String> invokeBlocking(String name, Map<String, String> params) throws IOException {
        String response = invoke(SUCCESS_EXIT, name, params, true).stdout;
        String activationId = extractActivationIdFromCliResult(response);
        String result = extractActivationResultFromCliResult(response);
        JsonObject json = new JsonParser().parse(result).getAsJsonObject();
        if (json == null || json.get("response") == null) {
            return Pair.make(activationId, "invalid Json response: " + result);
        } else {
            return Pair.make(activationId, json.get("response").toString());
        }
    }

    /**
     * Invoke Item in blocking mode, ignoring the return code.
     *
     * @param name
     *            name of the item to invoke
     * @param params
     *            the invocation arguments
     * @return
     * @throws IOException
     */
    public Pair<String, String> invokeBlockingIgnoringExitcode(String name, Map<String, String> params) throws IOException {
        String response = invoke(TestUtils.DONTCARE_EXIT, name, params, true).stdout;
        String activationId = extractActivationIdFromCliResult(response);
        String result = extractActivationResultFromCliResult(response);
        return Pair.make(activationId, result);
    }

    public String invokeBlockingIgnoringExitcodeReturnPlainResult(String name, Map<String, String> params) throws IOException {
        return invoke(TestUtils.DONTCARE_EXIT, name, params, true).stdout;
    }

    public RunResult invoke(int expectedCode, String name, Map<String, String> params, boolean blocking) throws IOException {
        String[] cmd = new String[] { "action", "invoke", "--auth", authKey, name };
        for (String key : params.keySet()) {
            String value = params.get(key);
            cmd = Util.concat(cmd, new String[] { "--param", key, value });
        }
        String[] args = blocking ? Util.concat(cmd, "--blocking") : cmd;
        return cli(expectedCode, args);
    }

    /**
     * Get a list of the activation ids for all activations of a particular
     * action name.
     *
     * @param name
     *            name of the action.
     * @return the activation ids
     */
    public List<String> getActivations(String name) throws IOException {
        return getActivations(name, 0);
    }

    /**
     * Get a list of the activation ids for the most recent N activations of a
     * particular action name.
     *
     * @param name
     *            name of the action.
     * @param lastN
     *            how many activation ids to fetch
     * @return the activation ids
     */
    public List<String> getActivations(String name, int lastN) throws IOException {
        String canonicalName = name;
        String result = cli(SUCCESS_EXIT, "activation", "list", canonicalName, "--limit", "" + lastN, "--auth", authKey).stdout;
        String[] lines = result.split("\n");
        List<String> activations = new ArrayList<String>();
        for (int i = 1; i < lines.length; i++) {
            String[] parts = lines[i].split(" ");
            activations.add(parts[0]);
        }
        return activations;
    }

    /**
     * Does there exist an activation id for a particular action which equals
     * the expected string?
     *
     * @param name
     *            the name of the action.
     * @param expecte
     *            dthe expected activation id
     * @param totalWait
     *            wait up until this many seconds for the id to appear.
     * @return true if found, false otherwise
     */
    public boolean activationsContain(String name, String expected, int totalWait) throws IOException {
        Boolean b = TestUtils.waitfor(() -> {
            List<String> ids = getActivations(name, 0);
            for (String id : ids) {
                if (id.contains(expected))
                    return true;
            }
            return false;
        } , 0, 1, totalWait);
        return (b != null) && b;
    }

    /**
     * Wait for at least N activation ids for a particular action to appear.
     *
     * @param name
     *            name of the action.
     * @param expected
     *            how many activation ids are expected?
     * @param totalWait
     *            wait up until this many seconds for the expected ids to appear
     */
    public List<String> waitForActivations(String name, int expected, int totalWait) throws IOException {
        return TestUtils.waitfor(() -> {
            List<String> activations = getActivations(name, 0);
            return (activations.size() >= expected) ? activations : null;
        } , 0, 1, totalWait);
    }

    public RunResult getResult(String activationId) throws IOException {
        RunResult result = cli(DONTCARE_EXIT, "activation", "result", activationId, "--auth", authKey);
        return result;
    }

    /**
     * Fetch the logs (stodout, stderr) recorded for a particular activation
     */
    public RunResult getLogsForActivation(String activationId) throws IOException {
        RunResult result = cli(DONTCARE_EXIT, "activation", "logs", activationId, "--auth", authKey);
        return result;
    }

    /**
     * Do the logs for a particular activation contain a certain string?
     *
     * @param activationId
     *            the id of the activation
     * @param w
     *            the string to search for
     * @param totalWait
     *            wait up to the this many seconds for the string to appear
     * @return whether log contains entry
     * @throws IOException
     */
    public boolean logsForActivationContain(String activationId, String w, int totalWait) throws IOException {
        return logsForActivationContainGet(activationId, w, totalWait) != null;
    }

    /**
     * Do the logs for a particular activation contain a certain string? If so,
     * return the log.
     *
     * @param activationId
     *            the id of the activation
     * @param w
     *            the string to search for
     * @param totalWait
     *            wait up to the this many seconds for the string to appear
     * @return the log if there is a match
     * @throws IOException
     */
    public String logsForActivationContainGet(String activationId, String w, int totalWait) throws IOException {
        String regex = ".*" + w + ".*";
        return checkActivationFor(activationId, regex, 1, 1, totalWait);
    }

    /**
     * Do the logs for a particular activation contain a certain string? If so,
     * return the log. Else null.
     *
     * @param activationId
     *            the id of the activation
     * @param w
     *            the string to search for
     * @param initialWait
     *            wait this many seconds before checking logs
     * @param pollPeriod
     *            poll at this interval in seconds
     * @param totalWait
     *            wait up to the this many seconds for the activation record to
     *            appear
     * @return the activation log if there is match; otherwise null
     * @throws IOException
     */
    private String checkActivationFor(String activationId, String sRegex, int initialWait, int pollPeriod, int totalWait) throws IOException {
        Pattern p = Pattern.compile(sRegex, Pattern.DOTALL);
        String log = TestUtils.waitfor(() -> {
            RunResult result = getLogsForActivation(activationId);
            if (result.exitCode == SUCCESS_EXIT) {
                return result.stdout;
            } else
                return null;
        } , initialWait, pollPeriod, totalWait);
        if (log != null) {
            Matcher m = p.matcher(log);
            return m.find() ? log : null;
        } else
            return null;
    }

    /***
     * Do the logs for a particular action contain a certain string?
     *
     * @param action
     *            name of the action.
     * @param w
     *            the string to search for
     * @param totalWait
     *            wait up to the this many seconds for the string to appear
     * @return the the first activation log if there is match; otherwise null
     * @throws IOException
     */
    public String firstLogsForActionContainGet(String action, String regex, int totalWait) throws IOException {
        return matchLogsForActionContain(action, regex, 1, 1, totalWait);
    }

    /**
     * Do the logs for a particular action contain a certain string? If so
     * return the matching log. If not, return null.
     *
     * @param action
     *            name of the action.
     * @param w
     *            the string to search for
     * @param initialWait
     *            wait this many seconds before checking lobs
     * @param pollPeriod
     *            poll at this interval in seconds
     * @param totalWait
     *            wait up to the this many seconds for the string to appear
     * @return
     * @throws IOException
     */
    private String matchLogsForActionContain(String action, String sRegex, int initialWait, int pollPeriod, int totalWait) throws IOException {
        Pattern p = Pattern.compile(sRegex, Pattern.DOTALL);
        String l = TestUtils.waitfor(() -> {
            List<String> activationIds = getActivations(action);
            for (String id : activationIds) {
                RunResult result = getLogsForActivation(id);
                if (result.exitCode == SUCCESS_EXIT) {
                    Matcher m = p.matcher(result.stdout);
                    if (m.find())
                        return result.stdout;
                }
            }
            return null;
        } , initialWait, pollPeriod, totalWait);
        return l;
    }

    public List<String> getLogsForAction(String action) throws IOException {
        List<String> result = new ArrayList<String>();
        List<String> activationIds = getActivations(action);
        for (String id : activationIds) {
            RunResult res = getLogsForActivation(id);
            if (res.exitCode == SUCCESS_EXIT) {
                result.add(res.stdout);
            }
        }
        return result;
    }

    /**
     * Do the logs for a particular action contain a certain string?
     *
     * @param action
     *            name of the action.
     * @param w
     *            the string to search for
     * @param initialWait
     *            wait this many seconds before checking lobs
     * @param pollPeriod
     *            poll at this interval in seconds
     * @param totalWait
     *            wait up to the this many seconds for the string to appear
     * @return
     * @throws IOException
     */
    private boolean logsForActionContain(String action, String sRegex, int initialWait, int pollPeriod, int totalWait) throws IOException {
        return matchLogsForActionContain(action, sRegex, initialWait, pollPeriod, totalWait) != null;
    }

    /**
     * Do the logs for a particular action contain a certain string?
     *
     * @param action
     *            name of the action.
     * @param w
     *            the string to search for
     * @param totalWait
     *            wait up to the this many seconds for the string to appear
     * @return
     * @throws IOException
     */
    public boolean logsForActionContain(String action, String w, int totalWait) throws IOException {
        String regex = ".*" + w + ".*";
        return logsForActionContain(action, regex, 1, 1, totalWait);
    }

    /**
     * Does the result for a particular activation contain a certain string? If
     * so, return the result. Else null.
     *
     * @param activationId
     *            the id of the activation
     * @param w
     *            the string to search for
     * @param initialWait
     *            wait this many seconds before checking logs
     * @param pollPeriod
     *            poll at this interval in seconds
     * @param totalWait
     *            wait up to the this many seconds for the activation record to
     *            appear
     * @return the activation result if there is match; otherwise null
     * @throws IOException
     */
    public String checkResultFor(String activationId, String sRegex, int initialWait, int pollPeriod, int totalWait) throws IOException {
        Pattern p = Pattern.compile(sRegex, Pattern.DOTALL);
        String log = TestUtils.waitfor(() -> {
            RunResult result = getResult(activationId);
            if (result.exitCode == SUCCESS_EXIT) {
                return result.stdout;
            } else
                return null;
        } , initialWait, pollPeriod, totalWait);
        if (log != null) {
            Matcher m = p.matcher(log);
            return m.find() ? log : null;
        } else
            return null;
    }

    /**
     * Does the result for a particular activation contain a certain string? If
     * so, return true else false.
     *
     * @param activationId
     *            the id of the activation
     * @param w
     *            the string to search for
     * @param totalWait
     *            wait up to the this many seconds for the activation record to
     *            appear
     * @return the activation result if there is match; otherwise null
     * @throws IOException
     */
    public boolean checkResultFor(String activationId, String sRegex, int totalWait) throws IOException {
        return checkResultFor(activationId, sRegex, 1, 1, totalWait) != null;
    }

    public static String extractActivationIdFromCliResult(String result) {
        assertTrue(result, result.contains("ok: invoked"));
        // a characteristic string that comes right before the activationId
        String idPrefix = "with id ";
        int start = result.indexOf(idPrefix) + idPrefix.length();
        int end = start;
        assertTrue(start > 0);
        while (end < result.length() && result.charAt(end) != '\n')
            end++;
        return result.substring(start, end); // a uuid
    }

    protected static String extractActivationResultFromCliResult(String result) {
        // a characteristic string that comes right before the result
        String resultPrefix = "{";
        assertTrue(result, result.contains(resultPrefix));
        int start = result.indexOf(resultPrefix);
        int end = result.length();
        return result.substring(start, end); // the result
    }

    /**
     * Run a wsk command. This will automatically add the auth key to the
     * command.
     */
    public RunResult runCmd(String... params) throws IOException {
        List<String> newParams = new ArrayList<String>();
        newParams.addAll(Arrays.asList(params));
        newParams.add("--auth");
        newParams.add(authKey);
        return cli(DONTCARE_EXIT, newParams.toArray(params));
    }

    /**
     * Run a command wsk [params].
     *
     * @return <stdout,sterr>
     * @throws IOException
     * @throws IllegalArgumentException
     */
    public RunResult cli(String... params) throws IllegalArgumentException, IOException {
        return cli(true, TestUtils.SUCCESS_EXIT, params);
    }

    /**
     * Run a command wsk [params] where the arguments come in as an array. Use
     * this instead of the other form with quoting.
     *
     * @param expectedExitCode
     *            if DONTCARE_EXIT then exit code is ignored, else check that
     *            exit code matches expected value
     * @return <stdout,sterr>
     */
    public RunResult cli(int expectedExitCode, String... params) throws IllegalArgumentException, IOException {
        return cli(false, expectedExitCode, params);
    }

    public RunResult cli(boolean verbose, int expectedExitCode, String... params) throws IllegalArgumentException, IOException {
        return cli(verbose, expectedExitCode, new File("."), params);
    }

    /**
     * Run a command wsk [params] where the arguments come in as an array. Use
     * this instead of the other form with quoting. Allows control of verbosity.
     *
     * @param verbose
     *            true to run with --verbose
     * @param expectedExitCode
     *            if DONTCARE_EXIT then exit code is ignored, else check that
     *            exit code matches expected value
     * @return RunResult which contains stdout,sterr, exit code
     */
    public RunResult cli(boolean verbose, int expectedExitCode, File workingDir, String... params) throws IllegalArgumentException, IOException {
        String[] cmd = verbose ? Util.concat(baseCmd, "--verbose") : baseCmd;
        String[] args = Util.concat(cmd, "-i", "--apihost", WhiskProperties.getEdgeHost());
        RunResult rr = TestUtils.runCmd(DONTCARE_EXIT, workingDir, TestUtils.logger, this.env, Util.concat(args, params));
        rr.validateExitCode(expectedExitCode);
        return rr;
    }

    public void setEnv(Map<String, String> env) {
        this.env = env;
    }
}
