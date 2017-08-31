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

package common

import java.time.Instant

import org.scalatest.Matchers

import scala.collection.mutable.ListBuffer
import scala.util.Failure
import scala.util.Try
import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

import spray.json._

import TestUtils.RunResult
import TestUtils.CONFLICT

/**
 * An arbitrary response of a whisk action. Includes the result as a JsObject as the
 * structure of "result" is not defined.
 *
 * @param result a JSON object used to save the result of the execution of the action
 * @param status a string used to indicate the status of the action
 * @param success a boolean value used to indicate whether the action is executed successfully or not
 */
case class ActivationResponse(result: Option[JsObject], status: String, success: Boolean)

object ActivationResponse extends DefaultJsonProtocol {
    implicit val serdes = jsonFormat3(ActivationResponse.apply)
}

/**
 * Activation record as it is returned from the OpenWhisk service.
 *
 * @param activationId a String to save the ID of the activation
 * @param logs a list of String to save the logs of the activation
 * @param response an Object of ActivationResponse to save the response of the activation
 * @param start an Instant to save the start time of activation
 * @param end an Instant to save the end time of activation
 * @param duration a Long to save the duration of the activation
 * @param cases String to save the cause of failure if the activation fails
 * @param annotations a list of JSON objects to save the annotations of the activation
 */
case class ActivationResult(
    activationId: String,
    logs: Option[List[String]],
    response: ActivationResponse,
    start: Instant,
    end: Instant,
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

object ActivationResult extends DefaultJsonProtocol {
    private implicit val instantSerdes = new RootJsonFormat[Instant] {
        def write(t: Instant) = t.toEpochMilli.toJson

        def read(value: JsValue) = Try {
            value match {
                case JsNumber(i) => Instant.ofEpochMilli(i.bigDecimal.longValue)
                case _           => deserializationError("timetsamp malformed")
            }
        } getOrElse deserializationError("timetsamp malformed 2")
    }

    implicit val serdes = new RootJsonFormat[ActivationResult] {
        private val format = jsonFormat8(ActivationResult.apply)

        def write(result: ActivationResult) = format.write(result)

        def read(value: JsValue) = {
            val obj = value.asJsObject
            obj.getFields("activationId", "response", "start") match {
                case Seq(JsString(activationId), response, start) =>
                    Try {
                        val logs = obj.fields.get("logs").map(_.convertTo[List[String]])
                        val end = obj.fields.get("end").map(_.convertTo[Instant]).getOrElse(Instant.EPOCH)
                        val duration = obj.fields.get("duration").map(_.convertTo[Long]).getOrElse(0L)
                        val cause = obj.fields.get("cause").map(_.convertTo[String])
                        val annotations = obj.fields.get("annotations").map(_.convertTo[List[JsObject]])
                        new ActivationResult(activationId, logs, response.convertTo[ActivationResponse],
                            start.convertTo[Instant], end, duration, cause, annotations)
                    } getOrElse deserializationError("Failed to deserialize the activation result.")
                case _ => deserializationError("Failed to deserialize the activation ID, response or start.")
            }
        }
    }
}

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
            cli.sanitize(name)(wskprops)

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
                // log the exception that occurred in the test and rethrow it
                println(s"Exception occurred during test execution: $t")
                throw t
        } finally {
            // delete assets in reverse order so that was created last is deleted first
            val deletedAll = assetsToDeleteAfterTest.reverse map {
                case ((cli, n, delete)) => n -> Try {
                    cli match {
                        case _: WskPackage if delete =>
                            val rr = cli.delete(n)(wskprops)
                            rr.exitCode match {
                                case CONFLICT => whisk.utils.retry(cli.delete(n)(wskprops), 5, Some(1.second))
                                case _        => rr
                            }
                        case _ => if (delete) cli.delete(n)(wskprops) else cli.sanitize(n)(wskprops)
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
     * the activation to the post processor which then check for expected values.
     */
    def withActivation(
        wsk: WskActivation,
        run: RunResult,
        initialWait: Duration = 1 second,
        pollPeriod: Duration = 1 second,
        totalWait: Duration = 30 seconds)(
            check: ActivationResult => Unit)(
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
            check: ActivationResult => Unit)(
                implicit wskprops: WskProps): Unit = {
        val id = activationId
        val activation = wsk.waitForActivation(id, initialWait, pollPeriod, totalWait)
        if (activation.isLeft) {
            assert(false, s"error waiting for activation $id: ${activation.left.get}")
        } else try {
            check(activation.right.get.convertTo[ActivationResult])
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
            check: Seq[ActivationResult] => Unit)(
                implicit wskprops: WskProps): Unit = {

        val activationIds = wsk.pollFor(N, Some(entity), since = since, retries = (totalWait / pollPeriod).toInt, pollPeriod = pollPeriod)
        withClue(s"expecting $N activations matching '$entity' name since $since but found ${activationIds.mkString(",")} instead") {
            activationIds.length shouldBe N
        }

        val parsed = activationIds.map { id =>
            wsk.parseJsonString(wsk.get(Some(id)).stdout).convertTo[ActivationResult]
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

    def removeCLIHeader(response: String): String = response.substring(response.indexOf("\n"))

    def getJSONFromCLIResponse(response: String): JsObject = removeCLIHeader(response).parseJson.asJsObject

    def getAdditionalTestSubject(newUser: String): WskProps = {
        val wskadmin = new RunWskAdminCmd {}
        WskProps(
            namespace = newUser,
            authKey = wskadmin.cli(Seq("user", "create", newUser)).stdout.trim)
    }

    def disposeAdditionalTestSubject(subject: String): Unit = {
        val wskadmin = new RunWskAdminCmd {}
        withClue(s"failed to delete temporary subject $subject") {
            wskadmin.cli(Seq("user", "delete", subject)).stdout should include("Subject deleted")
        }
    }
}
