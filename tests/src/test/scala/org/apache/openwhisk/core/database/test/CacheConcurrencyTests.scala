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

package org.apache.openwhisk.core.database.test

import scala.concurrent.duration.DurationInt
import java.util.concurrent.Executors

import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfterEach
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner
import akka.http.scaladsl.model.StatusCodes.NotFound
import common.TestUtils._
import common._
import common.rest.WskRestOperations
import spray.json.JsString
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.utils.retry

import scala.concurrent.ExecutionContext

@RunWith(classOf[JUnitRunner])
class CacheConcurrencyTests
    extends FlatSpec
    with WskTestHelpers
    with WskActorSystem
    with BeforeAndAfterEach
    with ConcurrencyHelpers {

  val timeout = 5.minutes
  println(s"Running tests on # proc: ${Runtime.getRuntime.availableProcessors()}")

  implicit private val transId = TransactionId.testing
  implicit private val wp = WskProps()
  private val wsk = new WskRestOperations

  val nExternalIters = 1
  val nInternalIters = 5
  val nThreads = nInternalIters * 30 // The maximum number of tasks running in parallel at any given time
  val parallelismExecutionContext = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(nThreads))

  def run[W](phase: String)(block: String => W) =
    concurrently((1 to nInternalIters), timeout) { i =>
      val name = s"testy${i}"
      withClue(s"$phase: failed for $name") { (name, block(name)) }
    }(parallelismExecutionContext)

  override def beforeEach() = {
    run("pre-test sanitize") { name =>
      wsk.action.sanitize(name)
    }
  }

  override def afterEach() = {
    run("post-test sanitize") { name =>
      wsk.action.sanitize(name)
    }
  }

  for (n <- 1 to nExternalIters)
    "the cache" should s"support concurrent CRUD without bogus residual cache entries, iter ${n}" in {
      val actionFile = TestUtils.getTestActionFilename("empty.js")

      run("create") { name =>
        wsk.action.create(name, Some(actionFile))
      }

      run("update") { name =>
        wsk.action.create(name, None, update = true)
      }

      run("delete+get") { name =>
        // run 30 operations in parallel: 15 get, 1 delete, 14 more get
        concurrently((1 to 30), timeout) { i =>
          if (i != 16) {
            val rr = wsk.action.get(name, expectedExitCode = DONTCARE_EXIT)
            withClue(s"expecting get to either succeed or fail with not found: $rr") {
              // some will succeed and some should fail with not found
              rr.exitCode should (be(SUCCESS_EXIT) or be(NOT_FOUND))
            }
          } else {
            wsk.action.delete(name)
          }
        }(parallelismExecutionContext)
      }

      // Give some time to replicate the state between the controllers
      retry(
        {
          // Check that every controller has the correct state (used round robin)
          WhiskProperties.getControllerHosts.split(",").foreach { _ =>
            run("get after delete") { name =>
              wsk.action.get(name, expectedExitCode = NotFound.intValue)
            }
          }
        },
        10,
        Some(2.second))

      run("recreate") { name =>
        wsk.action.create(name, Some(actionFile))
      }

      run("reupdate") { name =>
        wsk.action.create(name, None, parameters = Map("color" -> JsString("red")), update = true)
      }

      run("update+get") { name =>
        // run 30 operations in parallel: 15 get, 1 update, 14 more get
        concurrently((1 to 30), timeout) { i =>
          if (i != 16) {
            val rr = wsk.action.get(name, expectedExitCode = DONTCARE_EXIT)
            withClue(s"expecting get to either succeed or fail with not found: $rr") {
              // some will succeed and some should fail with not found
              rr.exitCode should (be(SUCCESS_EXIT) or be(NOT_FOUND))
            }
          } else {
            wsk.action.create(name, None, parameters = Map("color" -> JsString("blue")), update = true)
          }
        }(parallelismExecutionContext)
      }

      // All controllers should have the correct action
      // As they are used round robin, we ask every controller for the action.
      // We add a retry to tollarate a short interval to bring the controllers in sync.
      retry(
        {
          WhiskProperties.getControllerHosts.split(",").foreach { _ =>
            run("get after update") { name =>
              wsk.action.get(name)
            } map {
              case (name, rr) =>
                withClue(s"get after update: failed check for $name") {
                  rr.stdout should include("blue")
                  rr.stdout should not include ("red")
                }
            }
          }
        },
        10,
        Some(2.second))
    }
}
