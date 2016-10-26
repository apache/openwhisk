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

package whisk.core.database.test

import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfterAll
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

import common.TestUtils
import common.Wsk
import whisk.common.Logging
import whisk.common.SimpleExec
import whisk.common.TransactionId

@RunWith(classOf[JUnitRunner])
class CacheConcurrencyTests extends FlatSpec
    with BeforeAndAfterAll
    with Logging
    with Matchers {

    implicit private val logger = this
    implicit private val transId = TransactionId.testing

    "the cache" should "support concurrent CRUD without bogus residual cache entries" in {
        //val scriptPath = getClass.getResource("CacheConcurrencyTests.sh").getPath;
        val scriptPath = TestUtils.getTestActionFilename("CacheConcurrencyTests.sh")
        val actionFile = TestUtils.getTestActionFilename("empty.js")
        val fullCmd = Seq(scriptPath, Wsk.baseCommand.mkString, actionFile)

        val (stdout, stderr, exitCode) = SimpleExec.syncRunCmd(fullCmd)

        if (!stdout.isEmpty) {
            logger.info(stdout)
        }
        if (!stderr.isEmpty) {
            logger.error(this, stderr)
        }

        exitCode should be(0)
    }
}
