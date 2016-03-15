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
import spray.json._

import com.google.gson.JsonObject

import ActionContainer.withContainer

import collection.JavaConverters._

@RunWith(classOf[JUnitRunner])
class JavaActionContainerTests extends FlatSpec with Matchers {

    // Helpers specific to javaaction
    def withJavaContainer(code: ActionContainer => Unit) = withContainer("whisk/javaaction")(code)
    def initPayload(mainClass: String, jar64: String) = JsObject(
        "value" -> JsObject(
            "name" -> JsString("dummyAction"),
            "main" -> JsString(mainClass),
            "jar"  -> JsString(jar64)))
    def runPayload(args: JsValue) = JsObject("value" -> args)

    behavior of "whisk/javaaction"

    it should "support valid flows" in {
        val (out,err) = withJavaContainer { c =>
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
                """.stripMargin.trim
            )

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
            val brokenJar =
                "UEsDBAoAAAAAAPxYbkhT4iFbCgAAAAoAAAANABwAbm90YWNsYXNzZmlsZVV" +
                "UCQADzNPmVszT5lZ1eAsAAQT1AQAABAAAAABzYXVjaXNzb24KUEsBAh4DCg" +
                "AAAAAA/FhuSFPiIVsKAAAACgAAAA0AGAAAAAAAAQAAAKSBAAAAAG5vdGFjb" +
                "GFzc2ZpbGVVVAUAA8zT5lZ1eAsAAQT1AQAABAAAAABQSwUGAAAAAAEAAQBT" +
                "AAAAUQAAAAAA"

            val (initCode, _) = c.init(initPayload("example.Broken", brokenJar))
            initCode should not be(200)
        }

        // Somewhere, the logs should contain an exception.
        val combined = out + err
        combined.toLowerCase should include("exception")
    }

    it should "return some error on action error" in {
        val (out,err) = withJavaContainer { c =>
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
                """.stripMargin.trim
            )

            val (initCode, _) = c.init(initPayload("example.HelloWhisk", jar))
            initCode should be(200)

            val (runCode, runRes) = c.run(runPayload(JsObject()))
            runCode should not be(200)

            runRes shouldBe defined
            runRes.get.fields.get("error") shouldBe defined
        }

        val combined = out + err
        combined.toLowerCase should include("exception")
    }

    it should "support application errors" in {
        val (out,err) = withJavaContainer { c =>
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
                """.stripMargin.trim
            )

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
                """.stripMargin.trim
            )

            val (initCode, _) = c.init(initPayload("example.Quitter", jar))
            initCode should be(200)

            val (runCode, runRes) = c.run(runPayload(JsObject()))
            runCode should not be(200)

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
                    |     public static JsonObject main(JsonObject main) {
                    |         return null;
                    |     }
                    | }
                """.stripMargin.trim
            )

            val (initCode, _) = c.init(initPayload("example.Nuller", jar))
            initCode should be(200)

            val (runCode, runRes) = c.run(runPayload(JsObject()))
            runCode should not be(200)

            runRes shouldBe defined
            runRes.get.fields.get("error") shouldBe defined
        }
    }
}

/**
 * A convenience object to compile and package Java sources into a JAR, and to
 * encode that JAR as a base 64 string. The compilation options include the
 * current classpath, which is why Google GSON is readily available (though not
 * packaged in the JAR).
 */
object JarBuilder {
    import java.net.URI
    import java.net.URLClassLoader
    import java.nio.file.Files
    import java.nio.file.Path
    import java.nio.file.Paths
    import java.nio.file.SimpleFileVisitor
    import java.nio.file.FileVisitResult
    import java.nio.file.FileSystems
    import java.nio.file.attribute.BasicFileAttributes
    import java.nio.charset.StandardCharsets
    import java.util.Base64;

    import javax.tools.ToolProvider
    import javax.tools.JavaFileManager
    import javax.tools.StandardJavaFileManager
    import javax.tools.ForwardingJavaFileManager

    def mkBase64Jar(sources: Seq[(Seq[String],String)]) : String = {
        // Note that this pipeline doesn't delete any of the temporary files.
        val binDir  = compile(sources)
        val jarPath = makeJar(binDir)
        val base64  = toBase64(jarPath)
        base64
    }

    def mkBase64Jar(source: (Seq[String],String)) : String = {
        mkBase64Jar(Seq(source))
    }

    private def compile(sources: Seq[(Seq[String],String)]) : Path = {
        require(!sources.isEmpty)

        // A temporary directory for the source files.
        val srcDir = Files.createTempDirectory("src").toAbsolutePath()

        // The absolute paths of the source file
        val srcAbsPaths = for((sourceName, sourceContent) <- sources) yield {
            // The relative path of the source file
            val srcRelPath = Paths.get(sourceName.head, sourceName.tail : _*)
            // The absolute path of the source file
            val srcAbsPath = srcDir.resolve(srcRelPath)
            // Create parent directories if needed.
            Files.createDirectories(srcAbsPath.getParent)
            // Writing contents
            Files.write(srcAbsPath, sourceContent.getBytes(StandardCharsets.UTF_8))

            srcAbsPath
        }

        // A temporary directory for the destination files.
        val binDir = Files.createTempDirectory("bin").toAbsolutePath()

        // Preparing the compiler
        val compiler = ToolProvider.getSystemJavaCompiler()
        val fileManager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)

        // Collecting all files to be compiled
        val compUnit = fileManager.getJavaFileObjectsFromFiles(srcAbsPaths.map(_.toFile).asJava)

        // Setting the options
        val compOptions = Seq(
            "-d", binDir.toAbsolutePath().toString(),
            "-classpath", buildClassPath()
        )
        val compTask = compiler.getTask(null, fileManager, null, compOptions.asJava, null, compUnit)

        // ...and off we go.
        compTask.call()

        binDir
    }

    private def buildClassPath() : String = {
        val bcp = System.getProperty("java.class.path")

        val list = this.getClass().getClassLoader() match {
            case ucl: URLClassLoader =>
                bcp :: ucl.getURLs().map(_.getFile().toString()).toList

            case _ =>
                List(bcp)
        }

        list.mkString(System.getProperty("path.separator"))
    }

    private def makeJar(binDir: Path) : Path = {
        // Any temporary file name for the jar.
        val jarPath = Files.createTempFile("output", ".jar").toAbsolutePath()
        val jarUri = new URI("jar:" + jarPath.toUri().getScheme(), jarPath.toAbsolutePath().toString(), null)

        // OK, that's a hack. Doing this because newFileSystem wants to create that file.
        jarPath.toFile().delete()

        // We "mount" it as a zip filesystem, so we can just copy files to it.
        val fs = FileSystems.newFileSystem(jarUri, Map(("create" -> "true")).asJava)

        // Traversing all files in the bin directory...
        Files.walkFileTree(binDir, new SimpleFileVisitor[Path]() {
            override def visitFile(path: Path, attributes: BasicFileAttributes) = {
                // The path relative to the bin dir
                val relPath = binDir.relativize(path)
                // The corresponding path in the jar
                val jarRelPath = fs.getPath(relPath.toString())

                // Creating the directory structure if it doesn't exist.
                if(!Files.exists(jarRelPath.getParent())) {
                    Files.createDirectories(jarRelPath.getParent())
                }

                // Finally we can copy that file.
                Files.copy(path, jarRelPath)

                FileVisitResult.CONTINUE
            }
        })

        fs.close()

        jarPath
    }

    private def toBase64(path: Path) : String = {
        val encoder = Base64.getEncoder()
        new String(encoder.encode(Files.readAllBytes(path)), StandardCharsets.UTF_8)
    }
}
