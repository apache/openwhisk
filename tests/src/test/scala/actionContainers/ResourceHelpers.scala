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

package actionContainers

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
import java.util.Base64

import javax.tools.ToolProvider

import collection.JavaConverters._

/**
 * A collection of utility objects to create ephemeral action resources based
 *  on file contents.
 */
object ResourceHelpers {

  /** Creates a zip file based on the contents of a top-level directory. */
  object ZipBuilder {
    def mkBase64Zip(sources: Seq[(Seq[String], String)]): String = {
      val (tmpDir, _) = writeSourcesToTempDirectory(sources)
      val archive = makeZipFromDir(tmpDir)
      readAsBase64(archive)
    }
  }

  /**
   * A convenience object to compile and package Java sources into a JAR, and to
   * encode that JAR as a base 64 string. The compilation options include the
   * current classpath, which is why Google GSON is readily available (though not
   * packaged in the JAR).
   */
  object JarBuilder {
    def mkBase64Jar(sources: Seq[(Seq[String], String)]): String = {
      // Note that this pipeline doesn't delete any of the temporary files.
      val binDir = compile(sources)
      val jarPath = makeJarFromDir(binDir)
      val base64 = readAsBase64(jarPath)
      base64
    }

    def mkBase64Jar(source: (Seq[String], String)): String = {
      mkBase64Jar(Seq(source))
    }

    private def compile(sources: Seq[(Seq[String], String)]): Path = {
      require(!sources.isEmpty)

      // The absolute paths of the source file
      val (srcDir, srcAbsPaths) = writeSourcesToTempDirectory(sources)

      // A temporary directory for the destination files.
      val binDir = Files.createTempDirectory("bin").toAbsolutePath()

      // Preparing the compiler
      val compiler = ToolProvider.getSystemJavaCompiler()
      val fileManager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)

      // Collecting all files to be compiled
      val compUnit = fileManager.getJavaFileObjectsFromFiles(srcAbsPaths.map(_.toFile).asJava)

      // Setting the options
      val compOptions = Seq("-d", binDir.toAbsolutePath().toString(), "-classpath", buildClassPath())
      val compTask = compiler.getTask(null, fileManager, null, compOptions.asJava, null, compUnit)

      // ...and off we go.
      compTask.call()

      binDir
    }

    private def buildClassPath(): String = {
      val bcp = System.getProperty("java.class.path")

      val list = this.getClass().getClassLoader() match {
        case ucl: URLClassLoader =>
          bcp :: ucl.getURLs().map(_.getFile().toString()).toList

        case _ =>
          List(bcp)
      }

      list.mkString(System.getProperty("path.separator"))
    }
  }

  /**
   * Creates a temporary directory and reproduces the desired file structure
   * in it. Returns the path of the temporary directory and the path of each
   * file as represented in it.
   */
  private def writeSourcesToTempDirectory(sources: Seq[(Seq[String], String)]): (Path, Seq[Path]) = {
    // A temporary directory for the source files.
    val srcDir = Files.createTempDirectory("src").toAbsolutePath()

    val srcAbsPaths = for ((sourceName, sourceContent) <- sources) yield {
      // The relative path of the source file
      val srcRelPath = Paths.get(sourceName.head, sourceName.tail: _*)
      // The absolute path of the source file
      val srcAbsPath = srcDir.resolve(srcRelPath)
      // Create parent directories if needed.
      Files.createDirectories(srcAbsPath.getParent)
      // Writing contents
      Files.write(srcAbsPath, sourceContent.getBytes(StandardCharsets.UTF_8))

      srcAbsPath
    }

    (srcDir, srcAbsPaths)
  }

  private def makeZipFromDir(dir: Path): Path = makeArchiveFromDir(dir, ".zip")

  private def makeJarFromDir(dir: Path): Path = makeArchiveFromDir(dir, ".jar")

  /**
   * Compresses all files beyond a directory into a zip file.
   * Note that Jar files are just zip files.
   */
  private def makeArchiveFromDir(dir: Path, extension: String): Path = {
    // Any temporary file name for the archive.
    val arPath = Files.createTempFile("output", extension).toAbsolutePath()

    // We "mount" it as a filesystem, so we can just copy files into it.
    val dstUri = new URI("jar:" + arPath.toUri().getScheme(), arPath.toAbsolutePath().toString(), null)
    // OK, that's a hack. Doing this because newFileSystem wants to create that file.
    arPath.toFile().delete()
    val fs = FileSystems.newFileSystem(dstUri, Map(("create" -> "true")).asJava)

    // Traversing all files in the bin directory...
    Files.walkFileTree(
      dir,
      new SimpleFileVisitor[Path]() {
        override def visitFile(path: Path, attributes: BasicFileAttributes) = {
          // The path relative to the src dir
          val relPath = dir.relativize(path)

          // The corresponding path in the zip
          val arRelPath = fs.getPath(relPath.toString())

          // If this file is not top-level in the src dir...
          if (relPath.getParent() != null) {
            // ...create the directory structure if it doesn't exist.
            if (!Files.exists(arRelPath.getParent())) {
              Files.createDirectories(arRelPath.getParent())
            }
          }

          // Finally we can copy that file.
          Files.copy(path, arRelPath)

          FileVisitResult.CONTINUE
        }
      })

    fs.close()

    arPath
  }

  /** Reads the contents of a (possibly binary) file into a base64-encoded String */
  def readAsBase64(path: Path): String = {
    val encoder = Base64.getEncoder()
    new String(encoder.encode(Files.readAllBytes(path)), StandardCharsets.UTF_8)
  }
}
