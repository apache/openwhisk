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

import java.io.File
import java.time.Instant
import java.util.Base64
import java.security.cert.X509Certificate

import org.apache.commons.io.FileUtils
import org.scalatest.Matchers
import org.scalatest.FlatSpec
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.Span.convertDurationToSpan

import scala.Left
import scala.Right
import scala.collection.JavaConversions.mapAsJavaMap
import scala.collection.mutable.Buffer
import scala.collection.immutable.Seq
import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt
import scala.concurrent.Future
import scala.language.postfixOps
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.{Failure, Success}

import akka.http.scaladsl.model.StatusCode
import akka.http.scaladsl.model.StatusCodes.Accepted
import akka.http.scaladsl.model.StatusCodes.NotFound
import akka.http.scaladsl.model.StatusCodes.BadRequest
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpMethod
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.headers.Authorization
import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.model.ContentTypes
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.headers.BasicHttpCredentials
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model.HttpMethods.DELETE
import akka.http.scaladsl.model.HttpMethods.GET
import akka.http.scaladsl.model.HttpMethods.POST
import akka.http.scaladsl.model.HttpMethods.PUT
import akka.http.scaladsl.HttpsConnectionContext

import akka.stream.ActorMaterializer

import spray.json._
import spray.json.DefaultJsonProtocol._
import spray.json.JsObject
import spray.json.JsValue
import spray.json.pimpString

import common._
import common.BaseDeleteFromCollection
import common.BaseListOrGetFromCollection
import common.HasActivation
import common.RunWskCmd
import common.TestUtils
import common.TestUtils.SUCCESS_EXIT
import common.TestUtils.DONTCARE_EXIT
import common.TestUtils.ANY_ERROR_EXIT
import common.TestUtils.DONTCARE_EXIT
import common.TestUtils.RunResult
import common.WaitFor
import common.WhiskProperties
import common.WskActorSystem
import common.WskProps

import whisk.core.entity.ByteSize
import whisk.utils.retry

import javax.net.ssl.{HostnameVerifier, KeyManager, SSLContext, SSLSession, X509TrustManager}

import com.typesafe.sslconfig.akka.AkkaSSLConfig

class AcceptAllHostNameVerifier extends HostnameVerifier {
  def verify(s: String, sslSession: SSLSession) = true
}

object SSL {
  lazy val nonValidatingContext: SSLContext = {
    class IgnoreX509TrustManager extends X509TrustManager {
      def checkClientTrusted(chain: Array[X509Certificate], authType: String) = ()
      def checkServerTrusted(chain: Array[X509Certificate], authType: String) = ()
      def getAcceptedIssuers = Array[X509Certificate]()
    }

    val context = SSLContext.getInstance("TLS")
    context.init(Array[KeyManager](), Array(new IgnoreX509TrustManager), null)
    context
  }
}

class WskRest() extends RunWskRestCmd with BaseWsk {
  override implicit val action = new WskRestAction
  override implicit val trigger = new WskRestTrigger
  override implicit val rule = new WskRestRule
  override implicit val activation = new WskRestActivation
  override implicit val pkg = new WskRestPackage
  override implicit val namespace = new WskRestNamespace
  override implicit val api = new WskRestApi
}

trait ListOrGetFromCollectionRest extends BaseListOrGetFromCollection {
  self: RunWskRestCmd =>

  /**
   * List entities in collection.
   *
   * @param namespace (optional) if specified must be  fully qualified namespace
   * @param expectedExitCode (optional) the expected exit code for the command
   * if the code is anything but DONTCARE_EXIT, assert the code is as expected
   */
  override def list(namespace: Option[String] = None,
                    limit: Option[Int] = None,
                    nameSort: Option[Boolean] = None,
                    expectedExitCode: Int = OK.intValue)(implicit wp: WskProps): RestResult = {

    val entPath = namespace map { ns =>
      val (ns, name) = getNamespaceEntityName(resolve(namespace))
      Path(s"$basePath/namespaces/$ns/$noun/$name/")
    } getOrElse Path(s"$basePath/namespaces/${wp.namespace}/$noun")

    val paramMap = Map[String, String]() ++ { Map("skip" -> "0", "docs" -> true.toString) } ++ {
      limit map { l =>
        Map("limit" -> l.toString)
      } getOrElse Map[String, String]()
    }
    val resp = requestEntity(GET, entPath, paramMap)
    val r = new RestResult(resp.status, getRespData(resp))
    validateStatusCode(expectedExitCode, r.statusCode.intValue)
    r
  }

  /**
   * Gets entity from collection.
   *
   * @param name either a fully qualified name or a simple entity name
   * @param expectedExitCode (optional) the expected exit code for the command
   * if the code is anything but DONTCARE_EXIT, assert the code is as expected
   */
  override def get(name: String,
                   expectedExitCode: Int = OK.intValue,
                   summary: Boolean = false,
                   fieldFilter: Option[String] = None,
                   url: Option[Boolean] = None,
                   save: Option[Boolean] = None,
                   saveAs: Option[String] = None)(implicit wp: WskProps): RestResult = {
    val (ns, entity) = getNamespaceEntityName(name)
    val entPath = Path(s"$basePath/namespaces/$ns/$noun/$entity")
    val resp = requestEntity(GET, entPath)(wp)
    val r = new RestResult(resp.status, getRespData(resp))
    validateStatusCode(expectedExitCode, r.statusCode.intValue)
    r
  }
}

trait DeleteFromCollectionRest extends BaseDeleteFromCollection {
  self: RunWskRestCmd =>

  /**
   * Deletes entity from collection.
   *
   * @param name either a fully qualified name or a simple entity name
   * @param expectedExitCode (optional) the expected exit code for the command
   * if the code is anything but DONTCARE_EXIT, assert the code is as expected
   */
  override def delete(entity: String, expectedExitCode: Int = OK.intValue)(implicit wp: WskProps): RestResult = {
    val (ns, entityName) = getNamespaceEntityName(entity)
    val path = Path(s"$basePath/namespaces/$ns/$noun/$entityName")
    val resp = requestEntity(DELETE, path)(wp)
    val r = new RestResult(resp.status, getRespData(resp))
    validateStatusCode(expectedExitCode, r.statusCode.intValue)
    r
  }

  /**
   * Deletes entity from collection but does not assert that the command succeeds.
   * Use this if deleting an entity that may not exist and it is OK if it does not.
   *
   * @param name either a fully qualified name or a simple entity name
   */
  override def sanitize(name: String)(implicit wp: WskProps): RestResult = {
    delete(name, DONTCARE_EXIT)
  }
}

trait HasActivationRest extends HasActivation {

  /**
   * Extracts activation id from invoke (action or trigger) or activation get
   */
  override def extractActivationId(result: RunResult): Option[String] = {
    extractActivationIdFromInvoke(result.asInstanceOf[RestResult])
  }

  /**
   * Extracts activation id from 'wsk action invoke' or 'wsk trigger invoke'
   */
  private def extractActivationIdFromInvoke(result: RestResult): Option[String] = {
    if ((result.statusCode == OK) || (result.statusCode == Accepted))
      Some(result.getField("activationId"))
    else
      None
  }
}

class WskRestAction
    extends RunWskRestCmd
    with ListOrGetFromCollectionRest
    with DeleteFromCollectionRest
    with HasActivationRest
    with BaseAction {

  override protected val noun = "actions"

  /**
   * Creates action. Parameters mirror those available in the REST.
   *
   * @param name either a fully qualified name or a simple entity name
   * @param expectedExitCode (optional) the expected exit code for the command
   * if the code is anything but DONTCARE_EXIT, assert the code is as expected
   */
  override def create(
    name: String,
    artifact: Option[String],
    kind: Option[String] = None, // one of docker, copy, sequence or none for autoselect else an explicit type
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
    expectedExitCode: Int = OK.intValue)(implicit wp: WskProps): RestResult = {

    val (namespace, actName) = getNamespaceEntityName(name)
    var exec = Map[String, JsValue]()
    var code = ""
    var kindType = ""
    var artifactName = ""
    artifact match {
      case Some(artifactFile) => {
        val ext = getExt(artifactFile)
        artifactName = artifactFile
        ext match {
          case ".jar" => {
            kindType = "java:default"
            val jar = FileUtils.readFileToByteArray(new File(artifactFile))
            code = Base64.getEncoder.encodeToString(jar)
          }
          case ".zip" => {
            val zip = FileUtils.readFileToByteArray(new File(artifactFile))
            code = Base64.getEncoder.encodeToString(zip)
          }
          case ".js" => {
            kindType = "nodejs:default"
            code = FileUtils.readFileToString(new File(artifactFile))
          }
          case ".py" => {
            kindType = "python:default"
            code = FileUtils.readFileToString(new File(artifactFile))
          }
          case ".swift" => {
            kindType = "swift:default"
            code = FileUtils.readFileToString(new File(artifactFile))
          }
          case ".php" => {
            kindType = "php:default"
            code = FileUtils.readFileToString(new File(artifactFile))
          }
          case _ =>
        }
      }
      case None =>
    }

    kindType = docker map { d =>
      "blackbox"
    } getOrElse kindType

    var (params, annos) = getParamsAnnos(parameters, annotations, parameterFile, annotationFile, web = web)

    kind match {
      case Some(k) => {
        k match {
          case "copy" => {
            val actName = entityName(artifactName)
            val actionPath = Path(s"$basePath/namespaces/$namespace/$noun/$actName")
            val resp = requestEntity(GET, actionPath)
            if (resp == None)
              return new RestResult(NotFound)
            else {
              val result = new RestResult(resp.status, getRespData(resp))
              params = JsArray(result.getFieldListJsObject("parameters"))
              annos = JsArray(result.getFieldListJsObject("annotations"))
              exec = result.getFieldJsObject("exec").fields
            }
          }
          case "sequence" => {
            val comps = convertIntoComponents(artifactName)
            exec = Map("components" -> comps.toJson, "kind" -> k.toJson)
          }
          case "native" => {
            exec = Map("code" -> code.toJson, "kind" -> "blackbox".toJson, "image" -> "openwhisk/dockerskeleton".toJson)
          }
          case _ => {
            exec = Map("code" -> code.toJson, "kind" -> k.toJson)
          }
        }
      }
      case None => exec = Map("code" -> code.toJson, "kind" -> kindType.toJson)
    }

    exec = exec ++ {
      main map { m =>
        Map("main" -> m.toJson)
      } getOrElse Map[String, JsValue]()
    } ++ {
      docker map { d =>
        Map("kind" -> "blackbox".toJson, "image" -> d.toJson)
      } getOrElse Map[String, JsValue]()
    }

    var bodyContent = Map("name" -> name.toJson, "namespace" -> namespace.toJson)

    if (update) {
      kind match {
        case Some(k) if (k == "sequence" || k == "native") => {
          bodyContent = bodyContent ++ Map("exec" -> exec.toJson)
        }
        case _ =>
      }

      bodyContent = bodyContent ++ {
        shared map { s =>
          Map("publish" -> s.toJson)
        } getOrElse Map[String, JsValue]()
      }

      val inputParams = convertMapIntoKeyValue(parameters)
      if (inputParams.elements.size > 0) {
        bodyContent = bodyContent ++ Map("parameters" -> params)
      }
      val inputAnnos = convertMapIntoKeyValue(annotations)
      if (inputAnnos.elements.size > 0) {
        bodyContent = bodyContent ++ Map("annotations" -> annos)
      }
    } else {
      bodyContent = bodyContent ++ Map("exec" -> exec.toJson, "parameters" -> params, "annotations" -> annos)

      bodyContent = bodyContent ++ {
        timeout map { t =>
          Map("limits" -> JsObject("timeout" -> t.toMillis.toJson))
        } getOrElse Map[String, JsValue]()
      }
    }

    val path = Path(s"$basePath/namespaces/$namespace/$noun/$actName")
    val resp =
      if (update) requestEntity(PUT, path, Map("overwrite" -> "true"), Some(JsObject(bodyContent).toString))
      else requestEntity(PUT, path, body = Some(JsObject(bodyContent).toString))
    val r = new RestResult(resp.status, getRespData(resp))
    validateStatusCode(expectedExitCode, r.statusCode.intValue)
    r
  }

  override def invoke(name: String,
                      parameters: Map[String, JsValue] = Map(),
                      parameterFile: Option[String] = None,
                      blocking: Boolean = false,
                      result: Boolean = false,
                      expectedExitCode: Int = Accepted.intValue)(implicit wp: WskProps): RestResult = {
    super.invokeAction(name, parameters, parameterFile, blocking, result, expectedExitCode = expectedExitCode)
  }
}

class WskRestTrigger
    extends RunWskRestCmd
    with ListOrGetFromCollectionRest
    with DeleteFromCollectionRest
    with HasActivationRest
    with BaseTrigger {

  override protected val noun = "triggers"

  /**
   * Creates trigger. Parameters mirror those available in the REST.
   *
   * @param name either a fully qualified name or a simple entity name
   * @param expectedExitCode (optional) the expected exit code for the command
   * if the code is anything but DONTCARE_EXIT, assert the code is as expected
   */
  override def create(name: String,
                      parameters: Map[String, JsValue] = Map(),
                      annotations: Map[String, JsValue] = Map(),
                      parameterFile: Option[String] = None,
                      annotationFile: Option[String] = None,
                      feed: Option[String] = None,
                      shared: Option[Boolean] = None,
                      update: Boolean = false,
                      expectedExitCode: Int = OK.intValue)(implicit wp: WskProps): RestResult = {

    val (ns, triggerName) = this.getNamespaceEntityName(name)
    val path = Path(s"$basePath/namespaces/$ns/$noun/$triggerName")
    val (params, annos) = getParamsAnnos(parameters, annotations, parameterFile, annotationFile, feed)
    var bodyContent = JsObject("name" -> name.toJson, "namespace" -> s"$ns".toJson)

    if (!update) {
      val published = shared.getOrElse(false)
      bodyContent = JsObject(
        bodyContent.fields + ("publish" -> published.toJson,
        "parameters" -> params, "annotations" -> annos))
    } else {
      bodyContent = shared map { s =>
        JsObject(bodyContent.fields + ("publish" -> s.toJson))
      } getOrElse bodyContent

      val inputParams = convertMapIntoKeyValue(parameters)
      if (inputParams.elements.size > 0) {
        bodyContent = JsObject(bodyContent.fields + ("parameters" -> params))
      }
      val inputAnnos = convertMapIntoKeyValue(annotations)
      if (inputAnnos.elements.size > 0) {
        bodyContent = JsObject(bodyContent.fields + ("annotations" -> annos))
      }
    }

    val resp =
      if (update) requestEntity(PUT, path, Map("overwrite" -> "true"), Some(bodyContent.toString))
      else requestEntity(PUT, path, body = Some(bodyContent.toString))
    val result = new RestResult(resp.status, getRespData(resp))
    if (result.statusCode != OK) {
      validateStatusCode(expectedExitCode, result.statusCode.intValue)
      result
    }
    val r = feed map { f =>
      // Invoke the feed
      val (nsFeed, feedName) = this.getNamespaceEntityName(f)
      val path = Path(s"$basePath/namespaces/$nsFeed/actions/$feedName")
      val paramMap = Map("blocking" -> "true", "result" -> "false")
      var body: Map[String, JsValue] = Map(
        "lifecycleEvent" -> "CREATE".toJson,
        "triggerName" -> s"/$ns/$triggerName".toJson,
        "authKey" -> s"${getAuthKey(wp)}".toJson)
      body = body ++ parameters
      val resp = requestEntity(POST, path, paramMap, Some(body.toJson.toString()))
      val resultInvoke = new RestResult(resp.status, getRespData(resp))
      expectedExitCode shouldBe resultInvoke.statusCode.intValue
      if (resultInvoke.statusCode != OK) {
        // Remove the trigger, because the feed failed to invoke.
        this.delete(triggerName)
      } else {
        result
      }
    } getOrElse {
      validateStatusCode(expectedExitCode, result.statusCode.intValue)
      result
    }
    r
  }

  /**
   * Fires trigger. Parameters mirror those available in the REST.
   *
   * @param name either a fully qualified name or a simple entity name
   * @param expectedExitCode (optional) the expected exit code for the command
   * if the code is anything but DONTCARE_EXIT, assert the code is as expected
   */
  override def fire(name: String,
                    parameters: Map[String, JsValue] = Map(),
                    parameterFile: Option[String] = None,
                    expectedExitCode: Int = OK.intValue)(implicit wp: WskProps): RestResult = {
    val path = getNamePath(noun, name)
    val params = parameterFile map { l =>
      val input = FileUtils.readFileToString(new File(l))
      input.parseJson.convertTo[Map[String, JsValue]]
    } getOrElse parameters
    val resp =
      if (params.size == 0) requestEntity(POST, path)
      else requestEntity(POST, path, body = Some(params.toJson.toString()))
    new RestResult(resp.status.intValue, getRespData(resp))
  }
}

class WskRestRule
    extends RunWskRestCmd
    with ListOrGetFromCollectionRest
    with DeleteFromCollectionRest
    with WaitFor
    with BaseRule {

  override protected val noun = "rules"

  /**
   * Creates rule. Parameters mirror those available in the REST.
   *
   * @param name either a fully qualified name or a simple entity name
   * @param trigger must be a simple name
   * @param action must be a simple name
   * @param expectedExitCode (optional) the expected exit code for the command
   * if the code is anything but DONTCARE_EXIT, assert the code is as expected
   */
  override def create(name: String,
                      trigger: String,
                      action: String,
                      annotations: Map[String, JsValue] = Map(),
                      shared: Option[Boolean] = None,
                      update: Boolean = false,
                      expectedExitCode: Int = SUCCESS_EXIT)(implicit wp: WskProps): RestResult = {
    val path = getNamePath(noun, name)
    val annos = convertMapIntoKeyValue(annotations)
    val published = shared.getOrElse(false)
    val bodyContent = JsObject(
      "trigger" -> fullEntityName(trigger).toJson,
      "action" -> fullEntityName(action).toJson,
      "publish" -> published.toJson,
      "name" -> name.toJson,
      "status" -> "".toJson,
      "annotations" -> annos)

    val resp =
      if (update) requestEntity(PUT, path, Map("overwrite" -> "true"), Some(bodyContent.toString))
      else requestEntity(PUT, path, body = Some(bodyContent.toString))
    new RestResult(resp.status, getRespData(resp))
  }

  /**
   * Enables rule.
   *
   * @param name either a fully qualified name or a simple entity name
   * @param expectedExitCode (optional) the expected exit code for the command
   * if the code is anything but DONTCARE_EXIT, assert the code is as expected
   */
  override def enable(name: String, expectedExitCode: Int = SUCCESS_EXIT)(implicit wp: WskProps): RestResult = {
    changeRuleState(name, "active")
  }

  /**
   * Disables rule.
   *
   * @param name either a fully qualified name or a simple entity name
   * @param expectedExitCode (optional) the expected exit code for the command
   * if the code is anything but DONTCARE_EXIT, assert the code is as expected
   */
  override def disable(name: String, expectedExitCode: Int = SUCCESS_EXIT)(implicit wp: WskProps): RestResult = {
    changeRuleState(name, "inactive")
  }

  /**
   * Checks state of rule.
   *
   * @param name either a fully qualified name or a simple entity name
   * @param expectedExitCode (optional) the expected exit code for the command
   * if the code is anything but DONTCARE_EXIT, assert the code is as expected
   */
  override def state(name: String, expectedExitCode: Int = OK.intValue)(implicit wp: WskProps): RestResult = {
    get(name, expectedExitCode = expectedExitCode)
  }

  def changeRuleState(ruleName: String, state: String = "active")(implicit wp: WskProps): RestResult = {
    val enName = entityName(ruleName)
    val path = getNamePath(noun, enName)
    val bodyContent = JsObject("status" -> state.toJson)
    val resp = requestEntity(POST, path, body = Some(bodyContent.toString))
    new RestResult(resp.status, getRespData(resp))
  }
}

class WskRestActivation extends RunWskRestCmd with HasActivationRest with WaitFor with BaseActivation {

  protected val noun = "activations"

  /**
   * Activation polling console.
   *
   * @param duration exits console after duration
   * @param since (optional) time travels back to activation since given duration
   */
  override def console(duration: Duration, since: Option[Duration] = None, expectedExitCode: Int = SUCCESS_EXIT)(
    implicit wp: WskProps): RestResult = {
    var sinceTime = System.currentTimeMillis()
    sinceTime = since map { s =>
      sinceTime - s.toMillis
    } getOrElse sinceTime
    waitForActivationConsole(duration, Instant.ofEpochMilli(sinceTime))
  }

  /**
   * Lists activations.
   *
   * @param filter (optional) if define, must be a simple entity name
   * @param limit (optional) the maximum number of activation to return
   * @param since (optional) only the activations since this timestamp are included
   * @param expectedExitCode (optional) the expected exit code for the command
   * if the code is anything but DONTCARE_EXIT, assert the code is as expected
   */
  def listActivation(filter: Option[String] = None,
                     limit: Option[Int] = None,
                     since: Option[Instant] = None,
                     docs: Boolean = true,
                     expectedExitCode: Int = SUCCESS_EXIT)(implicit wp: WskProps): RestResult = {
    val entityPath = Path(s"${basePath}/namespaces/${wp.namespace}/$noun")
    var paramMap = Map("skip" -> "0", "docs" -> docs.toString) ++ {
      limit map { l =>
        Map("limit" -> l.toString)
      } getOrElse Map[String, String]()
    } ++ {
      filter map { f =>
        Map("name" -> f.toString)
      } getOrElse Map[String, String]()
    } ++ {
      since map { s =>
        Map("since" -> s.toEpochMilli().toString)
      } getOrElse Map[String, String]()
    }
    val resp = requestEntity(GET, entityPath, paramMap)
    new RestResult(resp.status, getRespData(resp))
  }

  /**
   * Parses result of WskActivation.list to extract sequence of activation ids.
   *
   * @param rr run result, should be from WhiskActivation.list otherwise behavior is undefined
   * @return sequence of activations
   */
  def idsActivation(rr: RestResult): Seq[String] = {
    val list = rr.getBodyListJsObject()
    var result = Seq[String]()
    list.foreach((obj: JsObject) => result = result :+ (RestResult.getField(obj, "activationId").toString))
    result
  }

  /**
   * Gets activation logs by id.
   *
   * @param activationId the activation id
   * @param expectedExitCode (optional) the expected exit code for the command
   * if the code is anything but DONTCARE_EXIT, assert the code is as expected
   */
  def activationLogs(activationId: String, expectedExitCode: Int = OK.intValue)(implicit wp: WskProps): RestResult = {
    val path = Path(s"${basePath}/namespaces/${wp.namespace}/$noun/$activationId/logs")
    val resp = requestEntity(GET, path)
    val r = new RestResult(resp.status, getRespData(resp))
    validateStatusCode(expectedExitCode, r.statusCode.intValue)
    r
  }

  /**
   * Gets activation result by id.
   *
   * @param activationId the activation id
   * @param expectedExitCode (optional) the expected exit code for the command
   * if the code is anything but DONTCARE_EXIT, assert the code is as expected
   */
  def activationResult(activationId: String, expectedExitCode: Int = OK.intValue)(implicit wp: WskProps): RestResult = {
    val path = Path(s"${basePath}/namespaces/${wp.namespace}/$noun/$activationId/result")
    val resp = requestEntity(GET, path)
    val r = new RestResult(resp.status, getRespData(resp))
    validateStatusCode(expectedExitCode, r.statusCode.intValue)
    r
  }

  /**
   * Polls activations list for at least N activations. The activations
   * are optionally filtered for the given entity. Will return as soon as
   * N activations are found. If after retry budget is exhausted, N activations
   * are still not present, will return a partial result. Hence caller must
   * check length of the result and not assume it is >= N.
   *
   * @param N the number of activations desired
   * @param entity the name of the entity to filter from activation list
   * @param limit the maximum number of entities to list (if entity name is not unique use Some(0))
   * @param since (optional) only the activations since this timestamp are included
   * @param retries the maximum retries (total timeout is retries + 1 seconds)
   * @return activation ids found, caller must check length of sequence
   */
  override def pollFor(N: Int,
                       entity: Option[String],
                       limit: Option[Int] = Some(30),
                       since: Option[Instant] = None,
                       retries: Int = 10,
                       pollPeriod: Duration = 1.second)(implicit wp: WskProps): Seq[String] = {
    Try {
      retry({
        val result = idsActivation(listActivation(filter = entity, limit = limit, since = since, docs = false))
        if (result.length >= N) result else throw PartialResult(result)
      }, retries, waitBeforeRetry = Some(pollPeriod))
    } match {
      case Success(ids)                => ids
      case Failure(PartialResult(ids)) => ids
      case _                           => Seq()
    }
  }

  override def get(activationId: Option[String],
                   expectedExitCode: Int = OK.intValue,
                   fieldFilter: Option[String] = None,
                   last: Option[Boolean] = None)(implicit wp: WskProps): RestResult = {
    val r = activationId match {
      case Some(id) => {
        val resp = requestEntity(GET, getNamePath(noun, id))
        new RestResult(resp.status, getRespData(resp))
      }
      case None => {
        new RestResult(NotFound)
      }
    }
    validateStatusCode(expectedExitCode, r.statusCode.intValue)
    r
  }

  /**
   * Polls for an activation matching the given id. If found
   * return Right(activation) else Left(result of calling REST API).
   *
   * @return either Left(error message) or Right(activation as JsObject)
   */
  override def waitForActivation(activationId: String,
                                 initialWait: Duration = 1 second,
                                 pollPeriod: Duration = 1 second,
                                 totalWait: Duration = 30 seconds)(implicit wp: WskProps): Either[String, JsObject] = {
    val activation = waitfor(() => {
      val result = get(Some(activationId), expectedExitCode = DONTCARE_EXIT)(wp)
      if (result.statusCode == NotFound) {
        null
      } else result
    }, initialWait, pollPeriod, totalWait)
    Try {
      assert(activation.statusCode == OK)
      assert(activation.getField("activationId") != "")
      activation.respBody
    } map {
      Right(_)
    } getOrElse Left(s"Cannot find activation id from '$activation'")

  }

  def waitForActivationConsole(totalWait: Duration = 30 seconds, sinceTime: Instant)(
    implicit wp: WskProps): RestResult = {
    Thread.sleep(totalWait.toMillis)
    listActivation(since = Some(sinceTime))(wp)
  }

  override def logs(activationId: Option[String] = None,
                    expectedExitCode: Int = OK.intValue,
                    last: Option[Boolean] = None)(implicit wp: WskProps): RestResult = {
    val r = activationId match {
      case Some(id) => {
        val resp = requestEntity(GET, getNamePath(noun, s"$id/logs"))
        new RestResult(resp.status, getRespData(resp))
      }
      case None => {
        new RestResult(NotFound)
      }
    }
    validateStatusCode(expectedExitCode, r.statusCode.intValue)
    r
  }

  override def result(activationId: Option[String] = None,
                      expectedExitCode: Int = OK.intValue,
                      last: Option[Boolean] = None)(implicit wp: WskProps): RestResult = {
    val r = activationId match {
      case Some(id) => {
        val resp = requestEntity(GET, getNamePath(noun, s"$id/result"))
        new RestResult(resp.status, getRespData(resp))
      }
      case None => {
        new RestResult(NotFound)
      }
    }
    validateStatusCode(expectedExitCode, r.statusCode.intValue)
    r
  }

  /** Used in polling for activations to record partial results from retry poll. */
  private case class PartialResult(ids: Seq[String]) extends Throwable
}

class WskRestNamespace extends RunWskRestCmd with BaseNamespace {

  protected val noun = "namespaces"

  /**
   * Lists available namespaces for whisk properties.
   *
   * @param expectedExitCode (optional) the expected exit code for the command
   * if the code is anything but DONTCARE_EXIT, assert the code is as expected
   */
  override def list(expectedExitCode: Int = OK.intValue, nameSort: Option[Boolean] = None)(
    implicit wp: WskProps): RestResult = {
    val entPath = Path(s"$basePath/namespaces")
    val resp = requestEntity(GET, entPath)
    val result = if (resp == None) new RestResult(NotFound) else new RestResult(resp.status, getRespData(resp))
    validateStatusCode(expectedExitCode, result.statusCode.intValue)
    result
  }

  /**
   * Gets entities in namespace.
   *
   * @param namespace (optional) if specified must be  fully qualified namespace
   * @param expectedExitCode (optional) the expected exit code for the command
   * if the code is anything but DONTCARE_EXIT, assert the code is as expected
   */
  override def get(namespace: Option[String] = None,
                   expectedExitCode: Int = OK.intValue,
                   nameSort: Option[Boolean] = None)(implicit wp: WskProps): RestResult = {
    val (ns, _) = namespace map { this.getNamespaceEntityName(_) } getOrElse (wp.namespace, "")
    val entPath = Path(s"$basePath/namespaces/$ns/")
    val resp = requestEntity(GET, entPath)
    val r = new RestResult(resp.status, getRespData(resp))
    validateStatusCode(expectedExitCode, r.statusCode.intValue)
    r
  }

  /**
   * Looks up namespace for whisk props.
   *
   * @param wskprops instance of WskProps with an auth key to lookup
   * @return namespace as string
   */
  override def whois()(implicit wskprops: WskProps): String = {
    val ns = list().getBodyListString
    if (ns.size > 0) ns(0).toString() else ""
  }
}

class WskRestPackage
    extends RunWskRestCmd
    with ListOrGetFromCollectionRest
    with DeleteFromCollectionRest
    with BasePackage {

  override protected val noun = "packages"

  /**
   * Creates package. Parameters mirror those available in the REST.
   *
   * @param name either a fully qualified name or a simple entity name
   * @param expectedExitCode (optional) the expected exit code for the command
   * if the code is anything but DONTCARE_EXIT, assert the code is as expected
   */
  override def create(name: String,
                      parameters: Map[String, JsValue] = Map(),
                      annotations: Map[String, JsValue] = Map(),
                      parameterFile: Option[String] = None,
                      annotationFile: Option[String] = None,
                      shared: Option[Boolean] = None,
                      update: Boolean = false,
                      expectedExitCode: Int = OK.intValue)(implicit wp: WskProps): RestResult = {
    val path = getNamePath(noun, name)
    var bodyContent = JsObject("namespace" -> s"${wp.namespace}".toJson, "name" -> name.toJson)

    val (params, annos) = this.getParamsAnnos(parameters, annotations, parameterFile, annotationFile)
    if (!update) {
      val published = shared.getOrElse(false)
      bodyContent = JsObject(
        bodyContent.fields + ("publish" -> published.toJson,
        "parameters" -> params, "annotations" -> annos))
    } else {
      if (shared != None)
        bodyContent = shared map { s =>
          JsObject(bodyContent.fields + ("publish" -> s.toJson))
        } getOrElse bodyContent

      val inputParams = convertMapIntoKeyValue(parameters)
      if (inputParams.elements.size > 0) {
        bodyContent = JsObject(bodyContent.fields + ("parameters" -> params))
      }
      val inputAnnos = convertMapIntoKeyValue(annotations)
      if (inputAnnos.elements.size > 0) {
        bodyContent = JsObject(bodyContent.fields + ("annotations" -> annos))
      }
    }

    val resp =
      if (update) requestEntity(PUT, path, Map("overwrite" -> "true"), Some(bodyContent.toString))
      else requestEntity(PUT, path, body = Some(bodyContent.toString))
    val r = new RestResult(resp.status, getRespData(resp))
    validateStatusCode(expectedExitCode, r.statusCode.intValue)
    r
  }

  /**
   * Binds package. Parameters mirror those available in the REST.
   *
   * @param name either a fully qualified name or a simple entity name
   * @param expectedExitCode (optional) the expected exit code for the command
   * if the code is anything but DONTCARE_EXIT, assert the code is as expected
   */
  override def bind(provider: String,
                    name: String,
                    parameters: Map[String, JsValue] = Map(),
                    annotations: Map[String, JsValue] = Map(),
                    expectedExitCode: Int = OK.intValue)(implicit wp: WskProps): RestResult = {
    val params = convertMapIntoKeyValue(parameters)
    val annos = convertMapIntoKeyValue(annotations)

    val (ns, packageName) = this.getNamespaceEntityName(provider)
    val path = getNamePath(noun, name)
    val binding = JsObject("namespace" -> ns.toJson, "name" -> packageName.toJson)
    val bodyContent = JsObject("binding" -> binding.toJson, "parameters" -> params, "annotations" -> annos)
    val resp = requestEntity(PUT, path, Map("overwrite" -> "false"), Some(bodyContent.toString))
    val r = new RestResult(resp.status, getRespData(resp))
    validateStatusCode(expectedExitCode, r.statusCode.intValue)
    r
  }

}

class WskRestApi extends RunWskRestCmd with BaseApi {
  protected val noun = "apis"

  /**
   * Creates and API endpoint. Parameters mirror those available in the REST.
   *
   * @param expectedExitCode (optional) the expected exit code for the command
   * if the code is anything but DONTCARE_EXIT, assert the code is as expected
   */
  override def create(basepath: Option[String] = None,
                      relpath: Option[String] = None,
                      operation: Option[String] = None,
                      action: Option[String] = None,
                      apiname: Option[String] = None,
                      swagger: Option[String] = None,
                      responsetype: Option[String] = None,
                      expectedExitCode: Int = SUCCESS_EXIT,
                      cliCfgFile: Option[String] = None)(implicit wp: WskProps): RestResult = {
    val r = action match {
      case Some(action) => {
        val (ns, actionName) = this.getNamespaceEntityName(action)
        val actionUrl = s"${WhiskProperties.getApiHostForAction}/$basePath/web/$ns/default/$actionName.http"
        val actionAuthKey = this.getAuthKey(wp)
        val testaction = Some(
          ApiAction(name = actionName, namespace = ns, backendUrl = actionUrl, authkey = actionAuthKey))

        val parms = Map[String, JsValue]() ++ { Map("namespace" -> ns.toJson) } ++ {
          basepath map { b =>
            Map("gatewayBasePath" -> b.toJson)
          } getOrElse Map[String, JsValue]()
        } ++ {
          relpath map { r =>
            Map("gatewayPath" -> r.toJson)
          } getOrElse Map[String, JsValue]()
        } ++ {
          operation map { o =>
            Map("gatewayMethod" -> o.toJson)
          } getOrElse Map[String, JsValue]()
        } ++ {
          apiname map { an =>
            Map("apiName" -> an.toJson)
          } getOrElse Map[String, JsValue]()
        } ++ {
          testaction map { a =>
            Map("action" -> a.toJson)
          } getOrElse Map[String, JsValue]()
        } ++ {
          swagger map { s =>
            val swaggerFile = FileUtils.readFileToString(new File(s))
            Map("swagger" -> swaggerFile.toJson)
          } getOrElse Map[String, JsValue]()
        }

        val parm = Map[String, JsValue]("apidoc" -> JsObject(parms)) ++ { Map("__ow_user" -> ns.toJson) } ++ {
          responsetype map { r =>
            Map("responsetype" -> r.toJson)
          } getOrElse Map[String, JsValue]()
        } ++ {
          Map("accesstoken" -> wp.authKey.toJson)
        } ++ {
          Map("spaceguid" -> wp.authKey.split(":")(0).toJson)
        }

        invokeAction(
          name = "apimgmt/createApi",
          parameters = parm,
          blocking = true,
          result = true,
          expectedExitCode = expectedExitCode)(wp)
      }
      case None => {
        new RestResult(NotFound)
      }
    }
    r
  }

  /**
   * Retrieve a list of API endpoints. Parameters mirror those available in the REST.
   *
   * @param expectedExitCode (optional) the expected exit code for the command
   * if the code is anything but DONTCARE_EXIT, assert the code is as expected
   */
  override def list(basepathOrApiName: Option[String] = None,
                    relpath: Option[String] = None,
                    operation: Option[String] = None,
                    limit: Option[Int] = None,
                    since: Option[Instant] = None,
                    full: Option[Boolean] = None,
                    nameSort: Option[Boolean] = None,
                    expectedExitCode: Int = SUCCESS_EXIT,
                    cliCfgFile: Option[String] = None)(implicit wp: WskProps): RestResult = {

    val parms = Map[String, JsValue]() ++
      Map("__ow_user" -> wp.namespace.toJson) ++ {
      basepathOrApiName map { b =>
        Map("basepath" -> b.toJson)
      } getOrElse Map[String, JsValue]()
    } ++ {
      relpath map { r =>
        Map("relpath" -> r.toJson)
      } getOrElse Map[String, JsValue]()
    } ++ {
      operation map { o =>
        Map("operation" -> o.toJson)
      } getOrElse Map[String, JsValue]()
    } ++ {
      Map("accesstoken" -> wp.authKey.toJson)
    } ++ {
      Map("spaceguid" -> wp.authKey.split(":")(0).toJson)
    }
    val rr = invokeAction(
      name = "apimgmt/getApi",
      parameters = parms,
      blocking = true,
      result = true,
      expectedExitCode = OK.intValue)(wp)
    rr
  }
//dasda
  /**
   * Retieves an API's configuration. Parameters mirror those available in the REST.
   * Runs a command wsk [params] where the arguments come in as a sequence.
   *
   * @param expectedExitCode (optional) the expected exit code for the command
   * if the code is anything but DONTCARE_EXIT, assert the code is as expected
   */
  override def get(basepathOrApiName: Option[String] = None,
                   full: Option[Boolean] = None,
                   expectedExitCode: Int = SUCCESS_EXIT,
                   cliCfgFile: Option[String] = None,
                   format: Option[String] = None)(implicit wp: WskProps): RestResult = {
    val parms = Map[String, JsValue]() ++
      Map("__ow_user" -> wp.namespace.toJson) ++ {
      basepathOrApiName map { b =>
        Map("basepath" -> b.toJson)
      } getOrElse Map[String, JsValue]()
    } ++ {
      Map("accesstoken" -> wp.authKey.toJson)
    } ++ {
      Map("spaceguid" -> wp.authKey.split(":")(0).toJson)
    }

    val result = invokeAction(
      name = "apimgmt/getApi",
      parameters = parms,
      blocking = true,
      result = true,
      expectedExitCode = OK.intValue)(wp)
    result
  }

  /**
   * Delete an entire API or a subset of API endpoints. Parameters mirror those available in the REST.
   *
   * @param expectedExitCode (optional) the expected exit code for the command
   * if the code is anything but DONTCARE_EXIT, assert the code is as expected
   */
  override def delete(basepathOrApiName: String,
                      relpath: Option[String] = None,
                      operation: Option[String] = None,
                      expectedExitCode: Int = SUCCESS_EXIT,
                      cliCfgFile: Option[String] = None)(implicit wp: WskProps): RestResult = {
    val parms = Map[String, JsValue]() ++ { Map("__ow_user" -> wp.namespace.toJson) } ++ {
      Map("basepath" -> basepathOrApiName.toJson)
    } ++ {
      relpath map { r =>
        Map("relpath" -> r.toJson)
      } getOrElse Map[String, JsValue]()
    } ++ {
      operation map { o =>
        Map("operation" -> o.toJson)
      } getOrElse Map[String, JsValue]()
    } ++ {
      Map("accesstoken" -> wp.authKey.toJson)
    } ++ {
      Map("spaceguid" -> wp.authKey.split(":")(0).toJson)
    }

    val rr = invokeAction(
      name = "apimgmt/deleteApi",
      parameters = parms,
      blocking = true,
      result = true,
      expectedExitCode = expectedExitCode)(wp)
    return rr
  }

  def getApi(basepathOrApiName: String, params: Map[String, String] = Map(), expectedExitCode: Int = OK.intValue)(
    implicit wp: WskProps): RestResult = {
    val whiskUrl = Uri(WhiskProperties.getApiHostForAction)
    val path = Path(s"/api/${wp.authKey.split(":")(0)}$basepathOrApiName/path")
    val resp = requestEntity(GET, path, params, whiskUrl = whiskUrl)
    val result = new RestResult(resp.status, getRespData(resp))
    result
  }
}

class RunWskRestCmd() extends FlatSpec with RunWskCmd with Matchers with ScalaFutures with WskActorSystem {

  implicit val config = PatienceConfig(100 seconds, 15 milliseconds)
  implicit val materializer = ActorMaterializer()
  val whiskRestUrl = Uri(WhiskProperties.getApiHostForAction)
  val basePath = Path("/api/v1")

  val sslConfig = AkkaSSLConfig().mapSettings { s =>
    s.withLoose(s.loose.withAcceptAnyCertificate(true).withDisableHostnameVerification(true))
  }
  val connectionContext = new HttpsConnectionContext(SSL.nonValidatingContext, Some(sslConfig))

  def isStatusCodeExpected(expectedExitCode: Int, statusCode: Int): Boolean = {
    return statusCode == expectedExitCode
  }

  def validateStatusCode(expectedExitCode: Int, statusCode: Int) = {
    if ((expectedExitCode != DONTCARE_EXIT) && (expectedExitCode != ANY_ERROR_EXIT))
      if (!isStatusCodeExpected(expectedExitCode, statusCode)) {
        statusCode shouldBe expectedExitCode
      }
  }

  def getNamePath(noun: String, name: String)(implicit wp: WskProps): Path = {
    return Path(s"$basePath/namespaces/${wp.namespace}/$noun/$name")
  }

  def getExt(filePath: String)(implicit wp: WskProps) = {
    val sep = "."
    if (filePath.contains(sep)) filePath.substring(filePath.lastIndexOf(sep), filePath.length())
    else ""
  }

  def request(method: HttpMethod,
              uri: Uri,
              body: Option[String] = None,
              creds: BasicHttpCredentials): Future[HttpResponse] = {
    val entity = body map { b =>
      HttpEntity(ContentTypes.`application/json`, b)
    } getOrElse HttpEntity(ContentTypes.`application/json`, "")
    val request = HttpRequest(method, uri, List(Authorization(creds)), entity = entity)

    Http().singleRequest(request, connectionContext)
  }

  def requestEntity(method: HttpMethod,
                    path: Path,
                    params: Map[String, String] = Map(),
                    body: Option[String] = None,
                    whiskUrl: Uri = whiskRestUrl)(implicit wp: WskProps): HttpResponse = {
    val creds = getBasicHttpCredentials(wp)
    request(method, whiskUrl.withPath(path).withQuery(Uri.Query(params)), body, creds = creds).futureValue
  }

  private def getBasicHttpCredentials(wp: WskProps): BasicHttpCredentials = {
    if (wp.authKey.contains(":")) {
      val authKey = wp.authKey.split(":")
      new BasicHttpCredentials(authKey(0), authKey(1))
    } else {
      new BasicHttpCredentials(wp.authKey, wp.authKey)
    }
  }

  def getAuthKey(wp: WskProps): String = {
    val authKey = wp.authKey.split(":")
    s"${authKey(0)}:${authKey(1)}"
  }

  def getParamsAnnos(parameters: Map[String, JsValue] = Map(),
                     annotations: Map[String, JsValue] = Map(),
                     parameterFile: Option[String] = None,
                     annotationFile: Option[String] = None,
                     feed: Option[String] = None,
                     web: Option[String] = None): (JsArray, JsArray) = {
    val params = parameterFile map { pf =>
      convertStringIntoKeyValue(pf)
    } getOrElse convertMapIntoKeyValue(parameters)
    val annos = annotationFile map { af =>
      convertStringIntoKeyValue(af, feed, web)
    } getOrElse convertMapIntoKeyValue(annotations, feed, web)
    (params, annos)
  }

  def convertStringIntoKeyValue(file: String, feed: Option[String] = None, web: Option[String] = None): JsArray = {
    var paramsList = Vector[JsObject]()
    val input = FileUtils.readFileToString(new File(file))
    val in = input.parseJson.convertTo[Map[String, JsValue]]
    convertMapIntoKeyValue(in, feed, web)
  }

  def convertMapIntoKeyValue(params: Map[String, JsValue],
                             feed: Option[String] = None,
                             web: Option[String] = None): JsArray = {
    var paramsList = Vector[JsObject]()
    params foreach { case (key, value) => paramsList :+= JsObject("key" -> key.toJson, "value" -> value.toJson) }
    paramsList = feed map { f =>
      paramsList :+ JsObject("key" -> "feed".toJson, "value" -> f.toJson)
    } getOrElse paramsList
    paramsList = web map { w =>
      paramsList :+ JsObject("key" -> "web-export".toJson, "value" -> w.toJson)
    } getOrElse paramsList
    JsArray(paramsList)
  }

  def entityName(name: String)(implicit wp: WskProps) = {
    val sep = "/"
    if (name.startsWith(sep)) name.substring(name.indexOf(sep, name.indexOf(sep) + 1) + 1, name.length())
    else name
  }

  def fullEntityName(name: String)(implicit wp: WskProps) = {
    val sep = "/"
    if (name.startsWith(sep)) name
    else s"/${wp.namespace}/$name"
  }

  def convertIntoComponents(comps: String)(implicit wp: WskProps): JsArray = {
    var paramsList = Vector[JsString]()
    comps.split(",") foreach {
      case (value) =>
        val fullName = fullEntityName(value)
        paramsList :+= JsString(fullName)
    }
    JsArray(paramsList)
  }

  def getRespData(resp: HttpResponse): String = {
    val timeout = 5.seconds
    Try {
      resp.entity.toStrict(timeout).map { _.data }.map(_.utf8String).futureValue
    } getOrElse {
      ""
    }
  }

  def getNamespaceEntityName(name: String)(implicit wp: WskProps): (String, String) = {
    name.split("/") match {
      // Example: /namespace/package_name/entity_name
      case Array(empty, namespace, packageName, entityName) if empty.isEmpty => (namespace, s"$packageName/$entityName")
      // Example: /namespace/entity_name
      case Array(empty, namespace, entityName) if empty.isEmpty => (namespace, entityName)
      // Example: namespace/package_name/entity_name
      case Array(namespace, packageName, entityName) => (namespace, s"$packageName/$entityName")
      // Example: package_name/entity_name
      case Array(packageName, entityName) => (wp.namespace, s"$packageName/$entityName")
      // Example: entity_name
      case Array(entityName) => (wp.namespace, entityName)
      case _                 => (wp.namespace, name)
    }
  }

  def invokeAction(name: String,
                   parameters: Map[String, JsValue] = Map(),
                   parameterFile: Option[String] = None,
                   blocking: Boolean = false,
                   result: Boolean = false,
                   expectedExitCode: Int = Accepted.intValue)(implicit wp: WskProps): RestResult = {
    val (ns, actName) = this.getNamespaceEntityName(name)
    val path = Path(s"$basePath/namespaces/$ns/actions/$actName")
    var paramMap = Map("blocking" -> blocking.toString, "result" -> result.toString)
    val input = parameterFile map { pf =>
      Some(FileUtils.readFileToString(new File(pf)))
    } getOrElse Some(parameters.toJson.toString())
    val resp = requestEntity(POST, path, paramMap, input)
    val r = new RestResult(resp.status.intValue, getRespData(resp))
    // If the statusCode does not not equal to expectedExitCode, it is acceptable that the statusCode
    // equals to 200 for the case that either blocking or result is set to true.
    if (!isStatusCodeExpected(expectedExitCode, r.statusCode.intValue)) {
      if (blocking || result) {
        validateStatusCode(OK.intValue, r.statusCode.intValue)
      } else {
        r.statusCode.intValue shouldBe expectedExitCode
      }
    }
    r
  }
}

object WskRestAdmin {
  private val binDir = WhiskProperties.getFileRelativeToWhiskHome("bin")
  private val binaryName = "wskadmin"

  def exists = {
    val dir = binDir
    val exec = new File(dir, binaryName)
    assert(dir.exists, s"did not find $dir")
    assert(exec.exists, s"did not find $exec")
  }

  def baseCommand = {
    Buffer(WhiskProperties.python, new File(binDir, binaryName).toString)
  }

  def listKeys(namespace: String, pick: Integer = 1): List[(String, String)] = {
    val wskadmin = new RunWskRestAdminCmd {}
    wskadmin
      .cli(Seq("user", "list", namespace, "--pick", pick.toString))
      .stdout
      .split("\n")
      .map("""\s+""".r.split(_))
      .map(parts => (parts(0), parts(1)))
      .toList
  }

}

trait RunWskRestAdminCmd extends RunWskCmd {
  override def baseCommand = WskRestAdmin.baseCommand

  def adminCommand(params: Seq[String],
                   expectedExitCode: Int = SUCCESS_EXIT,
                   verbose: Boolean = false,
                   env: Map[String, String] = Map("WSK_CONFIG_FILE" -> ""),
                   workingDir: File = new File("."),
                   stdinFile: Option[File] = None,
                   showCmd: Boolean = false): RunResult = {
    val args = baseCommand
    if (verbose) args += "--verbose"
    if (showCmd) println(args.mkString(" ") + " " + params.mkString(" "))
    val rr = TestUtils.runCmd(
      DONTCARE_EXIT,
      workingDir,
      TestUtils.logger,
      sys.env ++ env,
      stdinFile.getOrElse(null),
      args ++ params: _*)

    withClue(reportFailure(args ++ params, expectedExitCode, rr)) {
      if (expectedExitCode != TestUtils.DONTCARE_EXIT) {
        val ok = (rr.exitCode == expectedExitCode) || (expectedExitCode == TestUtils.ANY_ERROR_EXIT && rr.exitCode != 0)
        if (!ok) {
          rr.exitCode shouldBe expectedExitCode
        }
      }
    }
    rr
  }
}

object RestResult {

  val codeConversion = 256

  def getField(obj: JsObject, key: String): String = {
    obj.fields.get(key).map(_.convertTo[String]).getOrElse("")
  }

  def getFieldJsObject(obj: JsObject, key: String): JsObject = {
    obj.fields.get(key).map(_.asJsObject).getOrElse(JsObject())
  }

  def getFieldJsValue(obj: JsObject, key: String): JsValue = {
    obj.fields.get(key).getOrElse(JsObject())
  }

  def getFieldListJsObject(obj: JsObject, key: String): Vector[JsObject] = {
    obj.fields.get(key).map(_.convertTo[Vector[JsObject]]).getOrElse(Vector(JsObject()))
  }

  def convertStausCodeToExitCode(statusCode: StatusCode): Int = {
    if (statusCode == OK)
      return 0
    if (statusCode.intValue < BadRequest.intValue) statusCode.intValue else statusCode.intValue - codeConversion
  }

  def convertHttpResponseToStderr(respData: String): String = {
    Try {
      getField(respData.parseJson.asJsObject, "error")
    } getOrElse {
      ""
    }
  }
}

class RestResult(var statusCode: StatusCode, var respData: String = "")
    extends RunResult(
      RestResult.convertStausCodeToExitCode(statusCode),
      respData,
      RestResult.convertHttpResponseToStderr(respData)) {

  def respBody: JsObject = respData.parseJson.asJsObject

  def getField(key: String): String = {
    RestResult.getField(respBody, key)
  }

  def getFieldJsObject(key: String): JsObject = {
    RestResult.getFieldJsObject(respBody, key)
  }

  def getFieldJsValue(key: String): JsValue = {
    RestResult.getFieldJsValue(respBody, key)
  }

  def getFieldListJsObject(key: String): Vector[JsObject] = {
    RestResult.getFieldListJsObject(respBody, key)
  }

  def getBodyListJsObject(): Vector[JsObject] = {
    respData.parseJson.convertTo[Vector[JsObject]]
  }

  def getBodyListString(): Vector[String] = {
    respData.parseJson.convertTo[Vector[String]]
  }
}

case class ApiAction(name: String,
                     namespace: String,
                     backendMethod: String = "POST",
                     backendUrl: String,
                     authkey: String) {
  def toJson(): JsObject = {
    return JsObject(
      "name" -> name.toJson,
      "namespace" -> namespace.toJson,
      "backendMethod" -> backendMethod.toJson,
      "backendUrl" -> backendUrl.toJson,
      "authkey" -> authkey.toJson)
  }
}
