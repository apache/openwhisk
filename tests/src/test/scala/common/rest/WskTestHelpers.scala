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

package common.rest

import scala.collection.mutable.ListBuffer
import scala.util.Failure
import scala.util.Try
import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

import org.scalatest.Matchers

import common.WskProps
//import common.CliActivationResponse
import common.CliActivation
//import spray.json._
import java.time.Instant

import spray.http.StatusCodes.Conflict

/**
 * Test fixture to ease cleaning of whisk entities created during testing.
 *
 * The fixture records the entities created during a test and when the test
 * completed, will delete them all.
 */
trait WskTestHelpers extends Matchers {
    type AssetsRest = ListBuffer[(DeleteFromCollectionRest, String, Boolean)]
    /**
     * Helper to register an entity to delete once a test completes.
     * The helper sanitizes (deletes) a previous instance of the entity if it exists
     * in given collection.
     *
     */
    class AssetCleaner(assetsToDeleteAfterTest: AssetsRest, WskProps: WskProps) {
        def withCleaner[T <: DeleteFromCollectionRest](rest: T, name: String, confirmDelete: Boolean = true)(
            call: (T, String) => RestResult): RestResult = {
            // sanitize (delete) if asset exists
            rest.sanitize(name)(WskProps)

            assetsToDeleteAfterTest += ((rest, name, confirmDelete))
            call(rest, name)
        }
    }

    /**
     * Creates a test closure which records all entities created inside the test into a
     * list that is iterated at the end of the test so that these entities are deleted
     * (from most recently created to oldest).
     */
    def withAssetCleaner(WskProps: WskProps)(test: (WskProps, AssetCleaner) => Any) = {
        // create new asset list to track what must be deleted after test completes
        val assetsToDeleteAfterTest = new AssetsRest()

        try {
            test(WskProps, new AssetCleaner(assetsToDeleteAfterTest, WskProps))
        } catch {
            case t: Throwable =>
                // log the exception that occurred in the test and rethrow it
                println(s"Exception occurred during test execution: $t")
                throw t
        } finally {
            // delete assets in reverse order so that was created last is deleted first
            val deletedAll = assetsToDeleteAfterTest.reverse map {
                case ((rest, n, delete)) => n -> Try {
                    rest match {
                        case _: WskRestPackage if delete =>
                            val rr = rest.delete(n)(WskProps)
                            rr.statusCode match {
                                case Conflict => whisk.utils.retry(rest.delete(n)(WskProps), 5, Some(1.second))
                                case _        => rr
                            }
                        case _ => if (delete) rest.delete(n)(WskProps) else rest.sanitize(n)(WskProps)
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

    //case class RestActivationResponse(result: Option[JsObject], status: String, success: Boolean)

    //object RestActivationResponse extends DefaultJsonProtocol {
    //    implicit val serdes = jsonFormat3(CliActivationResponse.apply)
    //}

    /**
     * Activation record as it is returned by the REST.
     */
   /* case class RestActivation(
        activationId: String,
        logs: Option[List[String]],
        response: CliActivationResponse,
        start: Instant,
        end: Option[Instant] = Some(Instant.ofEpochSecond(0L)),
        duration: Option[Long] = Some(0L),
        cause: Option[String],
        annotations: Option[List[JsObject]]) {
        def getAnnotationValue(key: String): Option[JsValue] = {
            Try {
                val annotation = annotations.get.filter(x => x.getFields("key")(0) == JsString(key))
                assert(annotastion.size == 1) // only one annotation with this value
                val value = annotation(0).getFields("value")
                assert(value.size == 1)
                value(0)
            } toOption
        }
    }

    object RestActivation extends DefaultJsonProtocol {
        private implicit val instantSerdes = new RootJsonFormat[Instant] {
            def write(t: Instant) = t.toEpochMilli.toJson

            def read(value: JsValue) = Try {
                value match {
                    case JsNumber(i) => Instant.ofEpochMilli(i.bigDecimal.longValue)
                    case _           => deserializationError("timetsamp malformed")
                }
            } getOrElse deserializationError("timetsamp malformed 2")
        }

        implicit val serdes = jsonFormat8(RestActivation.apply)
    }*/

    def withActivation(
        wskrest: WskRestActivation,
        run: RestResult,
        initialWait: Duration = 1 second,
        pollPeriod: Duration = 1 second,
        totalWait: Duration = 30 seconds)(
            check: CliActivation => Unit)(
                implicit WskProps: WskProps): Unit = {
        val activationId = wskrest.extractActivationId(run)

        withClue(s"did not find an activation id in '$run'") {
            activationId shouldBe a[Some[_]]
        }
        withActivation(wskrest, activationId.get, initialWait, pollPeriod, totalWait)(check)
    }

    def withActivation(
        wskrest: WskRestActivation,
        activationId: String,
        initialWait: Duration,
        pollPeriod: Duration,
        totalWait: Duration)(
            check: CliActivation => Unit)(
                implicit WskProps: WskProps): Unit = {
        val id = activationId
        val activation = wskrest.waitForActivation(id, initialWait, pollPeriod, totalWait)
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

    def withActivationsFromEntity(
        wsk: WskRestActivation,
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
            wsk.getActivation(id).respBody.convertTo[CliActivation]
        }
        try {
            check(parsed)
        } catch {
            case error: Throwable =>
                println(s"check failed for activations $activationIds: ${parsed}")
                throw error
        }
    }

    def withPrintOnFailure(runResult: RestResult)(test: () => Unit) {
        try {
            test()
        } catch {
            case error: Throwable =>
                println(s"[stderr] ${runResult.respData}")
                throw error
        }
    }

    def getAdditionalTestSubject(newUser: String): WskProps = {
        val wskadmin = new RunWskRestAdminCmd {}
        WskProps(
            namespace = newUser,
            authKey = wskadmin.adminCommand(Seq("user", "create", newUser)).stdout.trim)
    }

    def disposeAdditionalTestSubject(subject: String): Unit = {
        val wskadmin = new RunWskRestAdminCmd {}
        withClue(s"failed to delete temporary subject $subject") {
            wskadmin.adminCommand(Seq("user", "delete", subject)).stdout should include("Subject deleted")
        }
    }
}
