/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package common;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Properties;

import static org.junit.Assert.assertTrue;

/**
 * Properties that describe a whisk installation
 */
public class WhiskProperties {

    /**
     * System property key which refers to OpenWhisk Edge Host url
     */
    public static final String WHISK_SERVER = "whisk.server";

    /**
     * System property key which refers to authentication key to be used for testing
     */
    private static final String WHISK_AUTH = "whisk.auth";

    /**
     * The name of the properties file.
     */
    protected static final String WHISK_PROPS_FILE = "whisk.properties";

    /**
     * Default concurrency level if otherwise unspecified
     */
    private static final int DEFAULT_CONCURRENCY = 20;

    /**
     * If true, then tests will direct to the router rather than the edge
     * components.
     */
    public static final boolean testRouter = System.getProperty("test.router", "false").equals("true");

    /**
     * The root of the whisk installation, used to retrieve files relative to
     * home.
     */
    private static final String whiskHome;

    /**
     * The properties read from the WHISK_PROPS_FILE.
     */
    private static final Properties whiskProperties;

    static {
        /**
         * Finds the whisk home directory. This is resolved to either (in
         * order):
         *
         * 1. a system property openwhisk.dir
         *
         * 2. OPENWHISK_HOME from the environment
         *
         * 3. a path in the directory tree containing WHISK_PROPS_FILE.
         *
         * @return the path to whisk home as a string
         * @throws assertion
         *             failure if whisk home cannot be determined
         */
        String wskdir = System.getProperty("openwhisk.home", System.getenv("OPENWHISK_HOME"));
        if (wskdir == null) {
            String dir = System.getProperty("user.dir");

            if (dir != null) {
                File propfile = findFileRecursively(dir, WHISK_PROPS_FILE);
                if (propfile != null) {
                    wskdir = propfile.getParent();
                }
            }
        }

        assertTrue("could not determine openwhisk home", wskdir != null);

        if (isWhiskPropertiesRequired()) {
            File wskpropsFile = new File(wskdir, WHISK_PROPS_FILE);
            assertTrue(String.format("'%s' does not exists but required", wskpropsFile), wskpropsFile.exists());

            // loads properties from file
            whiskProperties = loadProperties(wskpropsFile);

            // set whisk home from read properties
            whiskHome = whiskProperties.getProperty("openwhisk.home");
        } else {
            whiskProperties = new Properties();
            whiskHome = wskdir;
        }

        System.out.format("test router? %s\n", testRouter);
    }

    /**
     * The path to the Go CLI executable.
     */
    public static String getCLIPath() {
        return whiskHome + "/bin/wsk";
    }

    public static File getFileRelativeToWhiskHome(String name) {
        return new File(whiskHome, name);
    }

    public static int getControllerInstances() {
        return Integer.parseInt(whiskProperties.getProperty("controller.instances"));
    }

    public static String getProperty(String name) {
        return whiskProperties.getProperty(name);
    }

    public static Boolean getBooleanProperty(String name, Boolean defaultValue) {
        String value = whiskProperties.getProperty(name);
        if (value == null) {
            return defaultValue;
        }

        return Boolean.parseBoolean(value);
    }

    public static String getKafkaHosts() {
        return whiskProperties.getProperty("kafka.hosts");
    }

    public static int getKafkaMonitorPort() {
        return Integer.parseInt(whiskProperties.getProperty("kafkaras.host.port"));
    }

    public static String getZookeeperHost() {
        return whiskProperties.getProperty("zookeeper.hosts");
    }

    public static String getMainDockerEndpoint() {
        return whiskProperties.getProperty("main.docker.endpoint");
    }

    public static String[] getInvokerHosts() {
        // split of empty string is non-empty array
        String hosts = whiskProperties.getProperty("invoker.hosts");
        return (hosts == null || hosts.equals("")) ? new String[0] : hosts.split(",");
    }

    public static String[] getAdditionalHosts() {
        // split of empty string is non-empty array
        String hosts = whiskProperties.getProperty("additional.hosts");
        return (hosts == null || hosts.equals("")) ? new String[0] : hosts.split(",");
    }

    public static int numberOfInvokers() {
        return getInvokerHosts().length;
    }

    public static boolean isSSLCheckRelaxed() {
        return Boolean.valueOf(getPropFromSystemOrEnv("whisk.ssl.relax"));
    }

    public static String getSslCertificateChallenge() {
        return whiskProperties.getProperty("whisk.ssl.challenge");
    }

    /**
     * Note that when testRouter == true, we pretend the router host is edge
     * host.
     */
    public static String getEdgeHost() {
        String server = getPropFromSystemOrEnv(WHISK_SERVER);
        if (server != null) {
            return server;
        }
        return testRouter ? getRouterHost() : whiskProperties.getProperty("edge.host");
    }

    public static String getRealEdgeHost() {
        return whiskProperties.getProperty("edge.host");
    }

    public static String getAuthForTesting() {
        return whiskProperties.getProperty("testing.auth");
    }

    public static String getRouterHost() {
        return whiskProperties.getProperty("router.host");
    }

    public static String getApiProto() {
        return whiskProperties.getProperty("whisk.api.host.proto");
    }

    public static String getApiHost() {
        return whiskProperties.getProperty("whisk.api.host.name");
    }

    public static String getApiPort() {
        return whiskProperties.getProperty("whisk.api.host.port");
    }

    public static String getApiHostForAction() {
        String apihost = getApiProto() + "://" + getApiHost() + ":" + getApiPort();
        if (apihost.startsWith("https://") && apihost.endsWith(":443")) {
            return apihost.replaceAll(":443", "");
        } else if (apihost.startsWith("http://") && apihost.endsWith(":80")) {
            return apihost.replaceAll(":80", "");
        } else return apihost;
    }

    public static String getApiHostForClient(String subdomain, boolean includeProtocol) {
        String proto = whiskProperties.getProperty("whisk.api.host.proto");
        String port = whiskProperties.getProperty("whisk.api.host.port");
        String host = whiskProperties.getProperty("whisk.api.localhost.name");
        if (includeProtocol) {
            return proto + "://" + subdomain + "." + host + ":" + port;
        } else {
            return subdomain + "." + host + ":" + port;
        }
    }

    public static int getPartsInVanitySubdomain() {
        return Integer.parseInt(whiskProperties.getProperty("whisk.api.vanity.subdomain.parts"));
    }

    public static int getEdgeHostApiPort() {
        return Integer.parseInt(whiskProperties.getProperty("edge.host.apiport"));
    }

    public static String getControllerHosts() {
        return whiskProperties.getProperty("controller.hosts");
    }

    public static String getDBHosts() {
        return whiskProperties.getProperty("db.hostsList");
    }

    public static int getControllerBasePort() {
        return Integer.parseInt(whiskProperties.getProperty("controller.host.basePort"));
    }

    public static String getBaseControllerHost() {
        return getControllerHosts().split(",")[0];
    }

    public static String getBaseDBHost() {
        return getDBHosts().split(",")[0];
    }

    public static String getBaseControllerAddress() {
        return getBaseControllerHost() + ":" + getControllerBasePort();
    }

    public static String getBaseInvokerAddress(){
        return getInvokerHosts()[0] + ":" + whiskProperties.getProperty("invoker.hosts.basePort");
    }

    public static int getMaxActionInvokesPerMinute() {
        String valStr = whiskProperties.getProperty("limits.actions.invokes.perMinute");
        return Integer.parseInt(valStr);
    }

    /**
     * read the contents of auth key file and return as a Pair
     * <username,password>
     */
    public static Pair getBasicAuth() {
        File f = getAuthFileForTesting();
        String contents = readAuthKey(f);
        String[] parts = contents.split(":");
        assert parts.length == 2;
        return new Pair(parts[0], parts[1]);
    }

    /**
     * @return the path to a file holding the auth key used during junit testing
     */
    public static File getAuthFileForTesting() {
        String testAuth = getAuthForTesting();
        if (testAuth.startsWith(File.separator)) {
            return new File(testAuth);
        } else {
            return WhiskProperties.getFileRelativeToWhiskHome(testAuth);
        }
    }

    /**
     * read the contents of a file which holds an auth key.
     */
    public static String readAuthKey(File filename) {
        // the following funny relative path works both from Eclipse and when
        // running in bin/ directory from ant
        try {
            byte[] encoded = Files.readAllBytes(filename.toPath());
            String authKey = new String(encoded, "UTF-8").trim();
            return authKey;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Returns auth key to be used for testing
     */
    public static String getAuthKeyForTesting() {
        String authKey = getPropFromSystemOrEnv(WHISK_AUTH);
        if (authKey == null) {
            authKey = readAuthKey(getAuthFileForTesting());
        }
        return authKey;
    }

    /**
     * @return the path to a file holding the VCAP_SERVICES used during junit
     *         testing
     */
    public static File getVCAPServicesFile() {
        String vcapServices = whiskProperties.getProperty("vcap.services.file");
        if (vcapServices == null) {
            return null;
        } else if (vcapServices.startsWith(File.separator)) {
            return new File(vcapServices);
        } else {
            return WhiskProperties.getFileRelativeToWhiskHome(vcapServices);
        }
    }

    /**
     * are we running on Mac OS X?
     */
    public static boolean onMacOSX() {
        String osname = System.getProperty("os.name");
        return osname.toLowerCase().contains("mac");
    }

    /**
     * are we running on Linux?
     */
    public static boolean onLinux() {
        String osname = System.getProperty("os.name");
        return osname.equalsIgnoreCase("linux");
    }

    public static int getMaxActionSizeMB(){
        return Integer.parseInt(getProperty("whisk.action.size.max", "10"));
    }

    /**
     * python interpreter.
     */
    public static final String python = "python";

    protected static File findFileRecursively(String dir, String needle) {
        if (dir != null) {
            File base = new File(dir);
            File file = new File(base, needle);
            if (file.exists()) {
                return file;
            } else {
                return findFileRecursively(base.getParent(), needle);
            }
        } else {
            return null;
        }
    }

    /**
     * Load properties from whisk.properties
     */
    protected static Properties loadProperties(File propsFile) {
        Properties props = new Properties();
        InputStream input = null;

        try {
            input = new FileInputStream(propsFile);
            // load a properties file
            props.load(input);
        } catch (IOException ex) {
            ex.printStackTrace();
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return props;
    }

    private static boolean isWhiskPropertiesRequired() {
        return getPropFromSystemOrEnv(WHISK_SERVER) == null;
    }

    public static String getProperty(String key, String defaultValue) {
        String value = getPropFromSystemOrEnv(key);
        if (value == null) {
            value = whiskProperties.getProperty(key, defaultValue);
        }
        return value;
    }

    private static String getPropFromSystemOrEnv(String key) {
        String value = System.getProperty(key);
        if (value == null) {
            value = System.getenv(toEnvName(key));
        }
        return value;
    }

    private static String toEnvName(String p) {
        return p.replace('.', '_').toUpperCase();
    }
}
