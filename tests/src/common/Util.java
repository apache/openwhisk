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

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.apache.commons.codec.binary.Base64;

/**
 * Misc utility.
 */
public class Util {

    public static void sleep(double durSec) {
        try {
            Thread.sleep((int) (durSec * 1000));
        } catch (InterruptedException ie) {
            // Should we have figured out when we started and resumed sleeping
            // for remaining amount?
        }
    }

    // Java8 would make this unnecessary.
    public static String join(String[] strs, String sep) {
        StringBuilder sb = new StringBuilder();
        String separator = "";
        for (String s : strs) {
            sb.append(separator);
            sb.append(s);
            separator = sep;
        }
        return sb.toString();
    }

    public static String join(List<String> strs, String sep) {
        return join(strs.toArray(new String[strs.size()]), sep);
    }

    private static DateFormat dateFormatter = new SimpleDateFormat("HH:mm:ss");

    public static synchronized String getTimeString() {
        return dateFormatter.format(new Date());
    }

    public static <T> T[] concat(T[] first, @SuppressWarnings("unchecked") T... second) {
        T[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    // Read a binary file and base64 encode the result.
    public static byte[] readFile64(File file) {
        return Base64.encodeBase64(readFile(file));
    }

    // Write the given base64 data as binary to file.
    public static boolean writeFile64(File file, byte[] base64Data) {
        return writeFile(file, Base64.decodeBase64(base64Data));
    }

    // Read a binary file
    public static byte[] readFile(File file) {
        try {
            return Files.readAllBytes(file.toPath());
        } catch (Exception e) {
            System.out.println("Could not read file " + file + ": " + e);
            return null;
        }
    }

    // Write a binary file
    public static boolean writeFile(File file, byte[] data) {
        try {
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(data, 0, data.length);
            fos.flush();
            fos.close();
            return true;
        } catch (Exception e) {
            System.out.println("writeFile failed with " + e);
            return false;
        }
    }
}
