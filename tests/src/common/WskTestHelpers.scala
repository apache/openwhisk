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

import TestUtils._
import spray.json._
import java.time.Instant

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
    class AssetCleaner(assetsToDeleteAfterTest: Assets, wskprops: WskProps) {
        def withCleaner[T <: DeleteFromCollection](cli: T, name: String, confirmDelete: Boolean = true)(
            cmd: (T, String) => RunResult): RunResult = {
            // sanitize (delete) if asset exists
            cli match {
                case _: WskPackage =>
                    val rr = cli.sanitize(name)(wskprops)
                    rr.exitCode match {
                        case CONFLICT =>
                            // retry sanitization on a package since there may be a list (view)
                            // operation that requires a retry for eventual consistency
                            whisk.utils.retry({
                                cli.sanitize(name)(wskprops)
                            }, 5, Some(1 second))
                        case _ => rr
                    }

                case _ => cli.sanitize(name)(wskprops)
            }

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
        } catch {
            case t: Throwable =>
                // log the exception that occurred in the test
                println(s"Exception occurred during test execution: $t")
        } finally {
            // delete assets in reverse order so that was created last is deleted first
            val deletedAll = assetsToDeleteAfterTest.reverse map {
                case ((cli, n, delete)) => n -> Try {
                    if (delete) {
                        cli.delete(n)(wskprops)
                    } else {
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
     * An arbitrary response of a whisk action. Includes the result as a JsObject as the
     * structure of "result" is not defined.
     */
    case class CliActivationResponse(result: Option[JsObject], status: String, success: Boolean)

    object CliActivationResponse extends DefaultJsonProtocol {
        implicit val serdes = jsonFormat3(CliActivationResponse.apply)
    }

    /**
     * Activation record as it is returned by the CLI.
     */
    case class CliActivation(
        activationId: String,
        logs: Option[List[String]],
        response: CliActivationResponse,
        start: Long,
        end: Long,
        duration: Long,
        cause: Option[String],
        annotations: Option[List[JsObject]]) {

        def getAnnotationValue(key: String): Option[JsValue] = {
            Try {
                val annotation = annotations.get.filter(x => x.getFields("key")(0) == JsString(key))
                assert(annotation.size == 1) // only one annotation with this value
                val value = annotation(0).getFields("value")
                assert(value.size == 1)
                value(0)
            } toOption
        }
    }

    object CliActivation extends DefaultJsonProtocol {
        implicit val serdes = jsonFormat8(CliActivation.apply)
    }

    /**
     * Extracts an activation id from a wsk command producing a RunResult with such an id.
     * If id is found, polls activations until one matching id is found. If found, pass
     * the activation to the post processor which then check for expected values.
     */
    def withActivation(
        wsk: WskActivation,
        run: RunResult,
        initialWait: Duration = 1 second,
        pollPeriod: Duration = 1 second,
        totalWait: Duration = 30 seconds)(
            check: CliActivation => Unit)(
                implicit wskprops: WskProps): Unit = {
        val activationId = wsk.extractActivationId(run)

        withClue(s"did not find an activation id in '$run'") {
            activationId shouldBe a[Some[_]]
        }

        withActivation(wsk, activationId.get, initialWait, pollPeriod, totalWait)(check)
    }

    /**
     * Polls activations until one matching id is found. If found, pass
     * the activation to the post processor which then check for expected values.
     */
    def withActivation(
        wsk: WskActivation,
        activationId: String,
        initialWait: Duration,
        pollPeriod: Duration,
        totalWait: Duration)(
            check: CliActivation => Unit)(
                implicit wskprops: WskProps): Unit = {
        val id = activationId
        val activation = wsk.waitForActivation(id, initialWait, pollPeriod, totalWait)
        if (activation.isLeft) {
            assert(false, s"error waiting for activation $id: ${activation.left.get}")
        } else try {
            check(activation.right.get.convertTo[CliActivation])
        } catch {
            case error: Throwable =>
                println(s"check failed for activation $id: ${activation.right.get}")
                throw error
        }
    }

    /**
     * Polls until it finds {@code N} activationIds from an entity. Asserts the count
     * of the activationIds actually equal {@code N}. Takes a {@code since} parameter
     * defining the oldest activationId to consider valid.
     */
    def withActivationsFromEntity(
        wsk: WskActivation,
        entity: String,
        N: Int = 1,
        since: Option[Instant] = None,
        pollPeriod: Duration = 1 second,
        totalWait: Duration = 30 seconds)(
            check: Seq[CliActivation] => Unit)(
                implicit wskprops: WskProps): Unit = {

        val activationIds = wsk.pollFor(N, Some(entity), since = since, retries = (totalWait / pollPeriod).toInt, pollPeriod = pollPeriod)
        withClue(s"expecting $N activations matching '$entity' name since $since but found ${activationIds.mkString(",")} instead") {
            activationIds.length shouldBe N
        }

        val parsed = activationIds.map { id =>
            wsk.parseJsonString(wsk.get(id).stdout).convertTo[CliActivation]
        }
        try {
            check(parsed)
        } catch {
            case error: Throwable =>
                println(s"check failed for activations $activationIds: ${parsed}")
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
