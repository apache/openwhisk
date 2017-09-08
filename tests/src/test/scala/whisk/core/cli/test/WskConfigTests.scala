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

package whisk.core.cli.test

import java.io.File
import java.io.BufferedWriter
import java.io.FileWriter
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.apache.commons.io.FileUtils

import common.WhiskProperties
import common.TestHelpers
import common.TestUtils._
import common.Wsk
import common.WskProps
import common.WskTestHelpers

@RunWith(classOf[JUnitRunner])
class WskConfigTests extends TestHelpers with WskTestHelpers {

  implicit val wskprops = WskProps()
  val wsk = new Wsk

  behavior of "Wsk CLI config"

  it should "fail to show api build when setting apihost to bogus value" in {
    val tmpwskprops = File.createTempFile("wskprops", ".tmp")
    try {
      val env = Map("WSK_CONFIG_FILE" -> tmpwskprops.getAbsolutePath())
      wsk.cli(Seq("property", "set", "-i", "--apihost", "xxxx.yyyy"), env = env)
      val rr = wsk.cli(Seq("property", "get", "--apibuild", "-i"), env = env, expectedExitCode = ANY_ERROR_EXIT)
      rr.stdout should include regex ("""whisk API build\s*Unknown""")
      rr.stderr should include regex ("Unable to obtain API build information")
    } finally {
      tmpwskprops.delete()
    }
  }

  it should "validate default property values" in {
    val tmpwskprops = File.createTempFile("wskprops", ".tmp")
    val env = Map("WSK_CONFIG_FILE" -> tmpwskprops.getAbsolutePath())
    val stdout = wsk
      .cli(Seq("property", "unset", "--auth", "--cert", "--key", "--apihost", "--apiversion", "--namespace"), env = env)
      .stdout
    try {
      stdout should include regex ("ok: whisk auth unset")
      stdout should include regex ("ok: client cert unset")
      stdout should include regex ("ok: client key unset")
      stdout should include regex ("ok: whisk API host unset")
      stdout should include regex ("ok: whisk API version unset")
      stdout should include regex ("ok: whisk namespace unset")

      wsk
        .cli(Seq("property", "get", "--auth"), env = env)
        .stdout should include regex ("""(?i)whisk auth\s*$""") // default = empty string
      wsk
        .cli(Seq("property", "get", "--cert"), env = env)
        .stdout should include regex ("""(?i)client cert\s*$""") // default = empty string
      wsk
        .cli(Seq("property", "get", "--key"), env = env)
        .stdout should include regex ("""(?i)client key\s*$""") // default = empty string
      wsk
        .cli(Seq("property", "get", "--apihost"), env = env)
        .stdout should include regex ("""(?i)whisk API host\s*$""") // default = empty string
      wsk
        .cli(Seq("property", "get", "--namespace"), env = env)
        .stdout should include regex ("""(?i)whisk namespace\s*_$""") // default = _
    } finally {
      tmpwskprops.delete()
    }
  }

  it should "reject authenticated command when no auth key is given" in {
    // override wsk props file in case it exists
    val tmpwskprops = File.createTempFile("wskprops", ".tmp")
    val env = Map("WSK_CONFIG_FILE" -> tmpwskprops.getAbsolutePath())
    val stderr = wsk.cli(Seq("list") ++ wskprops.overrides, env = env, expectedExitCode = MISUSE_EXIT).stderr
    try {
      stderr should include regex (s"usage[:.]")
      stderr should include("--auth is required")
    } finally {
      tmpwskprops.delete()
    }
  }

  it should "reject a command when the API host is not set" in {
    val tmpwskprops = File.createTempFile("wskprops", ".tmp")
    try {
      val env = Map("WSK_CONFIG_FILE" -> tmpwskprops.getAbsolutePath())
      val stderr = wsk.cli(Seq("property", "get", "-i"), env = env, expectedExitCode = ERROR_EXIT).stderr
      stderr should include("The API host is not valid: An API host must be provided.")
    } finally {
      tmpwskprops.delete()
    }
  }

  it should "show api build details" in {
    val tmpProps = File.createTempFile("wskprops", ".tmp")
    try {
      val env = Map("WSK_CONFIG_FILE" -> tmpProps.getAbsolutePath())
      wsk.cli(Seq("property", "set", "-i") ++ wskprops.overrides, env = env)
      val rr = wsk.cli(Seq("property", "get", "--apibuild", "--apibuildno", "-i"), env = env)
      rr.stderr should not include ("https:///api/v1: http: no Host in request URL")
      rr.stdout should not include regex("Cannot determine API build")
      rr.stdout should include regex ("""(?i)whisk API build\s+201.*""")
      rr.stdout should include regex ("""(?i)whisk API build number\s+.*""")
    } finally {
      tmpProps.delete()
    }
  }

  it should "set apihost, auth, and namespace" in {
    val tmpwskprops = File.createTempFile("wskprops", ".tmp")
    try {
      val namespace = wsk.namespace.whois()
      val env = Map("WSK_CONFIG_FILE" -> tmpwskprops.getAbsolutePath())
      val stdout = wsk
        .cli(
          Seq(
            "property",
            "set",
            "-i",
            "--apihost",
            wskprops.apihost,
            "--auth",
            wskprops.authKey,
            "--namespace",
            namespace),
          env = env)
        .stdout
      stdout should include(s"ok: whisk auth set")
      stdout should include(s"ok: whisk API host set to ${wskprops.apihost}")
      stdout should include(s"ok: whisk namespace set to ${namespace}")
    } finally {
      tmpwskprops.delete()
    }
  }

  // If client certificate verification is off, should ingore run below tests.
  if (!WhiskProperties.getProperty("whisk.ssl.client.verification").equals("off")) {
    it should "set valid cert key to get expected success result for client certificate verification" in {
      val tmpwskprops = File.createTempFile("wskprops", ".tmp")
      try {
        val namespace = wsk.namespace.list().stdout.trim.split("\n").last
        val env = Map("WSK_CONFIG_FILE" -> tmpwskprops.getAbsolutePath())
        // Send request to https://<apihost>/api/v1/namespaces, wsk client passes client certificate to nginx, nginx will
        // verify it by client ca's openwhisk-client-ca-cert.pem
        val stdout = wsk
          .cli(
            Seq(
              "property",
              "set",
              "-i",
              "--apihost",
              wskprops.apihost,
              "--auth",
              wskprops.authKey,
              "--cert",
              wskprops.cert,
              "--key",
              wskprops.key,
              "--namespace",
              namespace),
            env = env)
          .stdout
        stdout should include(s"ok: client cert set")
        stdout should include(s"ok: client key set")
        stdout should include(s"ok: whisk auth set")
        stdout should include(s"ok: whisk API host set to ${wskprops.apihost}")
        stdout should include(s"ok: whisk namespace set to ${namespace}")
      } finally {
        tmpwskprops.delete()
      }
    }

    it should "set invalid cert key to get expected exception result for client certificate verification" in {
      val tmpwskprops = File.createTempFile("wskprops", ".tmp")
      try {
        val namespace = wsk.namespace.list().stdout.trim.split("\n").last
        val env = Map("WSK_CONFIG_FILE" -> tmpwskprops.getAbsolutePath())
        val thrown = the[Exception] thrownBy wsk.cli(
          Seq(
            "property",
            "set",
            "-i",
            "--apihost",
            wskprops.apihost,
            "--auth",
            wskprops.authKey,
            "--cert",
            "invalid-cert.pem",
            "--key",
            "invalid-key.pem",
            "--namespace",
            namespace),
          env = env)
        thrown.getMessage should include("cannot validate certificate")
      } finally {
        tmpwskprops.delete()
      }
    }
  }

  it should "ensure default namespace is used when a blank namespace is set" in {
    val tmpwskprops = File.createTempFile("wskprops", ".tmp")
    try {
      val writer = new BufferedWriter(new FileWriter(tmpwskprops))
      writer.write(s"NAMESPACE=")
      writer.close()
      val env = Map("WSK_CONFIG_FILE" -> tmpwskprops.getAbsolutePath())
      val stdout = wsk.cli(Seq("property", "get", "-i", "--namespace"), env = env).stdout
      stdout should include regex ("whisk namespace\\s+_")
    } finally {
      tmpwskprops.delete()
    }
  }

  it should "show api build version using property file" in {
    val tmpwskprops = File.createTempFile("wskprops", ".tmp")
    try {
      val env = Map("WSK_CONFIG_FILE" -> tmpwskprops.getAbsolutePath())
      wsk.cli(Seq("property", "set", "-i") ++ wskprops.overrides, env = env)
      val stdout = wsk.cli(Seq("property", "get", "--apibuild", "-i"), env = env).stdout
      stdout should include regex ("""(?i)whisk API build\s+201.*""")
    } finally {
      tmpwskprops.delete()
    }
  }

  it should "show api build using http apihost" in {
    val tmpwskprops = File.createTempFile("wskprops", ".tmp")
    try {
      val env = Map("WSK_CONFIG_FILE" -> tmpwskprops.getAbsolutePath())
      val apihost = s"http://${WhiskProperties.getBaseControllerAddress()}"
      wsk.cli(Seq("property", "set", "--apihost", apihost), env = env)
      val rr = wsk.cli(Seq("property", "get", "--apibuild", "-i"), env = env)
      rr.stdout should not include regex("""whisk API build\s*Unknown""")
      rr.stderr should not include regex("Unable to obtain API build information")
      rr.stdout should include regex ("""(?i)whisk API build\s+201.*""")
    } finally {
      tmpwskprops.delete()
    }
  }

  it should "set api host with or without http prefix" in {
    val tmpwskprops = File.createTempFile("wskprops", ".tmp")
    try {
      val env = Map("WSK_CONFIG_FILE" -> tmpwskprops.getAbsolutePath())
      Seq("", "http://", "https://").foreach { prefix =>
        Seq("10", "10:123", "aaa", "aaa:123").foreach { host =>
          val apihost = s"$prefix$host"
          withClue(apihost) {
            val rr = wsk.cli(Seq("property", "set", "--apihost", apihost), env = env)
            rr.stdout.trim shouldBe s"ok: whisk API host set to $apihost"
            rr.stderr shouldBe 'empty
            val fileContent = FileUtils.readFileToString(tmpwskprops)
            fileContent should include(s"APIHOST=$apihost")
          }
        }
      }
    } finally {
      tmpwskprops.delete()
    }
  }

  it should "set auth in property file" in {
    val tmpwskprops = File.createTempFile("wskprops", ".tmp")
    val env = Map("WSK_CONFIG_FILE" -> tmpwskprops.getAbsolutePath())
    wsk.cli(Seq("property", "set", "--auth", "testKey"), env = env)
    try {
      val fileContent = FileUtils.readFileToString(tmpwskprops)
      fileContent should include("AUTH=testKey")
    } finally {
      tmpwskprops.delete()
    }
  }

  it should "set multiple property values with single command" in {
    val tmpwskprops = File.createTempFile("wskprops", ".tmp")
    val env = Map("WSK_CONFIG_FILE" -> tmpwskprops.getAbsolutePath())
    val stdout = wsk
      .cli(
        Seq(
          "property",
          "set",
          "--auth",
          "testKey",
          "--cert",
          "cert.pem",
          "--key",
          "key.pem",
          "--apihost",
          "openwhisk.ng.bluemix.net",
          "--apiversion",
          "v1"),
        env = env)
      .stdout
    try {
      stdout should include regex ("ok: whisk auth set")
      stdout should include regex ("ok: client cert set")
      stdout should include regex ("ok: client key set")
      stdout should include regex ("ok: whisk API host set")
      stdout should include regex ("ok: whisk API version set")
      val fileContent = FileUtils.readFileToString(tmpwskprops)
      fileContent should include("AUTH=testKey")
      fileContent should include("APIHOST=openwhisk.ng.bluemix.net")
      fileContent should include("APIVERSION=v1")
    } finally {
      tmpwskprops.delete()
    }
  }

  it should "create a trigger using property file" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val name = "listTriggers"
    val tmpProps = File.createTempFile("wskprops", ".tmp")
    val env = Map("WSK_CONFIG_FILE" -> tmpProps.getAbsolutePath())
    wsk.cli(Seq("property", "set", "--auth", wp.authKey) ++ wskprops.overrides, env = env)
    assetHelper.withCleaner(wsk.trigger, name) { (trigger, _) =>
      wsk.cli(Seq("-i", "trigger", "create", name), env = env)
    }
    tmpProps.delete()
  }
}
