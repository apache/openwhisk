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

import spray.json._
import spray.json.DefaultJsonProtocol._

import TestUtils.RunResult
import TestUtils.SUCCESS_EXIT
import TestUtils.CONFLICT
import akka.http.scaladsl.model.StatusCodes

object FullyQualifiedNames {

  /**
   * Fully qualifies the name of an entity with its namespace.
   * If the name already starts with the PATHSEP character, then
   * it already is fully qualified. Otherwise (package name or
   * basic entity name) it is prefixed with the namespace. The
   * namespace is derived from the implicit whisk properties.
   *
   * @param name to fully qualify iff it is not already fully qualified
   * @param wp whisk properties
   * @return name if it is fully qualified else a name fully qualified for a namespace
   */
  def fqn(name: String)(implicit wp: WskProps) = {
    val sep = "/" // Namespace.PATHSEP
    if (name.startsWith(sep) || name.count(_ == sep(0)) == 2) name
    else s"$sep${wp.namespace}$sep$name"
  }

  /**
   * Resolves a namespace. If argument is defined, it takes precedence.
   * else resolve to namespace in implicit WskProps.
   *
   * @param namespace an optional namespace
   * @param wp whisk properties
   * @return resolved namespace
   */
  def resolve(namespace: Option[String])(implicit wp: WskProps) = {
    val sep = "/" // Namespace.PATHSEP
    namespace getOrElse s"$sep${wp.namespace}"
  }
}

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
 * @param cause String to save the cause of failure if the activation fails
 * @param annotations a list of JSON objects to save the annotations of the activation
 */
case class ActivationResult(activationId: String,
                            logs: Option[List[String]],
                            response: ActivationResponse,
                            start: Instant,
                            end: Instant,
                            duration: Long,
                            cause: Option[String],
                            annotations: Option[List[JsObject]]) {

  def getAnnotationValue(key: String): Option[JsValue] =
    annotations
      .flatMap(_.find(_.fields("key").convertTo[String] == key))
      .map(_.fields("value"))
}

object ActivationResult extends DefaultJsonProtocol {
  private implicit val instantSerdes = new RootJsonFormat[Instant] {
    def write(t: Instant) = t.toEpochMilli.toJson

    def read(value: JsValue) =
      Try {
        value match {
          case JsNumber(i) => Instant.ofEpochMilli(i.bigDecimal.longValue)
          case _           => deserializationError("timestamp malformed")
        }
      } getOrElse deserializationError("timestamp malformed 2")
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
            new ActivationResult(
              activationId,
              logs,
              response.convertTo[ActivationResponse],
              start.convertTo[Instant],
              end,
              duration,
              cause,
              annotations)
          } getOrElse deserializationError("Failed to deserialize the activation result.")
        case _ => deserializationError("Failed to deserialize the activation ID, response or start.")
      }
    }
  }
}

/** The result of a rule-activation written into the trigger activation */
case class RuleActivationResult(statusCode: Int, success: Boolean, activationId: String, action: String)
object RuleActivationResult extends DefaultJsonProtocol {
  implicit val serdes = jsonFormat4(RuleActivationResult.apply)
}

/**
 * Test fixture to ease cleaning of whisk entities created during testing.
 *
 * The fixture records the entities created during a test and when the test
 * completed, will delete them all.
 */
trait WskTestHelpers extends Matchers {
  type Assets = ListBuffer[(DeleteFromCollectionOperations, String, Boolean)]

  /**
   * Helper to register an entity to delete once a test completes.
   * The helper sanitizes (deletes) a previous instance of the entity if it exists
   * in given collection.
   *
   */
  class AssetCleaner(assetsToDeleteAfterTest: Assets, wskprops: WskProps) {
    def withCleaner[T <: DeleteFromCollectionOperations](cli: T, name: String, confirmDelete: Boolean = true)(
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
  def withAssetCleaner[T](wskprops: WskProps)(test: (WskProps, AssetCleaner) => T): T = {
    // create new asset list to track what must be deleted after test completes
    val assetsToDeleteAfterTest = new Assets()

    try {
      test(wskprops, new AssetCleaner(assetsToDeleteAfterTest, wskprops))
    } catch {
      case t: Throwable =>
        // log the exception that occurred in the test and rethrow it
        println(s"Exception occurred during test execution: $t")
        t.printStackTrace()
        throw t
    } finally {
      // delete assets in reverse order so that was created last is deleted first
      val deletedAll = assetsToDeleteAfterTest.reverse map {
        case (cli, n, delete) =>
          n -> Try {
            cli match {
              case _: PackageOperations if delete =>
                // sanitize ignores the exit code, so we can inspect the actual result and retry accordingly
                val rr = cli.sanitize(n)(wskprops)
                rr.exitCode match {
                  case CONFLICT | StatusCodes.Conflict.intValue =>
                    org.apache.openwhisk.utils.retry({
                      println("package deletion conflict, view computation delay likely, retrying...")
                      cli.delete(n)(wskprops)
                    }, 5, Some(1.second))
                  case _ => rr
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
    wsk: ActivationOperations,
    run: RunResult,
    initialWait: Duration = 1.second,
    pollPeriod: Duration = 1.second,
    totalWait: Duration = 120.seconds)(check: ActivationResult => Unit)(implicit wskprops: WskProps): Unit = {
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
  def withActivation(wsk: ActivationOperations,
                     activationId: String,
                     initialWait: Duration,
                     pollPeriod: Duration,
                     totalWait: Duration)(check: ActivationResult => Unit)(implicit wskprops: WskProps): Unit = {
    val id = activationId
    val activation = wsk.waitForActivation(id, initialWait, pollPeriod, totalWait)

    activation match {
      case Left(reason) => fail(s"error waiting for activation $id for $totalWait: $reason")
      case Right(result) =>
        withRethrowingPrint(s"check failed for activation $id: $result") {
          check(result.convertTo[ActivationResult])
        }
    }
  }
  def withActivation(wsk: ActivationOperations, activationId: String)(check: ActivationResult => Unit)(
    implicit wskprops: WskProps): Unit = {
    withActivation(wsk, activationId, 1.second, 1.second, 120.seconds)(check)
  }

  /**
   * In the case that test throws an exception, print stderr and stdout
   * from the provided RunResult.
   */
  def withPrintOnFailure(runResult: RunResult)(test: () => Unit): Unit = {
    try {
      test()
    } catch {
      case error: Throwable =>
        println(s"[stderr] ${runResult.stderr}")
        println(s"[stdout] ${runResult.stdout}")
        throw error
    }
  }

  /**
   * Prints the given information iff the inner test fails. Rethrows the tests exception to get a meaningful
   * stacktrace.
   *
   * @param information additional information to print
   * @param test test to run
   */
  def withRethrowingPrint(information: String)(test: => Unit): Unit = {
    try test
    catch {
      case error: Throwable =>
        println(information)
        throw error
    }
  }

  def getAdditionalTestSubject(newUser: String): WskProps = {
    import WskAdmin.wskadmin
    WskProps(namespace = newUser, authKey = wskadmin.cli(Seq("user", "create", newUser)).stdout.trim)
  }

  def disposeAdditionalTestSubject(subject: String, expectedExitCode: Int = SUCCESS_EXIT): Unit = {
    import WskAdmin.wskadmin
    withClue(s"failed to delete temporary subject $subject") {
      wskadmin.cli(Seq("user", "delete", subject), expectedExitCode).stdout should include("Subject deleted")
    }
  }

  /** Appends the current timestamp in ms. */
  def withTimestamp(text: String) = s"${text}-${System.currentTimeMillis}"

  /** Strips the first line if it ends in a new line as is common for CLI output. */
  def removeCLIHeader(response: String): String = {
    if (response.contains("\n")) response.substring(response.indexOf("\n")) else response
  }

  // using annotation will cause compile errors because we use -Xfatal-warnings
  // @deprecated(message = "use wsk.parseJsonString instead", since = "pr #3741")
  def getJSONFromResponse(response: String, isCli: Boolean = false): JsObject = {
    println("!!! WARNING: method is deprecated; use wsk.parseJsonString instead")
    if (isCli) removeCLIHeader(response).parseJson.asJsObject else response.parseJson.asJsObject
  }
}
