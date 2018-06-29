<!--
#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
-->

## Creating and invoking Java actions

The process of creating Java actions is similar to that of [other actions](actions.md#the-basics).
The following sections guide you through creating and invoking a single Java action,
and demonstrate how to bundle multiple files and third party dependencies.

In order to compile, test and archive Java files, you must have a
[JDK 8](http://www.oracle.com/technetwork/java/javase/downloads/index.html) installed locally.

A Java action is a Java program with a method called `main` that has the exact signature as follows:
```java
public static com.google.gson.JsonObject main(com.google.gson.JsonObject);
```

For example, create a Java file called `Hello.java` with the following content:

```java
import com.google.gson.JsonObject;

public class Hello {
    public static JsonObject main(JsonObject args) {
        String name = "stranger";
        if (args.has("name"))
            name = args.getAsJsonPrimitive("name").getAsString();
        JsonObject response = new JsonObject();
        response.addProperty("greeting", "Hello " + name + "!");
        return response;
    }
}
```

Then, compile `Hello.java` into a JAR file `hello.jar` as follows:
```
javac Hello.java
```
```
jar cvf hello.jar Hello.class
```

**Note:** [google-gson](https://github.com/google/gson) must exist in your Java CLASSPATH when compiling the Java file.

You can create a OpenWhisk action called `helloJava` from this JAR file as
follows:

```
wsk action create helloJava hello.jar --main Hello
```

When you use the command line and a `.jar` source file, you do not need to
specify that you are creating a Java action;
the tool determines that from the file extension.

You need to specify the name of the main class using `--main`. An eligible main
class is one that implements a static `main` method as described above. If the
class is not in the default package, use the Java fully-qualified class name,
e.g., `--main com.example.MyMain`.

If needed you can also customize the method name of your Java action. This
can be done by specifying the Java fully-qualified method name of your action,
e.q., `--main com.example.MyMain#methodName`

Action invocation is the same for Java actions as it is for Swift and JavaScript actions:

```
wsk action invoke --result helloJava --param name World
```

```json
  {
      "greeting": "Hello World!"
  }
```

Find out more about parameters in the [Working with parameters](./parameters.md) section.
