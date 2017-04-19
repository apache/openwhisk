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

package whisk.core.container.test

import org.junit.runner.RunWith
import org.scalatest.fixture.FlatSpec
import org.scalatest.junit.JUnitRunner
import java.io.File

import common.StreamLogging

import whisk.common.TransactionId
import whisk.core.container.ContainerUtils
import whisk.common.Logging
import whisk.core.container.ContainerHash
import org.scalatest.BeforeAndAfter
import java.io.FileWriter
import java.nio.charset.StandardCharsets
import org.scalatest.Matchers

/**
 * Unit tests for ContainerPool and, by association, Container and WhiskContainer.
 */
@RunWith(classOf[JUnitRunner])
class ContainerUtilsTests extends FlatSpec
    with BeforeAndAfter
    with Matchers
    with StreamLogging {

    before {
        stream.reset()
    }

    implicit val transid = TransactionId.testing

    behavior of "getDockerLogContent"

    case class FixtureParam(file: File, writer: FileWriter, cu: ContainerUtilsTester)

    def withFixture(test: OneArgTest) = {
        val file = File.createTempFile(this.getClass.getName, test.name.replaceAll("[^a-zA-Z0-9.-]", "_"))
        val writer = new FileWriter(file)
        val cu = new ContainerUtilsTester(file)

        val fixture = FixtureParam(file, writer, cu)

        try {
            withFixture(test.toNoArgTest(fixture))
        } finally {
            writer.close()
            file.delete()
        }
    }

    def writeLogFile(fixture: FixtureParam, content: String): Long = {
        fixture.writer.write(content)
        fixture.writer.flush()
        fixture.file.length
    }

    val containerHash = ContainerHash.fromString("0123")

    it should "tolerate an empty log file" in { fixture =>
        val logText = ""
        val size = writeLogFile(fixture, logText)

        val buffer = fixture.cu.getDockerLogContent(containerHash, start = 0, end = size, mounted = false)
        val logContent = new String(buffer.array, buffer.arrayOffset, buffer.position, StandardCharsets.UTF_8)

        logContent shouldBe logText
        stream should have size 0
    }

    it should "read a full log file" in { fixture =>
        val logText = "text"
        val size = writeLogFile(fixture, logText)

        val buffer = fixture.cu.getDockerLogContent(containerHash, start = 0, end = size, mounted = false)
        val logContent = new String(buffer.array, buffer.arrayOffset, buffer.position, StandardCharsets.UTF_8)

        logContent shouldBe logText
        stream should have size 0
    }

    it should "read a log file portion" in { fixture =>
        val logText =
            """Hey, dude-it'z true not sad
              |Take a thrash song and make it better
              |Admit it! Beatallica'z under your skin!
              |So now begin to be a shredder""".stripMargin
        val from = 66 // extract the third line...
        val to = 105
        val expectedText = logText.substring(from, to)

        val size = writeLogFile(fixture, logText)

        val buffer = fixture.cu.getDockerLogContent(containerHash, start = from, end = to, mounted = false)
        val logContent = new String(buffer.array, buffer.arrayOffset, buffer.position, StandardCharsets.UTF_8)

        logContent shouldBe expectedText
        stream should have size 0
    }

    it should "tolerate premature end of log file" in { fixture =>
        val logText = (1 to 2).map(i => s"Line ${i}\n").mkString
        val size = writeLogFile(fixture, logText)
        val to = 2 * size

        val buffer = fixture.cu.getDockerLogContent(containerHash, start = 0, end = to, mounted = false)
        val logContent = new String(buffer.array, buffer.arrayOffset, buffer.position, StandardCharsets.UTF_8)

        logContent shouldBe logText
        stream should have size 0
    }

    it should "provide an empty result on failure" in { fixture =>
        fixture.cu.logFile = new File("/nonsense")

        val buffer = fixture.cu.getDockerLogContent(containerHash, start = 0, end = 1, mounted = false)

        buffer.capacity() shouldBe 0
        logLines.head should include("getDockerLogContent failed")
    }
}

class ContainerUtilsTester(var logFile: File)(implicit val logging: Logging) extends ContainerUtils {
    val dockerhost: String = ""

    override def getDockerLogFile(containerId: ContainerHash, mounted: Boolean) = {
        logFile
    }
}
