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

package system.basic

import java.io.File

import org.apache.commons.io.FileUtils
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import common.TestHelpers
import common.TestUtils.ERROR_EXIT
import common.TestUtils.SUCCESS_EXIT
import common.Wsk
import common.WskProps
import common.WskTestHelpers

@RunWith(classOf[JUnitRunner])
class WskSdkTests extends TestHelpers with WskTestHelpers {

  implicit val wskprops = WskProps()
  val wsk = new Wsk

  behavior of "Wsk SDK"

  it should "prefix https to apihost if no scheme given" in {
    val result = wsk.cli(Seq("--apihost", "localhost:54321", "sdk", "install", "docker"), expectedExitCode = ERROR_EXIT)
    result.stderr should include regex ("""(?i)Get https://localhost:54321/""")
  }

  it should "not prefix https to http apihost" in {
    val result =
      wsk.cli(Seq("--apihost", "http://localhost:54321", "sdk", "install", "docker"), expectedExitCode = ERROR_EXIT)
    result.stderr should include regex ("""(?i)Get http://localhost:54321/""")
  }

  it should "not double prefix https to https apihost" in {
    val result =
      wsk.cli(Seq("--apihost", "https://localhost:54321", "sdk", "install", "docker"), expectedExitCode = ERROR_EXIT)
    result.stderr should include regex ("""(?i)Get https://localhost:54321/""")
  }

  it should "download docker action sdk" in {
    val dir = File.createTempFile("wskinstall", ".tmp")
    dir.delete()
    dir.mkdir() should be(true)
    try {
      wsk.cli(wskprops.overrides ++ Seq("sdk", "install", "docker"), workingDir = dir).stdout should include(
        "The docker skeleton is now installed at the current directory.")

      val sdk = new File(dir, "dockerSkeleton")
      sdk.exists() should be(true)
      sdk.isDirectory() should be(true)

      val dockerfile = new File(sdk, "Dockerfile")
      dockerfile.exists() should be(true)
      dockerfile.isFile() should be(true)
      val lines = FileUtils.readLines(dockerfile)
      // confirm that the image is correct
      lines.get(1) shouldBe "FROM openwhisk/dockerskeleton"

      val buildAndPushFile = new File(sdk, "buildAndPush.sh")
      buildAndPushFile.canExecute() should be(true)
    } finally {
      FileUtils.deleteDirectory(dir)
    }
  }

  it should "download iOS sdk" in {
    val dir = File.createTempFile("wskinstall", ".tmp")
    dir.delete()
    dir.mkdir() should be(true)

    wsk.cli(wskprops.overrides ++ Seq("sdk", "install", "iOS"), workingDir = dir).stdout should include(
      "Downloaded OpenWhisk iOS starter app. Unzip 'OpenWhiskIOSStarterApp.zip' and open the project in Xcode.")

    val sdk = new File(dir, "OpenWhiskIOSStarterApp.zip")
    sdk.exists() should be(true)
    sdk.isFile() should be(true)
    FileUtils.sizeOf(sdk) should be > 20000L
    FileUtils.deleteDirectory(dir)
  }

  it should "install the bash auto-completion bash script" in {
    // Use a temp dir for testing to not disturb user's local folder
    val dir = File.createTempFile("wskinstall", ".tmp")
    dir.delete()
    dir.mkdir() should be(true)

    val scriptfilename = "wsk_cli_bash_completion.sh"
    var scriptfile = new File(dir.getPath(), scriptfilename)
    try {
      val stdout = wsk.cli(Seq("sdk", "install", "bashauto"), workingDir = dir, expectedExitCode = SUCCESS_EXIT).stdout
      stdout should include("is installed in the current directory")
      val fileContent = FileUtils.readFileToString(scriptfile)
      fileContent should include("bash completion for wsk")
    } finally {
      scriptfile.delete()
      FileUtils.deleteDirectory(dir)
    }
  }

  it should "print bash command completion script to STDOUT" in {
    val msg = "bash completion for wsk" // Subject to change, dependent on Cobra script

    val stdout = wsk.cli(Seq("sdk", "install", "bashauto", "--stdout")).stdout
    stdout should include(msg)
  }
}
