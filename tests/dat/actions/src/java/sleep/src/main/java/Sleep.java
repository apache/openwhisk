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

/**
 * Build instructions:
 * - Assumption: the dependency GSON is in the local dicrectory, e.g. "gson-2.8.2.jar"
 * - Compile with "javac -cp gson-2.8.2.jar Sleep.java"
 * - Create .jar archive with "jar cvf sleep.jar Sleep.class"
 */

/**
 * Java based OpenWhisk action that sleeps for the specified number
 * of milliseconds before returning.
 * The function actually sleeps slightly longer than requested.
 *
 * @param parm JSON object with Number property sleepTimeInMs
 * @returns JSON object with String property msg describing how long the function slept
 */

import com.google.gson.JsonObject;
public class Sleep {
    public static JsonObject main(JsonObject parm) throws InterruptedException {
        int sleepTimeInMs = 1;
        if (parm.has("sleepTimeInMs")) {
            sleepTimeInMs = parm.getAsJsonPrimitive("sleepTimeInMs").getAsInt();
        }
        System.out.println("Specified sleep time is " + sleepTimeInMs + " ms.");

        final String responseText = "Terminated successfully after around " + sleepTimeInMs + " ms.";
        final JsonObject response = new JsonObject();
        response.addProperty("msg", responseText);

        Thread.sleep(sleepTimeInMs);

        System.out.println(responseText);
        return response;
    }
}
