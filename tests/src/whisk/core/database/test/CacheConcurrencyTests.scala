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

import scala.collection.parallel._
import scala.concurrent.forkjoin.ForkJoinPool

import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfter
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner

import common.TestUtils
import common.TestUtils._
import common.Wsk
import common.WskProps
import common.WskTestHelpers
import spray.json.JsString
import whisk.common.TransactionId

@RunWith(classOf[JUnitRunner])
class CacheConcurrencyTests extends FlatSpec
    with WskTestHelpers
    with BeforeAndAfter {

    implicit private val transId = TransactionId.testing
    implicit private val wp = WskProps()
    private val wsk = new Wsk

    val nExternalIters = 1
    val nInternalIters = 5
    val nThreads = nInternalIters * 30

    val parallel = (1 to nInternalIters).par
    parallel.tasksupport = new ForkJoinTaskSupport(new ForkJoinPool(nThreads))

    def run[W](phase: String)(block: String => W) = parallel.map { i =>
        val name = s"testy${i}"
        withClue(s"$phase: failed for $name") { (name, block(name)) }
    }

    before {
        run("pre-test sanitize") {
            name => wsk.action.sanitize(name)
        }
    }

    after {
        run("post-test sanitize") {
            name => wsk.action.sanitize(name)
        }
    }

    for (n <- 1 to nExternalIters)
        "the cache" should s"support concurrent CRUD without bogus residual cache entries, iter ${n}" in {
            val scriptPath = TestUtils.getTestActionFilename("CacheConcurrencyTests.sh")
            val actionFile = TestUtils.getTestActionFilename("empty.js")

            run("create") {
                name => wsk.action.create(name, Some(actionFile))
            }

            run("update") {
                name => wsk.action.create(name, None, update = true)
            }

            run("delete+get") {
                name =>
                    // run 30 operations in parallel: 15 get, 1 delete, 14 more get
                    val para = (1 to 30).par
                    para.tasksupport = new ForkJoinTaskSupport(new ForkJoinPool(nThreads))
                    para.map {
                        i =>
                            if (i != 16) {
                                val rr = wsk.action.get(name, expectedExitCode = DONTCARE_EXIT)
                                withClue(s"expecting get to either succeed or fail with not found: $rr") {
                                    // some will succeed and some should fail with not found
                                    rr.exitCode should (be(SUCCESS_EXIT) or be(NOT_FOUND))
                                }
                            } else {
                                wsk.action.delete(name)
                            }
                    }
            }

            run("get after delete") {
                name => wsk.action.get(name, expectedExitCode = NOT_FOUND)
            }

            run("recreate") {
                name => wsk.action.create(name, Some(actionFile))
            }

            run("reupdate") {
                name => wsk.action.create(name, None, parameters = Map("color" -> JsString("red")), update = true)
            }

            run("update+get") {
                name =>
                    // run 30 operations in parallel: 15 get, 1 update, 14 more get
                    val para = (1 to 30).par
                    para.tasksupport = new ForkJoinTaskSupport(new ForkJoinPool(nThreads))
                    para.map {
                        i =>
                            if (i != 16) {
                                wsk.action.get(name)
                            } else {
                                wsk.action.create(name, None, parameters = Map("color" -> JsString("blue")), update = true)
                            }
                    }
            }

            run("get after update") {
                name => wsk.action.get(name)
            } map {
                case (name, rr) =>
                    withClue(s"get after update: failed check for $name") {
                        rr.stdout should include("blue")
                        rr.stdout should not include ("red")
                    }
            }
        }
}
