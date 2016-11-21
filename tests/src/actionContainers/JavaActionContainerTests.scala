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

package actionContainers

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner
import spray.json.DefaultJsonProtocol._
import spray.json._

import ActionContainer.withContainer
import ResourceHelpers.JarBuilder

import common.WskActorSystem

@RunWith(classOf[JUnitRunner])
class JavaActionContainerTests extends FlatSpec with Matchers with WskActorSystem with ActionProxyContainerTestUtils {

    // Helpers specific to javaaction
    def withJavaContainer(code: ActionContainer => Unit, env: Map[String, String] = Map.empty) = withContainer("javaaction", env)(code)

    override def initPayload(mainClass: String, jar64: String) = JsObject(
        "value" -> JsObject(
            "name" -> JsString("dummyAction"),
            "main" -> JsString(mainClass),
            "jar" -> JsString(jar64)))

    behavior of "Java action"

    it should s"run a java snippet and confirm expected environment variables" in {
        val props = Seq("api_host" -> "xyz",
            "api_key" -> "abc",
            "namespace" -> "zzz",
            "action_name" -> "xxx",
            "activation_id" -> "iii",
            "deadline" -> "123")
        val env = props.map { case (k, v) => s"__OW_${k.toUpperCase}" -> v }
        val (out, err) = withJavaContainer({ c =>
            val jar = JarBuilder.mkBase64Jar(
                Seq("example", "HelloWhisk.java") -> """
                    | package example;
                    |
                    | import com.google.gson.JsonObject;
                    |
                    | public class HelloWhisk {
                    |     public static JsonObject main(JsonObject args) {
                    |         JsonObject response = new JsonObject();
                    |         response.addProperty("api_host", System.getenv("__OW_API_HOST"));
                    |         response.addProperty("api_key", System.getenv("__OW_API_KEY"));
                    |         response.addProperty("namespace", System.getenv("__OW_NAMESPACE"));
                    |         response.addProperty("action_name", System.getenv("__OW_ACTION_NAME"));
                    |         response.addProperty("activation_id", System.getenv("__OW_ACTIVATION_ID"));
                    |         response.addProperty("deadline", System.getenv("__OW_DEADLINE"));
                    |         return response;
                    |     }
                    | }
                """.stripMargin.trim)

            val (initCode, _) = c.init(initPayload("example.HelloWhisk", jar))
            initCode should be(200)

            val (runCode, out) = c.run(runPayload(JsObject(), Some(props.toMap.toJson.asJsObject)))
            runCode should be(200)
            props.map {
                case (k, v) => out.get.fields(k) shouldBe JsString(v)

            }
        }, env.take(1).toMap)

        out.trim shouldBe empty
        err.trim shouldBe empty
    }

    it should "support valid flows" in {
        val (out, err) = withJavaContainer { c =>
            val jar = JarBuilder.mkBase64Jar(
                Seq("example", "HelloWhisk.java") -> """
                    | package example;
                    |
                    | import com.google.gson.JsonObject;
                    |
                    | public class HelloWhisk {
                    |     public static JsonObject main(JsonObject args) {
                    |         String name = args.getAsJsonPrimitive("name").getAsString();
                    |         JsonObject response = new JsonObject();
                    |         response.addProperty("greeting", "Hello " + name + "!");
                    |         return response;
                    |     }
                    | }
                """.stripMargin.trim)

            val (initCode, _) = c.init(initPayload("example.HelloWhisk", jar))
            initCode should be(200)

            val (runCode1, out1) = c.run(runPayload(JsObject("name" -> JsString("Whisk"))))
            runCode1 should be(200)
            out1 should be(Some(JsObject("greeting" -> JsString("Hello Whisk!"))))

            val (runCode2, out2) = c.run(runPayload(JsObject("name" -> JsString("ksihW"))))
            runCode2 should be(200)
            out2 should be(Some(JsObject("greeting" -> JsString("Hello ksihW!"))))
        }

        out.trim shouldBe empty
        err.trim shouldBe empty
    }

    it should "fail to initialize with bad code" in {
        val (out, err) = withJavaContainer { c =>
            // This is valid zip file containing a single file, but not a valid
            // jar file.
            val brokenJar = (
                "UEsDBAoAAAAAAPxYbkhT4iFbCgAAAAoAAAANABwAbm90YWNsYXNzZmlsZVV" +
                "UCQADzNPmVszT5lZ1eAsAAQT1AQAABAAAAABzYXVjaXNzb24KUEsBAh4DCg" +
                "AAAAAA/FhuSFPiIVsKAAAACgAAAA0AGAAAAAAAAQAAAKSBAAAAAG5vdGFjb" +
                "GFzc2ZpbGVVVAUAA8zT5lZ1eAsAAQT1AQAABAAAAABQSwUGAAAAAAEAAQBT" +
                "AAAAUQAAAAAA")

            val (initCode, _) = c.init(initPayload("example.Broken", brokenJar))
            initCode should not be (200)
        }

        // Somewhere, the logs should contain an exception.
        val combined = out + err
        combined.toLowerCase should include("exception")
    }

    it should "return some error on action error" in {
        val (out, err) = withJavaContainer { c =>
            val jar = JarBuilder.mkBase64Jar(
                Seq("example", "HelloWhisk.java") -> """
                    | package example;
                    |
                    | import com.google.gson.JsonObject;
                    |
                    | public class HelloWhisk {
                    |     public static JsonObject main(JsonObject args) throws Exception {
                    |         throw new Exception("noooooooo");
                    |     }
                    | }
                """.stripMargin.trim)

            val (initCode, _) = c.init(initPayload("example.HelloWhisk", jar))
            initCode should be(200)

            val (runCode, runRes) = c.run(runPayload(JsObject()))
            runCode should not be (200)

            runRes shouldBe defined
            runRes.get.fields.get("error") shouldBe defined
        }

        val combined = out + err
        combined.toLowerCase should include("exception")
    }

    it should "support application errors" in {
        val (out, err) = withJavaContainer { c =>
            val jar = JarBuilder.mkBase64Jar(
                Seq("example", "Error.java") -> """
                    | package example;
                    |
                    | import com.google.gson.JsonObject;
                    |
                    | public class Error {
                    |     public static JsonObject main(JsonObject args) throws Exception {
                    |         JsonObject error = new JsonObject();
                    |         error.addProperty("error", "This action is unhappy.");
                    |         return error;
                    |     }
                    | }
                """.stripMargin.trim)

            val (initCode, _) = c.init(initPayload("example.Error", jar))
            initCode should be(200)

            val (runCode, runRes) = c.run(runPayload(JsObject()))
            runCode should be(200) // action writer returning an error is OK

            runRes shouldBe defined
            runRes.get.fields.get("error") shouldBe defined
        }

        val combined = out + err
        combined.trim shouldBe empty
    }

    it should "survive System.exit" in {
        val (out, err) = withJavaContainer { c =>
            val jar = JarBuilder.mkBase64Jar(
                Seq("example", "Quitter.java") -> """
                    | package example;
                    |
                    | import com.google.gson.*;
                    |
                    | public class Quitter {
                    |     public static JsonObject main(JsonObject main) {
                    |         System.exit(1);
                    |         return new JsonObject();
                    |     }
                    | }
                """.stripMargin.trim)

            val (initCode, _) = c.init(initPayload("example.Quitter", jar))
            initCode should be(200)

            val (runCode, runRes) = c.run(runPayload(JsObject()))
            runCode should not be (200)

            runRes shouldBe defined
            runRes.get.fields.get("error") shouldBe defined
        }

        val combined = out + err
        combined.toLowerCase should include("system.exit")
    }

    it should "enforce that the user returns an object" in {
        withJavaContainer { c =>
            val jar = JarBuilder.mkBase64Jar(
                Seq("example", "Nuller.java") -> """
                    | package example;
                    |
                    | import com.google.gson.*;
                    |
                    | public class Nuller {
                    |     public static JsonObject main(JsonObject args) {
                    |         return null;
                    |     }
                    | }
                """.stripMargin.trim)

            val (initCode, _) = c.init(initPayload("example.Nuller", jar))
            initCode should be(200)

            val (runCode, runRes) = c.run(runPayload(JsObject()))
            runCode should not be (200)

            runRes shouldBe defined
            runRes.get.fields.get("error") shouldBe defined
        }
    }

    val dynamicLoadingJar = JarBuilder.mkBase64Jar(
        Seq(
            Seq("example", "EntryPoint.java") -> """
                | package example;
                |
                | import com.google.gson.*;
                | import java.lang.reflect.*;
                |
                | public class EntryPoint {
                |     private final static String CLASS_NAME = "example.DynamicClass";
                |     public static JsonObject main(JsonObject args) throws Exception {
                |         String cl = args.getAsJsonPrimitive("classLoader").getAsString();
                |
                |         Class d = null;
                |         if("local".equals(cl)) {
                |             d = Class.forName(CLASS_NAME);
                |         } else if("thread".equals(cl)) {
                |             d = Thread.currentThread().getContextClassLoader().loadClass(CLASS_NAME);
                |         }
                |
                |         Object o = d.newInstance();
                |         Method m = o.getClass().getMethod("getMessage");
                |         String msg = (String)m.invoke(o);
                |
                |         JsonObject response = new JsonObject();
                |         response.addProperty("message", msg);
                |         return response;
                |     }
                | }
                |""".stripMargin.trim,
            Seq("example", "DynamicClass.java") -> """
                | package example;
                |
                | public class DynamicClass {
                |     public String getMessage() {
                |         return "dynamic!";
                |     }
                | }
                |""".stripMargin.trim))

    def classLoaderTest(param: String) = {
        val (out, err) = withJavaContainer { c =>
            val (initCode, _) = c.init(initPayload("example.EntryPoint", dynamicLoadingJar))
            initCode should be(200)

            val (runCode, runRes) = c.run(runPayload(JsObject("classLoader" -> JsString(param))))
            runCode should be(200)

            runRes shouldBe defined
            runRes.get.fields.get("message") shouldBe Some(JsString("dynamic!"))
        }
        (out ++ err).trim shouldBe empty
    }

    it should "support loading classes from the current classloader" in {
        classLoaderTest("local")
    }

    it should "support loading classes from the Thread classloader" in {
        classLoaderTest("thread")
    }
}
