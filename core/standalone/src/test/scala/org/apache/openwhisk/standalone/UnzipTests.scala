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

package org.apache.openwhisk.standalone

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.util.zip.{ZipEntry, ZipOutputStream}

import org.apache.commons.io.FileUtils
import org.junit.runner.RunWith
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class UnzipTests extends AnyFlatSpec with Matchers {
  behavior of "Unzip"

  it should "extract nested files from a zip stream" in {
    val zipBytes = new ByteArrayOutputStream()
    val zip = new ZipOutputStream(zipBytes)
    val entries = Seq("provisioning/datasources/datasource.yml" -> "apiVersion: 1\n", "dashboard.json" -> "{}\n")

    entries.foreach {
      case (name, contents) =>
        zip.putNextEntry(new ZipEntry(name))
        zip.write(contents.getBytes(UTF_8))
        zip.closeEntry()
    }
    zip.close()

    val testRoot = Files.createDirectories(java.nio.file.Paths.get("build/tmp"))
    val destination = Files.createTempDirectory(testRoot, "unzip-test-")
    try {
      Unzip(new ByteArrayInputStream(zipBytes.toByteArray), destination.toFile)

      new String(Files.readAllBytes(destination.resolve("provisioning/datasources/datasource.yml")), UTF_8) shouldBe
        "apiVersion: 1\n"
      new String(Files.readAllBytes(destination.resolve("dashboard.json")), UTF_8) shouldBe "{}\n"
    } finally {
      FileUtils.deleteDirectory(destination.toFile)
    }
  }
}
