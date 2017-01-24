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
package openwhisk.java.action;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

public class Proxy {
    private HttpServer server;

    private JarLoader loader = null;

    public Proxy(int port) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(port), -1);

        this.server.createContext("/init", new InitHandler());
        this.server.createContext("/run", new RunHandler());
        this.server.setExecutor(null); // creates a default executor
    }

    public void start() {
        server.start();
    }

    private static void writeResponse(HttpExchange t, int code, String content) throws IOException {
        byte[] bytes = content.getBytes("UTF-8");
        t.sendResponseHeaders(code, bytes.length);
        OutputStream os = t.getResponseBody();
        os.write(bytes);
        os.close();
    }

    private static void writeError(HttpExchange t, String errorMessage) throws IOException {
        JsonObject message = new JsonObject();
        message.addProperty("error", errorMessage);
        writeResponse(t, 502, message.toString());
    }

    private class InitHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (loader != null) {
                Proxy.writeError(t, "Cannot initialize the action more than once.");
                return;
            }

            try {
                InputStream is = t.getRequestBody();
                JsonParser parser = new JsonParser();
                JsonElement ie = parser.parse(new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8)));
                JsonObject inputObject = ie.getAsJsonObject();

                JsonObject message = inputObject.getAsJsonObject("value");
                String mainClass = message.getAsJsonPrimitive("main").getAsString();
                String base64Jar = message.getAsJsonPrimitive("jar").getAsString();

                // FIXME: this is obviously not very useful. The idea is that we
                // will implement/use
                // a streaming parser for the incoming JSON object so that we
                // can stream the contents
                // of the jar straight to a file.
                InputStream jarIs = new ByteArrayInputStream(base64Jar.getBytes(StandardCharsets.UTF_8));

                // Save the bytes to a file.
                Path jarPath = JarLoader.saveBase64EncodedFile(jarIs);

                // Start up the custom classloader. This also checks that the
                // main method exists.
                loader = new JarLoader(jarPath, mainClass);

                Proxy.writeResponse(t, 200, "OK");
                return;
            } catch (Exception e) {
                e.printStackTrace(System.err);
                Proxy.writeError(t, "An error has occurred (see logs for details): " + e);
                return;
            }
        }
    }

    private class RunHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (loader == null) {
                Proxy.writeError(t, "Cannot invoke an uninitialized action.");
                return;
            }

            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            SecurityManager sm = System.getSecurityManager();

            try {
                InputStream is = t.getRequestBody();
                JsonParser parser = new JsonParser();
                JsonElement ie = parser.parse(new BufferedReader(new InputStreamReader(is, "UTF-8")));
                JsonObject inputObject = ie.getAsJsonObject().getAsJsonObject("value");

                HashMap<String, String> env = new HashMap<String, String>();
                for (String p : new String[] { "api_key", "namespace", "action_name", "activation_id", "deadline" }) {
                    try {
                        String val = ie.getAsJsonObject().getAsJsonPrimitive(p).getAsString();
                        env.put(String.format("__OW_%s", p.toUpperCase()), val);
                    } catch (Exception e) {}
                }

                Thread.currentThread().setContextClassLoader(loader);
                System.setSecurityManager(new WhiskSecurityManager());

                // User code starts running here.
                JsonObject output = loader.invokeMain(inputObject, env);
                // User code finished running here.

                if(output == null) {
                    throw new NullPointerException("The action returned null");
                }

                Proxy.writeResponse(t, 200, output.toString());
                return;
            } catch (InvocationTargetException ite) {
                // These are exceptions from the action, wrapped in ite because
                // of reflection
                Throwable underlying = ite.getCause();
                underlying.printStackTrace(System.err);
                Proxy.writeError(t,
                        "An error has occured while invoking the action (see logs for details): " + underlying);
            } catch (Exception e) {
                e.printStackTrace(System.err);
                Proxy.writeError(t, "An error has occurred (see logs for details): " + e);
            } finally {
                System.setSecurityManager(sm);
                Thread.currentThread().setContextClassLoader(cl);
            }
        }
    }

    public static void main(String args[]) throws Exception {
        Proxy proxy = new Proxy(8080);
        proxy.start();
    }
}
