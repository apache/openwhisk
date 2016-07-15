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

package system.basic

import java.io.File

import org.apache.commons.io.FileUtils
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import common.TestHelpers
import common.TestUtils.SUCCESS_EXIT
import common.Wsk
import common.WskProps
import common.WskTestHelpers

@RunWith(classOf[JUnitRunner])
class WskSdkTests
    extends TestHelpers
    with WskTestHelpers {

    implicit val wskprops = WskProps()
    val wsk = new Wsk(usePythonCLI = false)

    behavior of "Wsk SDK"

    it should "download docker action sdk" in {
        val dir = File.createTempFile("wskinstall", ".tmp")
        dir.delete()
        dir.mkdir() should be(true)

        wsk.cli(wskprops.overrides ++ Seq("sdk", "install", "docker"), workingDir = dir).
            stdout should include("The docker skeleton is now installed at the current directory.")

        val sdk = new File(dir, "dockerSkeleton")
        sdk.exists() should be(true)
        sdk.isDirectory() should be(true)

        val file = new File(sdk, "Dockerfile")
        file.exists() should be(true)
        file.isFile() should be(true)
        FileUtils.deleteDirectory(dir)
    }

    it should "download iOS sdk" in {
        val dir = File.createTempFile("wskinstall", ".tmp")
        dir.delete()
        dir.mkdir() should be(true)

        wsk.cli(wskprops.overrides ++ Seq("sdk", "install", "iOS"), workingDir = dir).
            stdout should include("Downloaded OpenWhisk iOS starter app. Unzip OpenWhiskIOSStarterApp.zip and open the project in Xcode.")

        val sdk = new File(dir, "OpenWhiskIOSStarterApp.zip")
        sdk.exists() should be(true)
        sdk.isFile() should be(true)
        FileUtils.sizeOf(sdk) should be > 30000L
        FileUtils.deleteDirectory(dir)
    }

    it should "preview swift sdk" in {
        wsk.cli(wskprops.overrides ++ Seq("sdk", "install", "swift")).
            stdout should include("Swift SDK coming soon.")
    }

    it should "install the bash auto-completion bash script" in {
        val scriptfilename = "wsk_cli_bash_completion.sh"
        val stdout = wsk.cli(Seq("sdk", "install", "bashauto"), expectedExitCode = SUCCESS_EXIT).stdout
        stdout should include("is installed in the curent directory")
        val scriptfile = new File(scriptfilename)
        val fileContent = FileUtils.readFileToString(scriptfile)
        fileContent should include("bash completion for wsk")
        scriptfile.delete()
    }

}
