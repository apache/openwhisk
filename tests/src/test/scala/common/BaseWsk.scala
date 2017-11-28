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
import scala.collection.mutable.Buffer
import scala.concurrent.duration.Duration
import scala.language.postfixOps
import org.scalatest.Matchers

import TestUtils._
import spray.json.JsObject
import spray.json.JsValue
import spray.json.pimpString
import whisk.core.entity.ByteSize

case class WskProps(
  authKey: String = WhiskProperties.readAuthKey(WhiskProperties.getAuthFileForTesting),
  cert: String =
    WhiskProperties.getFileRelativeToWhiskHome("ansible/roles/nginx/files/openwhisk-client-cert.pem").getAbsolutePath,
  key: String =
    WhiskProperties.getFileRelativeToWhiskHome("ansible/roles/nginx/files/openwhisk-client-key.pem").getAbsolutePath,
  namespace: String = "_",
  apiversion: String = "v1",
  apihost: String = WhiskProperties.getEdgeHost,
  token: String = "") {
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

trait BaseWsk extends BaseRunWsk {
  val action: BaseAction
  val trigger: BaseTrigger
  val rule: BaseRule
  val activation: BaseActivation
  val pkg: BasePackage
  val namespace: BaseNamespace
  val api: BaseApi
}

trait FullyQualifiedNames {

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

trait BaseListOrGetFromCollection extends FullyQualifiedNames {

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

trait BaseDeleteFromCollection extends FullyQualifiedNames {

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

trait BaseAction extends BaseRunWsk with BaseDeleteFromCollection with BaseListOrGetFromCollection {

  def create(name: String,
             artifact: Option[String],
             kind: Option[String] = None,
             main: Option[String] = None,
             docker: Option[String] = None,
             parameters: Map[String, JsValue] = Map(),
             annotations: Map[String, JsValue] = Map(),
             parameterFile: Option[String] = None,
             annotationFile: Option[String] = None,
             timeout: Option[Duration] = None,
             memory: Option[ByteSize] = None,
             logsize: Option[ByteSize] = None,
             shared: Option[Boolean] = None,
             update: Boolean = false,
             web: Option[String] = None,
             expectedExitCode: Int = SUCCESS_EXIT)(implicit wp: WskProps): RunResult

  def invoke(name: String,
             parameters: Map[String, JsValue] = Map(),
             parameterFile: Option[String] = None,
             blocking: Boolean = false,
             result: Boolean = false,
             expectedExitCode: Int = SUCCESS_EXIT)(implicit wp: WskProps): RunResult
}

trait BasePackage extends BaseRunWsk with BaseDeleteFromCollection with BaseListOrGetFromCollection {

  def create(name: String,
             parameters: Map[String, JsValue] = Map(),
             annotations: Map[String, JsValue] = Map(),
             parameterFile: Option[String] = None,
             annotationFile: Option[String] = None,
             shared: Option[Boolean] = None,
             update: Boolean = false,
             expectedExitCode: Int = SUCCESS_EXIT)(implicit wp: WskProps): RunResult

  def bind(provider: String,
           name: String,
           parameters: Map[String, JsValue] = Map(),
           annotations: Map[String, JsValue] = Map(),
           expectedExitCode: Int = SUCCESS_EXIT)(implicit wp: WskProps): RunResult
}

trait BaseTrigger extends BaseRunWsk with BaseDeleteFromCollection with BaseListOrGetFromCollection {

  def create(name: String,
             parameters: Map[String, JsValue] = Map(),
             annotations: Map[String, JsValue] = Map(),
             parameterFile: Option[String] = None,
             annotationFile: Option[String] = None,
             feed: Option[String] = None,
             shared: Option[Boolean] = None,
             update: Boolean = false,
             expectedExitCode: Int = SUCCESS_EXIT)(implicit wp: WskProps): RunResult

  def fire(name: String,
           parameters: Map[String, JsValue] = Map(),
           parameterFile: Option[String] = None,
           expectedExitCode: Int = SUCCESS_EXIT)(implicit wp: WskProps): RunResult
}

trait BaseRule extends BaseRunWsk with BaseDeleteFromCollection with BaseListOrGetFromCollection {

  def create(name: String,
             trigger: String,
             action: String,
             annotations: Map[String, JsValue] = Map(),
             shared: Option[Boolean] = None,
             update: Boolean = false,
             expectedExitCode: Int = SUCCESS_EXIT)(implicit wp: WskProps): RunResult

  def enable(name: String, expectedExitCode: Int = SUCCESS_EXIT)(implicit wp: WskProps): RunResult

  def disable(name: String, expectedExitCode: Int = SUCCESS_EXIT)(implicit wp: WskProps): RunResult

  def state(name: String, expectedExitCode: Int = SUCCESS_EXIT)(implicit wp: WskProps): RunResult
}

trait BaseActivation extends BaseRunWsk {

  def extractActivationId(result: RunResult): Option[String]

  def pollFor(N: Int,
              entity: Option[String],
              limit: Option[Int] = None,
              since: Option[Instant] = None,
              retries: Int,
              pollPeriod: Duration = 1.second)(implicit wp: WskProps): Seq[String]

  def waitForActivation(activationId: String, initialWait: Duration, pollPeriod: Duration, totalWait: Duration)(
    implicit wp: WskProps): Either[String, JsObject]

  def get(activationId: Option[String] = None,
          expectedExitCode: Int = SUCCESS_EXIT,
          fieldFilter: Option[String] = None,
          last: Option[Boolean] = None)(implicit wp: WskProps): RunResult

  def console(duration: Duration, since: Option[Duration] = None, expectedExitCode: Int = SUCCESS_EXIT)(
    implicit wp: WskProps): RunResult

  def logs(activationId: Option[String] = None, expectedExitCode: Int = SUCCESS_EXIT, last: Option[Boolean] = None)(
    implicit wp: WskProps): RunResult

  def result(activationId: Option[String] = None, expectedExitCode: Int = SUCCESS_EXIT, last: Option[Boolean] = None)(
    implicit wp: WskProps): RunResult
}

trait BaseNamespace extends BaseRunWsk {

  def list(expectedExitCode: Int = SUCCESS_EXIT, nameSort: Option[Boolean] = None)(implicit wp: WskProps): RunResult

  def whois()(implicit wskprops: WskProps): String

  def get(namespace: Option[String] = None, expectedExitCode: Int, nameSort: Option[Boolean] = None)(
    implicit wp: WskProps): RunResult
}

trait BaseApi extends BaseRunWsk {

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

trait BaseRunWsk extends Matchers {

  // Takes a string and a list of sensitive strings. Any sensistive string found in
  // the target string will be replaced with "XXXXX", returning the processed string
  def hideStr(str: String, hideThese: Seq[String]): String = {
    // Iterate through each string to hide, replacing it in the target string (str)
    hideThese.fold(str)((updatedStr, replaceThis) => updatedStr.replace(replaceThis, "XXXXX"))
  }

  /*
   * Utility function to return a JSON object from the CLI output that returns
   * an optional a status line following by the JSON data
   */
  def parseJsonString(jsonStr: String): JsObject = {
    jsonStr.substring(jsonStr.indexOf("\n") + 1).parseJson.asJsObject // Skip optional status line before parsing
  }

  def reportFailure(args: Buffer[String], ec: Integer, rr: RunResult) = {
    val s = new StringBuilder()
    s.append(args.mkString(" ") + "\n")
    if (rr.stdout.nonEmpty) s.append(rr.stdout + "\n")
    if (rr.stderr.nonEmpty) s.append(rr.stderr)
    s.append("exit code:")
  }
}
