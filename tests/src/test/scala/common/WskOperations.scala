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

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.time.Instant

import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.Duration
import scala.language.postfixOps
import TestUtils._
import spray.json._
import org.apache.openwhisk.core.entity.ByteSize

import scala.util.Try

case class WskProps(
  authKey: String = WhiskProperties.getAuthKeyForTesting,
  cert: String =
    WhiskProperties.getFileRelativeToWhiskHome("ansible/roles/nginx/files/openwhisk-client-cert.pem").getAbsolutePath,
  key: String =
    WhiskProperties.getFileRelativeToWhiskHome("ansible/roles/nginx/files/openwhisk-client-key.pem").getAbsolutePath,
  namespace: String = "_",
  apiversion: String = "v1",
  apihost: String = WhiskProperties.getEdgeHost,
  token: String = "",
  basicAuth: Boolean = true) {
  def overrides = Seq("-i", "--apihost", apihost, "--apiversion", apiversion)
  def writeFile(propsfile: File) = {
    val propsStr = s"""NAMESPACE=$namespace
                  |APIVERSION=$apiversion
                  |AUTH=$authKey
                  |APIHOST=$apihost
                  |APIGW_ACCESS_TOKEN=$token""".stripMargin
    val bw = new BufferedWriter(new FileWriter(propsfile))
    try {
      bw.write(propsStr)
    } finally {
      bw.close()
    }
  }
}

trait WaitFor {

  /**
   * Waits up to totalWait seconds for a 'step' to return value.
   * Often tests call this routine immediately after starting work.
   * Performs an initial wait before entering poll loop.
   */
  def waitfor[T](step: () => T,
                 initialWait: Duration = 1 second,
                 pollPeriod: Duration = 1 second,
                 totalWait: Duration = 30 seconds): T = {
    Thread.sleep(initialWait.toMillis)
    val endTime = System.currentTimeMillis() + totalWait.toMillis
    while (System.currentTimeMillis() < endTime) {
      val predicate = step()
      predicate match {
        case (t: Boolean) if t =>
          return predicate
        case (t: Any) if t != null && !t.isInstanceOf[Boolean] =>
          return predicate
        case _ if System.currentTimeMillis() >= endTime =>
          return predicate
        case _ =>
          Thread.sleep(pollPeriod.toMillis)
      }
    }
    null.asInstanceOf[T]
  }
}

trait HasActivation {

  /**
   * Extracts activation id from invoke (action or trigger) or activation get
   */
  def extractActivationId(result: RunResult): Option[String] = {
    Try {
      // try to interpret the run result as the result of an invoke
      extractActivationIdFromInvoke(result) getOrElse extractActivationIdFromActivation(result).get
    } toOption
  }

  /**
   * Extracts activation id from 'wsk activation get' run result
   */
  private def extractActivationIdFromActivation(result: RunResult): Option[String] = {
    Try {
      // a characteristic string that comes right before the activationId
      val idPrefix = "ok: got activation "
      val output = if (result.exitCode != SUCCESS_EXIT) result.stderr else result.stdout
      assert(output.contains(idPrefix), output)
      extractActivationId(idPrefix, output).get
    } toOption
  }

  /**
   * Extracts activation id from 'wsk action invoke' or 'wsk trigger invoke'
   */
  private def extractActivationIdFromInvoke(result: RunResult): Option[String] = {
    Try {
      val output = if (result.exitCode != SUCCESS_EXIT) result.stderr else result.stdout
      assert(output.contains("ok: invoked") || output.contains("ok: triggered"), output)
      // a characteristic string that comes right before the activationId
      val idPrefix = "with id "
      extractActivationId(idPrefix, output).get
    } toOption
  }

  /**
   * Extracts activation id preceded by a prefix (idPrefix) from a string (output)
   *
   * @param idPrefix the prefix of the activation id
   * @param output the string to be used in the extraction
   * @return an option containing the id as a string or None if the extraction failed for any reason
   */
  private def extractActivationId(idPrefix: String, output: String): Option[String] = {
    Try {
      val start = output.indexOf(idPrefix) + idPrefix.length
      var end = start
      assert(start > 0)
      while (end < output.length && output.charAt(end) != '\n') end = end + 1
      output.substring(start, end) // a uuid
    } toOption
  }
}

trait WskOperations {
  val action: ActionOperations
  val trigger: TriggerOperations
  val rule: RuleOperations
  val activation: ActivationOperations
  val pkg: PackageOperations
  val namespace: NamespaceOperations
  val api: GatewayOperations

  /**
   * Utility function which strips the leading line if it ends in a newline (present when output is from
   * wsk CLI) and parses the rest as a JSON object.
   */
  def parseJsonString(jsonStr: String): JsObject = WskOperations.parseJsonString(jsonStr)
}

object WskOperations {

  /**
   * Utility function which strips the leading line if it ends in a newline (present when output is from
   * wsk CLI) and parses the rest as a JSON object.
   */
  def parseJsonString(jsonStr: String): JsObject = {
    jsonStr.substring(jsonStr.indexOf("\n") + 1).parseJson.asJsObject // Skip optional status line before parsing
  }
}

trait ListOrGetFromCollectionOperations {

  protected val noun: String

  /**
   * List entities in collection.
   *
   * @param namespace (optional) if specified must be  fully qualified namespace
   * @param expectedExitCode (optional) the expected exit code for the command
   * if the code is anything but DONTCARE_EXIT, assert the code is as expected
   */
  def list(namespace: Option[String] = None,
           limit: Option[Int] = None,
           nameSort: Option[Boolean] = None,
           expectedExitCode: Int = SUCCESS_EXIT)(implicit wp: WskProps): RunResult

  /**
   * Gets entity from collection.
   *
   * @param name either a fully qualified name or a simple entity name
   * @param expectedExitCode (optional) the expected exit code for the command
   * if the code is anything but DONTCARE_EXIT, assert the code is as expected
   */
  def get(name: String,
          expectedExitCode: Int = SUCCESS_EXIT,
          summary: Boolean = false,
          fieldFilter: Option[String] = None,
          url: Option[Boolean] = None,
          save: Option[Boolean] = None,
          saveAs: Option[String] = None)(implicit wp: WskProps): RunResult
}

trait DeleteFromCollectionOperations {

  protected val noun: String

  /**
   * Deletes entity from collection.
   *
   * @param name either a fully qualified name or a simple entity name
   * @param expectedExitCode (optional) the expected exit code for the command
   * if the code is anything but DONTCARE_EXIT, assert the code is as expected
   */
  def delete(name: String, expectedExitCode: Int = SUCCESS_EXIT)(implicit wp: WskProps): RunResult

  /**
   * Deletes entity from collection but does not assert that the command succeeds.
   * Use this if deleting an entity that may not exist and it is OK if it does not.
   *
   * @param name either a fully qualified name or a simple entity name
   */
  def sanitize(name: String)(implicit wp: WskProps): RunResult
}

trait ActionOperations extends DeleteFromCollectionOperations with ListOrGetFromCollectionOperations {

  def create(name: String,
             artifact: Option[String],
             kind: Option[String] = None,
             main: Option[String] = None,
             docker: Option[String] = None,
             parameters: Map[String, JsValue] = Map.empty,
             annotations: Map[String, JsValue] = Map.empty,
             delAnnotations: Array[String] = Array(),
             parameterFile: Option[String] = None,
             annotationFile: Option[String] = None,
             timeout: Option[Duration] = None,
             memory: Option[ByteSize] = None,
             logsize: Option[ByteSize] = None,
             concurrency: Option[Int] = None,
             shared: Option[Boolean] = None,
             update: Boolean = false,
             web: Option[String] = None,
             websecure: Option[String] = None,
             expectedExitCode: Int = SUCCESS_EXIT)(implicit wp: WskProps): RunResult

  def invoke(name: String,
             parameters: Map[String, JsValue] = Map.empty,
             parameterFile: Option[String] = None,
             blocking: Boolean = false,
             result: Boolean = false,
             expectedExitCode: Int = SUCCESS_EXIT)(implicit wp: WskProps): RunResult
}

trait PackageOperations extends DeleteFromCollectionOperations with ListOrGetFromCollectionOperations {

  def create(name: String,
             parameters: Map[String, JsValue] = Map.empty,
             annotations: Map[String, JsValue] = Map.empty,
             parameterFile: Option[String] = None,
             annotationFile: Option[String] = None,
             shared: Option[Boolean] = None,
             update: Boolean = false,
             expectedExitCode: Int = SUCCESS_EXIT)(implicit wp: WskProps): RunResult

  def bind(provider: String,
           name: String,
           parameters: Map[String, JsValue] = Map.empty,
           annotations: Map[String, JsValue] = Map.empty,
           expectedExitCode: Int = SUCCESS_EXIT)(implicit wp: WskProps): RunResult
}

trait TriggerOperations extends DeleteFromCollectionOperations with ListOrGetFromCollectionOperations {

  def create(name: String,
             parameters: Map[String, JsValue] = Map.empty,
             annotations: Map[String, JsValue] = Map.empty,
             parameterFile: Option[String] = None,
             annotationFile: Option[String] = None,
             feed: Option[String] = None,
             shared: Option[Boolean] = None,
             update: Boolean = false,
             expectedExitCode: Int = SUCCESS_EXIT)(implicit wp: WskProps): RunResult

  def fire(name: String,
           parameters: Map[String, JsValue] = Map.empty,
           parameterFile: Option[String] = None,
           expectedExitCode: Int = SUCCESS_EXIT)(implicit wp: WskProps): RunResult
}

trait RuleOperations extends DeleteFromCollectionOperations with ListOrGetFromCollectionOperations {

  def create(name: String,
             trigger: String,
             action: String,
             annotations: Map[String, JsValue] = Map.empty,
             shared: Option[Boolean] = None,
             update: Boolean = false,
             expectedExitCode: Int = SUCCESS_EXIT)(implicit wp: WskProps): RunResult

  def enable(name: String, expectedExitCode: Int = SUCCESS_EXIT)(implicit wp: WskProps): RunResult

  def disable(name: String, expectedExitCode: Int = SUCCESS_EXIT)(implicit wp: WskProps): RunResult

  def state(name: String, expectedExitCode: Int = SUCCESS_EXIT)(implicit wp: WskProps): RunResult
}

trait ActivationOperations {

  def extractActivationId(result: RunResult): Option[String]

  def pollFor(N: Int,
              entity: Option[String],
              limit: Option[Int] = None,
              since: Option[Instant] = None,
              skip: Option[Int] = None,
              retries: Int,
              pollPeriod: Duration = 1.second)(implicit wp: WskProps): Seq[String]

  def waitForActivation(activationId: String, initialWait: Duration, pollPeriod: Duration, totalWait: Duration)(
    implicit wp: WskProps): Either[String, JsObject]

  def get(activationId: Option[String] = None,
          expectedExitCode: Int = SUCCESS_EXIT,
          fieldFilter: Option[String] = None,
          last: Option[Boolean] = None,
          summary: Option[Boolean] = None)(implicit wp: WskProps): RunResult

  def console(duration: Duration,
              since: Option[Duration] = None,
              expectedExitCode: Int = SUCCESS_EXIT,
              actionName: Option[String] = None)(implicit wp: WskProps): RunResult

  def logs(activationId: Option[String] = None, expectedExitCode: Int = SUCCESS_EXIT, last: Option[Boolean] = None)(
    implicit wp: WskProps): RunResult

  def result(activationId: Option[String] = None, expectedExitCode: Int = SUCCESS_EXIT, last: Option[Boolean] = None)(
    implicit wp: WskProps): RunResult
}

trait NamespaceOperations {

  def list(expectedExitCode: Int = SUCCESS_EXIT, nameSort: Option[Boolean] = None)(implicit wp: WskProps): RunResult

  def whois()(implicit wskprops: WskProps): String
}

trait GatewayOperations {

  def create(basepath: Option[String] = None,
             relpath: Option[String] = None,
             operation: Option[String] = None,
             action: Option[String] = None,
             apiname: Option[String] = None,
             swagger: Option[String] = None,
             responsetype: Option[String] = None,
             expectedExitCode: Int = SUCCESS_EXIT,
             cliCfgFile: Option[String] = None)(implicit wp: WskProps): RunResult

  def list(basepathOrApiName: Option[String] = None,
           relpath: Option[String] = None,
           operation: Option[String] = None,
           limit: Option[Int] = None,
           since: Option[Instant] = None,
           full: Option[Boolean] = None,
           nameSort: Option[Boolean] = None,
           expectedExitCode: Int = SUCCESS_EXIT,
           cliCfgFile: Option[String] = None)(implicit wp: WskProps): RunResult

  def get(basepathOrApiName: Option[String] = None,
          full: Option[Boolean] = None,
          expectedExitCode: Int = SUCCESS_EXIT,
          cliCfgFile: Option[String] = None,
          format: Option[String] = None)(implicit wp: WskProps): RunResult

  def delete(basepathOrApiName: String,
             relpath: Option[String] = None,
             operation: Option[String] = None,
             expectedExitCode: Int = SUCCESS_EXIT,
             cliCfgFile: Option[String] = None)(implicit wp: WskProps): RunResult
}
