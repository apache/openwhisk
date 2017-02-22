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

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;

import com.google.gson.JsonObject;

public class JarLoader extends URLClassLoader {
    private final Class<?> mainClass;
    private final Method mainMethod;

    public static Path saveBase64EncodedFile(InputStream encoded) throws Exception {
        Base64.Decoder decoder = Base64.getDecoder();

        InputStream decoded = decoder.wrap(encoded);

        File destinationFile = File.createTempFile("useraction", ".jar");
        destinationFile.deleteOnExit();
        Path destinationPath = destinationFile.toPath();

        Files.copy(decoded, destinationPath, StandardCopyOption.REPLACE_EXISTING);

        return destinationPath;
    }

    public JarLoader(Path jarPath, String mainClassName)
            throws MalformedURLException, ClassNotFoundException, NoSuchMethodException, SecurityException {
        super(new URL[] { jarPath.toUri().toURL() });

        this.mainClass = loadClass(mainClassName);

        Method m = mainClass.getMethod("main", new Class[] { JsonObject.class });
        m.setAccessible(true);
        int modifiers = m.getModifiers();
        if (m.getReturnType() != JsonObject.class || !Modifier.isStatic(modifiers) || !Modifier.isPublic(modifiers)) {
            throw new NoSuchMethodException("main");
        }

        this.mainMethod = m;
    }

    public JsonObject invokeMain(JsonObject arg, Map<String, String> env) throws Exception {
        augmentEnv(env);
        return (JsonObject) mainMethod.invoke(null, arg);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void augmentEnv(Map<String, String> newEnv) {
        try {
            for (Class cl : Collections.class.getDeclaredClasses()) {
                if ("java.util.Collections$UnmodifiableMap".equals(cl.getName())) {
                    Field field = cl.getDeclaredField("m");
                    field.setAccessible(true);
                    Object obj = field.get(System.getenv());
                    Map<String, String> map = (Map<String, String>) obj;
                    map.putAll(newEnv);
                }
            }
        } catch (Exception e) {}
    }
}
