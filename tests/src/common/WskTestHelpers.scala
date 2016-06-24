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

package common

import scala.collection.mutable.ListBuffer
import scala.util.Failure
import scala.util.Try
import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

import org.scalatest.Matchers

import common.TestUtils.RunResult
import spray.json.JsObject

/**
 * Test fixture to ease cleaning of whisk entities created during testing.
 *
 * The fixture records the entities created during a test and when the test
 * completed, will delete them all.
 */
trait WskTestHelpers extends Matchers {
    type Assets = ListBuffer[(DeleteFromCollection, String, Boolean)]

    /**
     * Helper to register an entity to delete once a test completes.
     * The helper sanitizes (deletes) a previous instance of the entity if it exists
     * in given collection.
     *
     */
    private class AssetCleaner(assetsToDeleteAfterTest: Assets, wskprops: WskProps) {
        def withCleaner[T <: DeleteFromCollection](cli: T, name: String, confirmDelete: Boolean = true)(
            cmd: (T, String) => RunResult): RunResult = {
            cli.sanitize(name)(wskprops) // sanitize (delete) if asset exists
            assetsToDeleteAfterTest += ((cli, name, confirmDelete))
            cmd(cli, name)
        }
    }

    /**
     * Creates a test closure which records all entities created inside the test into a
     * list that is iterated at the end of the test so that these entities are deleted
     * (from most recently created to oldest).
     */
    def withAssetCleaner(wskprops: WskProps)(test: (WskProps, AssetCleaner) => Any) = {
        // create new asset list to track what must be deleted after test completes
        val assetsToDeleteAfterTest = new Assets()

        try {
            test(wskprops, new AssetCleaner(assetsToDeleteAfterTest, wskprops))
        } finally {
            // delete assets in reverse order so that was created last is deleted first
            val deletedAll = assetsToDeleteAfterTest.reverse map {
                case ((cli, n, delete)) => n -> Try {
                    if (delete) {
                        println(s"deleting $n")
                        cli.delete(n)(wskprops)
                    } else {
                        println(s"sanitizing $n")
                        cli.sanitize(n)(wskprops)
                    }
                }
            } forall {
                case (n, Failure(t)) =>
                    println(s"ERROR: deleting asset failed for $n: $t")
                    false
                case _ =>
                    true
            }
            assert(deletedAll, "some assets were not deleted")
        }
    }

    /**
     * Extracts an activation id from a wsk command producing a RunResult with such an id.
     * If id is found, polls activations until one matching id is found. If found, pass
     * the activation as a JsObject to the post processor which then check for expected values.
     */
    def withActivation(
        wsk: WskActivation,
        run: RunResult,
        initialWait: Duration = 1 second,
        pollPeriod: Duration = 1 second,
        totalWait: Duration = 30 seconds)(
            check: JsObject => Unit)(
                implicit wskprops: WskProps): Unit = {
        val activationId = wsk.extractActivationId(run)

        withClue(s"did not find an activation id in '$run'") {
            activationId shouldBe a[Some[_]]
        }

        val id = activationId.get
        val activation = wsk.waitForActivation(id, initialWait, pollPeriod, totalWait)
        if (activation.isLeft) {
            assert(false, s"error waiting for activation $id: ${activation.left.get}")
        } else try {
            check(activation.right.get)
        } catch {
            case error: Throwable =>
                println(s"check failed for activation $id: ${activation.right.get}")
                throw error
        }
    }

    /**
     * In the case that test throws an exception, print stderr and stdout
     * from the provided RunResult.
     */
    def withPrintOnFailure(runResult: RunResult)(test: () => Unit) {
        try {
            test()
        } catch {
            case error: Throwable =>
                println(s"[stderr] ${runResult.stderr}")
                println(s"[stdout] ${runResult.stdout}")
                throw error
        }
    }
}
