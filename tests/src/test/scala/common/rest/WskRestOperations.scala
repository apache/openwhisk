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

import java.io.{File, FileInputStream}
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Base64

import akka.actor.ActorSystem
import akka.http.scaladsl.{Http, HttpsConnectionContext}
import akka.http.scaladsl.model.HttpMethods.{DELETE, GET, POST, PUT}
import akka.http.scaladsl.model.StatusCodes.{Accepted, NotFound, OK}
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.Uri.{Path, Query}
import akka.http.scaladsl.model.headers.{Authorization, BasicHttpCredentials, OAuth2BearerToken}
import akka.util.ByteString
import common.TestUtils.{ANY_ERROR_EXIT, DONTCARE_EXIT, RunResult, SUCCESS_EXIT}
import common.rest.SSL.httpsConfig
import common.{
  DeleteFromCollectionOperations,
  HasActivation,
  ListOrGetFromCollectionOperations,
  WaitFor,
  WhiskProperties,
  WskProps,
  _
}
import javax.net.ssl._
import org.apache.commons.io.{FileUtils, FilenameUtils}
import org.apache.openwhisk.common.Https.HttpsConfig
import org.apache.openwhisk.common.{AkkaLogging, Https, TransactionId}
import org.apache.openwhisk.core.entity.ByteSize
import org.apache.openwhisk.utils.retry
import org.scalatest.Matchers
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.Span.convertDurationToSpan
import pureconfig._
import pureconfig.generic.auto._
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.collection.immutable.Seq
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

class AcceptAllHostNameVerifier extends HostnameVerifier {
  override def verify(s: String, sslSession: SSLSession): Boolean = true
}

object SSL {

  lazy val httpsConfig: HttpsConfig = loadConfigOrThrow[HttpsConfig]("whisk.controller.https")

  def keyManagers(clientAuth: Boolean): Array[KeyManager] = {
    if (clientAuth)
      keyManagersForClientAuth
    else
      Array.empty
  }

  def keyManagersForClientAuth: Array[KeyManager] = {
    val keyFactoryType = "SunX509"
    val keystorePassword = httpsConfig.keystorePassword.toCharArray
    val ks: KeyStore = KeyStore.getInstance(httpsConfig.keystoreFlavor)
    ks.load(new FileInputStream(httpsConfig.keystorePath), httpsConfig.keystorePassword.toCharArray)
    val keyManagerFactory: KeyManagerFactory = KeyManagerFactory.getInstance(keyFactoryType)
    keyManagerFactory.init(ks, keystorePassword)
    keyManagerFactory.getKeyManagers
  }

  def nonValidatingContext(clientAuth: Boolean = false): SSLContext = {
    class IgnoreX509TrustManager extends X509TrustManager {
      def checkClientTrusted(chain: Array[X509Certificate], authType: String): Unit = ()
      def checkServerTrusted(chain: Array[X509Certificate], authType: String): Unit = ()
      def getAcceptedIssuers: Array[X509Certificate] = Array.empty
    }

    val context = SSLContext.getInstance("TLS")

    context.init(keyManagers(clientAuth), Array(new IgnoreX509TrustManager), null)
    context
  }

  def httpsConnectionContext(implicit system: ActorSystem): HttpsConnectionContext = {
    Https.connectionContextClient(httpsConfig, true)
  }
}

object HttpConnection {

  /**
   * Returns either the https context that is tailored for self-signed certificates on the controller, or
   * a default connection context used in Http.SingleRequest
   *
   * @param protocol protocol used to communicate with controller API
   * @param system actor system
   * @return https connection context
   */
  def getContext(protocol: String)(implicit system: ActorSystem): HttpsConnectionContext = {
    if (protocol == "https") {
      SSL.httpsConnectionContext
    } else {
      // supports http
      Http().defaultClientHttpsContext
    }
  }
}

class WskRestOperations(implicit actorSytem: ActorSystem) extends WskOperations {
  override implicit val action: RestActionOperations = new RestActionOperations
  override implicit val trigger: RestTriggerOperations = new RestTriggerOperations
  override implicit val rule: RestRuleOperations = new RestRuleOperations
  override implicit val activation: RestActivationOperations = new RestActivationOperations
  override implicit val pkg: RestPackageOperations = new RestPackageOperations
  override implicit val namespace: RestNamespaceOperations = new RestNamespaceOperations
  override implicit val api: RestGatewayOperations = new RestGatewayOperations
}

trait RestListOrGetFromCollectionOperations extends ListOrGetFromCollectionOperations with RunRestCmd {
  import FullyQualifiedNames.resolve

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
      val (nspace, name) = getNamespaceEntityName(resolve(namespace))
      if (name.isEmpty) Path(s"$basePath/namespaces/$nspace/$noun")
      else Path(s"$basePath/namespaces/$nspace/$noun/$name/")
    } getOrElse Path(s"$basePath/namespaces/${wp.namespace}/$noun")

    val paramMap: Map[String, String] = Map("skip" -> "0", "docs" -> true.toString) ++
      limit.map(l => Map("limit" -> l.toString)).getOrElse(Map.empty)

    val resp = requestEntity(GET, entPath, paramMap)
    val r = new RestResult(resp.status, getTransactionId(resp), getRespData(resp))
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
    val rr = new RestResult(resp.status, getTransactionId(resp), getRespData(resp))
    validateStatusCode(expectedExitCode, rr.statusCode.intValue)
    rr
  }
}

trait RestDeleteFromCollectionOperations extends DeleteFromCollectionOperations with RunRestCmd {

  /**
   * Deletes entity from collection.
   *
   * @param name either a fully qualified name or a simple entity name
   * @param expectedExitCode (optional) the expected exit code for the command
   * if the code is anything but DONTCARE_EXIT, assert the code is as expected
   */
  override def delete(name: String, expectedExitCode: Int = OK.intValue)(implicit wp: WskProps): RestResult = {
    val (ns, entityName) = getNamespaceEntityName(name)
    val path = Path(s"$basePath/namespaces/$ns/$noun/$entityName")
    val resp = requestEntity(DELETE, path)(wp)
    val rr = new RestResult(resp.status, getTransactionId(resp), getRespData(resp))
    validateStatusCode(expectedExitCode, rr.statusCode.intValue)
    rr
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

trait RestActivation extends HasActivation {

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

class RestActionOperations(implicit val actorSystem: ActorSystem)
    extends RestListOrGetFromCollectionOperations
    with RestDeleteFromCollectionOperations
    with RestActivation
    with ActionOperations {

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
    expectedExitCode: Int = OK.intValue)(implicit wp: WskProps): RestResult = {

    val (namespace, actionName) = getNamespaceEntityName(name)
    val (paramsInput, annosInput) = getParamsAnnos(parameters, annotations, parameterFile, annotationFile, web = web)
    val (params: Array[JsValue], annos: Array[JsValue], exec: Map[String, JsValue]) = kind match {
      case Some(k) =>
        k match {
          case "copy" =>
            require(artifact.isDefined, "copy requires an artifact name")
            val actionName = entityName(artifact.get)
            val actionPath = Path(s"$basePath/namespaces/$namespace/$noun/$actionName")
            val resp = requestEntity(GET, actionPath)
            val result = new RestResult(resp.status, getTransactionId(resp), getRespData(resp))
            val params = result.getFieldListJsObject("parameters").toArray[JsValue]
            val annos = result.getFieldListJsObject("annotations").toArray[JsValue]
            val exec = result.getFieldJsObject("exec").fields
            (paramsInput ++ params, annosInput ++ annos, exec)

          case "sequence" =>
            require(artifact.isDefined, "sequence requires a component list")
            val comps = convertIntoComponents(artifact.get)
            val exec =
              if (comps.nonEmpty) Map("components" -> comps.toJson, "kind" -> k.toJson)
              else Map("kind" -> k.toJson)
            (paramsInput, annosInput, exec)

          case _ =>
            val code = readCodeFromFile(artifact).map(c => Map("code" -> c.toJson)).getOrElse(Map.empty)
            val exec: Map[String, JsValue] = if (k == "native" || k == "docker") {
              require(k == "native" && docker.isEmpty || k == "docker" && docker.isDefined)
              Map("kind" -> "blackbox".toJson, "image" -> docker.getOrElse("openwhisk/dockerskeleton").toJson) ++ code
            } else {
              require(artifact.isDefined, "file name required as an artifact")
              Map("kind" -> k.toJson) ++ code
            }

            (paramsInput, annosInput, exec)
        }

      case None =>
        docker
          .map(_ => "blackbox")
          .orElse {
            artifact.map { file =>
              getExt(file) match {
                case "js"    => "nodejs:default"
                case "py"    => "python:default"
                case "swift" => "swift:default"
                case "jar"   => "java:default"
                case _ =>
                  throw new IllegalStateException(s"Extension for $file not recognized and kind cannot be inferred.")
              }
            }
          }
          .map { k =>
            val code = readCodeFromFile(artifact).map(c => Map("code" -> c.toJson)).getOrElse(Map.empty)
            val image = docker.map(i => Map("image" -> i.toJson)).getOrElse(Map.empty)
            (paramsInput, annosInput, Map("kind" -> k.toJson) ++ code ++ image)
          }
          .getOrElse {
            if (!update && artifact.isDefined)
              throw new IllegalStateException(
                s"Extension for ${artifact.get} not recognized and kind cannot be inferred.")
            else (paramsInput, annosInput, Map.empty)
          }
    }

    val limits: Map[String, JsValue] = {
      timeout.map(t => Map("timeout" -> t.toMillis.toJson)).getOrElse(Map.empty) ++
        logsize.map(log => Map("logs" -> log.toMB.toJson)).getOrElse(Map.empty) ++
        memory.map(m => Map("memory" -> m.toMB.toJson)).getOrElse(Map.empty) ++
        concurrency.map(c => Map("concurrency" -> c.toJson)).getOrElse(Map.empty)
    }

    val body: Map[String, JsValue] = if (!update) {
      require(exec.nonEmpty, "exec cannot be empty on create")
      Map(
        "exec" -> main.map(m => exec ++ Map("main" -> m.toJson)).getOrElse(exec).toJson,
        "parameters" -> params.toJson,
        "annotations" -> annos.toJson) ++ Map("limits" -> limits.toJson)
    } else {
      var content: Map[String, JsValue] = Map.empty
      if (exec.nonEmpty)
        content = Map("exec" -> main.map(m => exec ++ Map("main" -> m.toJson)).getOrElse(exec).toJson)
      if (params.nonEmpty)
        content = content + ("parameters" -> params.toJson)
      if (annos.nonEmpty)
        content = content + ("annotations" -> annos.toJson)
      if (limits.nonEmpty)
        content = content + ("limits" -> limits.toJson)
      if (delAnnotations.nonEmpty)
        content = content + ("delAnnotations" -> delAnnotations.toJson)
      content
    }

    val path = Path(s"$basePath/namespaces/$namespace/$noun/$actionName")
    val resp =
      if (update) requestEntity(PUT, path, Map("overwrite" -> "true"), Some(JsObject(body).toString))
      else requestEntity(PUT, path, body = Some(JsObject(body).toString))
    val rr = new RestResult(resp.status, getTransactionId(resp), getRespData(resp))
    validateStatusCode(expectedExitCode, rr.statusCode.intValue)
    rr
  }

  override def invoke(name: String,
                      parameters: Map[String, JsValue] = Map.empty,
                      parameterFile: Option[String] = None,
                      blocking: Boolean = false,
                      result: Boolean = false,
                      expectedExitCode: Int = Accepted.intValue)(implicit wp: WskProps): RestResult = {
    super.invokeAction(name, parameters, parameterFile, blocking, result, expectedExitCode = expectedExitCode)
  }

  private def readCodeFromFile(artifact: Option[String]): Option[String] = {
    artifact.map { file =>
      val ext = getExt(file)
      val isBinary = ext == "zip" || ext == "jar" || ext == "bin"
      if (!isBinary) {
        FileUtils.readFileToString(new File(file), StandardCharsets.UTF_8)
      } else {
        val zip = FileUtils.readFileToByteArray(new File(file))
        Base64.getEncoder.encodeToString(zip)
      }
    }
  }
}

class RestTriggerOperations(implicit val actorSystem: ActorSystem)
    extends RestListOrGetFromCollectionOperations
    with RestDeleteFromCollectionOperations
    with RestActivation
    with TriggerOperations {

  override protected val noun = "triggers"

  /**
   * Creates trigger. Parameters mirror those available in the REST.
   *
   * @param name either a fully qualified name or a simple entity name
   * @param expectedExitCode (optional) the expected exit code for the command
   * if the code is anything but DONTCARE_EXIT, assert the code is as expected
   */
  override def create(name: String,
                      parameters: Map[String, JsValue] = Map.empty,
                      annotations: Map[String, JsValue] = Map.empty,
                      parameterFile: Option[String] = None,
                      annotationFile: Option[String] = None,
                      feed: Option[String] = None,
                      shared: Option[Boolean] = None,
                      update: Boolean = false,
                      expectedExitCode: Int = OK.intValue)(implicit wp: WskProps): RestResult = {

    val (ns, triggerName) = getNamespaceEntityName(name)
    val path = Path(s"$basePath/namespaces/$ns/$noun/$triggerName")
    val (params, annos) = getParamsAnnos(parameters, annotations, parameterFile, annotationFile, feed)
    var bodyContent: Map[String, JsValue] = Map.empty

    if (!update) {
      bodyContent =
        Map("publish" -> shared.getOrElse(false).toJson, "parameters" -> params.toJson, "annotations" -> annos.toJson)
    } else {
      shared.foreach { p =>
        bodyContent = Map("publish" -> p.toJson)
      }

      val inputParams = convertMapIntoKeyValue(parameters)
      if (inputParams.nonEmpty) {
        bodyContent = bodyContent + ("parameters" -> params.toJson)
      }
      val inputAnnos = convertMapIntoKeyValue(annotations)
      if (inputAnnos.nonEmpty) {
        bodyContent = bodyContent + ("annotations" -> annos.toJson)
      }
    }

    val resp =
      if (update) requestEntity(PUT, path, Map("overwrite" -> "true"), Some(JsObject(bodyContent).toString))
      else requestEntity(PUT, path, body = Some(JsObject(bodyContent).toString))
    val result = new RestResult(resp.status, getTransactionId(resp), getRespData(resp))
    if (result.statusCode != OK) {
      validateStatusCode(expectedExitCode, result.statusCode.intValue)
    }
    val rr = feed map { f =>
      // Invoke the feed
      val (nsFeed, feedName) = getNamespaceEntityName(f)
      val path = Path(s"$basePath/namespaces/$nsFeed/actions/$feedName")
      val paramMap = Map("blocking" -> "true", "result" -> "false")
      var body: Map[String, JsValue] = Map(
        "lifecycleEvent" -> "CREATE".toJson,
        "triggerName" -> s"/$ns/$triggerName".toJson,
        "authKey" -> s"${wp.authKey}".toJson)
      body = body ++ parameters
      val resp = requestEntity(POST, path, paramMap, Some(body.toJson.toString))
      val resultInvoke = new RestResult(resp.status, getTransactionId(resp), getRespData(resp))
      if ((expectedExitCode != DONTCARE_EXIT) && (expectedExitCode != ANY_ERROR_EXIT))
        expectedExitCode shouldBe resultInvoke.statusCode.intValue
      if (resultInvoke.statusCode != OK) {
        // Remove the trigger, because the feed failed to invoke.
        delete(triggerName)
        new RestResult(NotFound, getTransactionId(resp))
      } else {
        result
      }
    } getOrElse {
      validateStatusCode(expectedExitCode, result.statusCode.intValue)
      result
    }
    rr
  }

  /**
   * Fires trigger. Parameters mirror those available in the REST.
   *
   * @param name either a fully qualified name or a simple entity name
   * @param expectedExitCode (optional) the expected exit code for the command
   * if the code is anything but DONTCARE_EXIT, assert the code is as expected
   */
  override def fire(name: String,
                    parameters: Map[String, JsValue] = Map.empty,
                    parameterFile: Option[String] = None,
                    expectedExitCode: Int = Accepted.intValue)(implicit wp: WskProps): RestResult = {
    val path = getNamePath(wp.namespace, noun, name)
    val params = parameterFile map { l =>
      val input = FileUtils.readFileToString(new File(l), StandardCharsets.UTF_8)
      input.parseJson.convertTo[Map[String, JsValue]]
    } getOrElse parameters
    val resp =
      if (params.isEmpty) requestEntity(POST, path)
      else requestEntity(POST, path, body = Some(params.toJson.toString))
    new RestResult(resp.status.intValue, getTransactionId(resp), getRespData(resp))
  }
}

class RestRuleOperations(implicit val actorSystem: ActorSystem)
    extends RestListOrGetFromCollectionOperations
    with RestDeleteFromCollectionOperations
    with WaitFor
    with RuleOperations {

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
                      annotations: Map[String, JsValue] = Map.empty,
                      shared: Option[Boolean] = None,
                      update: Boolean = false,
                      expectedExitCode: Int = SUCCESS_EXIT)(implicit wp: WskProps): RestResult = {
    val path = getNamePath(wp.namespace, noun, name)
    val annos = convertMapIntoKeyValue(annotations)
    val published = shared.getOrElse(false)
    val bodyContent = JsObject(
      "trigger" -> fullEntityName(trigger).toJson,
      "action" -> fullEntityName(action).toJson,
      "publish" -> published.toJson,
      "status" -> "".toJson,
      "annotations" -> annos.toJson)

    val resp =
      if (update) requestEntity(PUT, path, Map("overwrite" -> "true"), Some(bodyContent.toString))
      else requestEntity(PUT, path, body = Some(bodyContent.toString))
    new RestResult(resp.status, getTransactionId(resp), getRespData(resp))
  }

  /**
   * Enables rule.
   *
   * @param name either a fully qualified name or a simple entity name
   * @param expectedExitCode (optional) the expected exit code for the command
   * if the code is anything but DONTCARE_EXIT, assert the code is as expected
   */
  override def enable(name: String, expectedExitCode: Int = SUCCESS_EXIT)(implicit wp: WskProps): RestResult = {
    changeRuleState(name)
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
    val path = getNamePath(wp.namespace, noun, enName)
    val bodyContent = JsObject("status" -> state.toJson)
    val resp = requestEntity(POST, path, body = Some(bodyContent.toString))
    new RestResult(resp.status, getTransactionId(resp), getRespData(resp))
  }
}

class RestActivationOperations(implicit val actorSystem: ActorSystem)
    extends ActivationOperations
    with RunRestCmd
    with RestActivation
    with WaitFor {

  protected val noun = "activations"

  /**
   * Activation polling console.
   *
   * @param duration exits console after duration
   * @param since (optional) time travels back to activation since given duration
   */
  override def console(duration: Duration,
                       since: Option[Duration] = None,
                       expectedExitCode: Int = SUCCESS_EXIT,
                       actionName: Option[String] = None)(implicit wp: WskProps): RestResult = {
    require(duration > 1.second, "duration must be at least 1 second")
    val sinceTime = {
      val now = System.currentTimeMillis
      since.map(s => now - s.toMillis).getOrElse(now)
    }

    retry({
      val result = listActivation(since = Some(Instant.ofEpochMilli(sinceTime)))(wp)
      if (result.stdout != "[]") result else throw new Throwable()
    }, (duration / 1.second).toInt, Some(1.second))
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
                     skip: Option[Int] = None,
                     docs: Boolean = true,
                     expectedExitCode: Int = SUCCESS_EXIT)(implicit wp: WskProps): RestResult = {
    val entityPath = Path(s"$basePath/namespaces/${wp.namespace}/$noun")
    val paramMap = Map("docs" -> docs.toString) ++
      skip.map(s => Map("skip" -> s.toString)).getOrElse(Map.empty) ++
      limit.map(l => Map("limit" -> l.toString)).getOrElse(Map.empty) ++
      filter.map(f => Map("name" -> f.toString)).getOrElse(Map.empty) ++
      since.map(s => Map("since" -> s.toEpochMilli.toString)).getOrElse(Map.empty)
    val resp = requestEntity(GET, entityPath, paramMap)
    new RestResult(resp.status, getTransactionId(resp), getRespData(resp))
  }

  /**
   * Parses result of WskActivation.list to extract sequence of activation ids.
   *
   * @param rr run result, should be from WhiskActivation.list otherwise behavior is undefined
   * @return sequence of activations
   */
  def idsActivation(rr: RestResult): Seq[String] = {
    rr.getBodyListJsObject.map(r => RestResult.getField(r, "activationId").toString)
  }

  /**
   * Gets activation logs by id.
   *
   * @param activationId the activation id
   * @param expectedExitCode (optional) the expected exit code for the command
   * if the code is anything but DONTCARE_EXIT, assert the code is as expected
   */
  def activationLogs(activationId: String, expectedExitCode: Int = OK.intValue)(implicit wp: WskProps): RestResult = {
    val path = Path(s"$basePath/namespaces/${wp.namespace}/$noun/$activationId/logs")
    val resp = requestEntity(GET, path)
    val rr = new RestResult(resp.status, getTransactionId(resp), getRespData(resp))
    validateStatusCode(expectedExitCode, rr.statusCode.intValue)
    rr
  }

  /**
   * Gets activation result by id.
   *
   * @param activationId the activation id
   * @param expectedExitCode (optional) the expected exit code for the command
   * if the code is anything but DONTCARE_EXIT, assert the code is as expected
   */
  def activationResult(activationId: String, expectedExitCode: Int = OK.intValue)(implicit wp: WskProps): RestResult = {
    val path = Path(s"$basePath/namespaces/${wp.namespace}/$noun/$activationId/result")
    val resp = requestEntity(GET, path)
    val rr = new RestResult(resp.status, getTransactionId(resp), getRespData(resp))
    validateStatusCode(expectedExitCode, rr.statusCode.intValue)
    rr
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
   * @param skip (optional) the number of activations to skip
   * @param retries the maximum retries (total timeout is retries + 1 seconds)
   * @return activation ids found, caller must check length of sequence
   */
  override def pollFor(N: Int,
                       entity: Option[String],
                       limit: Option[Int] = Some(30),
                       since: Option[Instant] = None,
                       skip: Option[Int] = Some(0),
                       retries: Int = 10,
                       pollPeriod: Duration = 1.second)(implicit wp: WskProps): Seq[String] = {
    Try {
      retry({
        val result =
          idsActivation(listActivation(filter = entity, limit = limit, since = since, skip = skip, docs = false))
        if (result.length >= N) result else throw PartialResult(result)
      }, retries, waitBeforeRetry = Some(pollPeriod))
    } match {
      case Success(ids)                => ids
      case Failure(PartialResult(ids)) => ids
      case _                           => Seq.empty
    }
  }

  override def get(activationId: Option[String],
                   expectedExitCode: Int = OK.intValue,
                   fieldFilter: Option[String] = None,
                   last: Option[Boolean] = None,
                   summary: Option[Boolean] = None)(implicit wp: WskProps): RestResult = {
    val actId = activationId match {
      case Some(_) => activationId
      case None =>
        last match {
          case Some(true) =>
            val activations = pollFor(N = 1, entity = None, limit = Some(1))
            require(activations.size <= 1)
            activations.headOption
          case _ => None
        }
    }
    val rr = actId match {
      case Some(id) =>
        val resp = requestEntity(GET, getNamePath(wp.namespace, noun, id))
        new RestResult(resp.status, getTransactionId(resp), getRespData(resp))
      case None => new RestResult(NotFound, "")
    }
    validateStatusCode(expectedExitCode, rr.statusCode.intValue)
    rr
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
    } getOrElse Left(s"No activation record for'$activationId'")

  }

  override def logs(activationId: Option[String] = None,
                    expectedExitCode: Int = OK.intValue,
                    last: Option[Boolean] = None)(implicit wp: WskProps): RestResult = {
    val rr = activationId match {
      case Some(id) =>
        val resp = requestEntity(GET, getNamePath(wp.namespace, noun, s"$id/logs"))
        new RestResult(resp.status, getTransactionId(resp), getRespData(resp))

      case None =>
        new RestResult(NotFound, "")
    }
    validateStatusCode(expectedExitCode, rr.statusCode.intValue)
    rr
  }

  override def result(activationId: Option[String] = None,
                      expectedExitCode: Int = OK.intValue,
                      last: Option[Boolean] = None)(implicit wp: WskProps): RestResult = {
    val rr = activationId match {
      case Some(id) =>
        val resp = requestEntity(GET, getNamePath(wp.namespace, noun, s"$id/result"))
        new RestResult(resp.status, getTransactionId(resp), getRespData(resp))

      case None =>
        new RestResult(NotFound, "")
    }
    validateStatusCode(expectedExitCode, rr.statusCode.intValue)
    rr
  }

  /** Used in polling for activations to record partial results from retry poll. */
  private case class PartialResult(ids: Seq[String]) extends Throwable
}

class RestNamespaceOperations(implicit val actorSystem: ActorSystem) extends NamespaceOperations with RunRestCmd {

  protected val noun = "namespaces"

  /**
   * Lists available namespaces for whisk key.
   *
   * @param expectedExitCode (optional) the expected exit code for the command
   * if the code is anything but DONTCARE_EXIT, assert the code is as expected
   */
  override def list(expectedExitCode: Int = OK.intValue, nameSort: Option[Boolean] = None)(implicit
                                                                                           wp: WskProps): RestResult = {
    val entPath = Path(s"$basePath/namespaces")
    val resp = requestEntity(GET, entPath)
    val result = new RestResult(resp.status, getTransactionId(resp), getRespData(resp))
    validateStatusCode(expectedExitCode, result.statusCode.intValue)
    result
  }

  /**
   * Looks up namespace for whisk props.
   *
   * @param wskprops instance of WskProps with an auth key to lookup
   * @return namespace as string
   */
  override def whois()(implicit wskprops: WskProps): String = {
    val ns = list().getBodyListString
    ns.headOption.map(_.toString).getOrElse("")
  }
}

class RestPackageOperations(implicit val actorSystem: ActorSystem)
    extends RestListOrGetFromCollectionOperations
    with RestDeleteFromCollectionOperations
    with PackageOperations {

  override protected val noun = "packages"

  /**
   * Creates package. Parameters mirror those available in the REST.
   *
   * @param name either a fully qualified name or a simple entity name
   * @param expectedExitCode (optional) the expected exit code for the command
   * if the code is anything but DONTCARE_EXIT, assert the code is as expected
   */
  override def create(name: String,
                      parameters: Map[String, JsValue] = Map.empty,
                      annotations: Map[String, JsValue] = Map.empty,
                      parameterFile: Option[String] = None,
                      annotationFile: Option[String] = None,
                      shared: Option[Boolean] = None,
                      update: Boolean = false,
                      expectedExitCode: Int = OK.intValue)(implicit wp: WskProps): RestResult = {
    val path = getNamePath(wp.namespace, noun, name)
    var bodyContent: Map[String, JsValue] = Map.empty

    val (params, annos) = getParamsAnnos(parameters, annotations, parameterFile, annotationFile)
    if (!update) {
      val published = shared.getOrElse(false)
      bodyContent = Map("publish" -> published.toJson, "parameters" -> params.toJson, "annotations" -> annos.toJson)
    } else {
      shared.foreach { s =>
        bodyContent = bodyContent + ("publish" -> s.toJson)
      }

      val inputParams = convertMapIntoKeyValue(parameters)
      if (inputParams.nonEmpty) {
        bodyContent = bodyContent + ("parameters" -> params.toJson)
      }
      val inputAnnos = convertMapIntoKeyValue(annotations)
      if (inputAnnos.nonEmpty) {
        bodyContent = bodyContent + ("annotations" -> annos.toJson)
      }
    }

    val resp =
      if (update) requestEntity(PUT, path, Map("overwrite" -> "true"), Some(JsObject(bodyContent).toString))
      else requestEntity(PUT, path, body = Some(JsObject(bodyContent).toString))
    val r = new RestResult(resp.status, getTransactionId(resp), getRespData(resp))
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
                    parameters: Map[String, JsValue] = Map.empty,
                    annotations: Map[String, JsValue] = Map.empty,
                    expectedExitCode: Int = OK.intValue)(implicit wp: WskProps): RestResult = {
    val params = convertMapIntoKeyValue(parameters)
    val annos = convertMapIntoKeyValue(annotations)

    val (ns, packageName) = getNamespaceEntityName(provider)
    val path = getNamePath(wp.namespace, noun, name)
    val binding = JsObject("namespace" -> ns.toJson, "name" -> packageName.toJson)
    val bodyContent =
      JsObject("binding" -> binding.toJson, "parameters" -> params.toJson, "annotations" -> annos.toJson)
    val resp = requestEntity(PUT, path, Map("overwrite" -> "false"), Some(bodyContent.toString))
    val rr = new RestResult(resp.status, getTransactionId(resp), getRespData(resp))
    validateStatusCode(expectedExitCode, rr.statusCode.intValue)
    rr
  }

}

class RestGatewayOperations(implicit val actorSystem: ActorSystem) extends GatewayOperations with RunRestCmd {
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
        val (ns, actionName) = getNamespaceEntityName(action)
        val actionUrl = s"${WhiskProperties.getApiHostForAction}$basePath/web/$ns/default/$actionName.http"
        val actionAuthKey = wp.authKey
        val testaction = Some(
          new ApiAction(name = actionName, namespace = ns, backendUrl = actionUrl, authkey = actionAuthKey))
        val parms = Map("namespace" -> ns.toJson) ++ {
          basepath map { b =>
            Map("gatewayBasePath" -> b.toJson)
          } getOrElse Map.empty
        } ++ {
          relpath map { r =>
            Map("gatewayPath" -> r.toJson)
          } getOrElse Map.empty
        } ++ {
          operation map { o =>
            Map("gatewayMethod" -> o.toJson)
          } getOrElse Map.empty
        } ++ {
          apiname map { an =>
            Map("apiName" -> an.toJson)
          } getOrElse Map.empty
        } ++ {
          testaction map { a =>
            Map("action" -> a.toJson)
          } getOrElse Map.empty
        } ++ {
          swagger map { s =>
            val swaggerFile = FileUtils.readFileToString(new File(s), StandardCharsets.UTF_8)
            Map("swagger" -> swaggerFile.toJson)
          } getOrElse Map.empty
        }

        val spaceguid = if (wp.authKey.contains(":")) wp.authKey.split(":")(0) else wp.authKey

        val parm = Map[String, JsValue]("apidoc" -> JsObject(parms)) ++ {
          responsetype.map(r => Map("responsetype" -> r.toJson)).getOrElse(Map.empty)
        } ++ {
          Map("accesstoken" -> wp.authKey.toJson)
        } ++ {
          Map("spaceguid" -> spaceguid.toJson)
        }

        invokeAction(
          name = "apimgmt/createApi",
          parameters = parm,
          blocking = true,
          result = true,
          web = true,
          expectedExitCode = expectedExitCode)(wp)
      }
      case None =>
        swagger match {
          case Some(swaggerFile) =>
            var file = ""
            val fileName = swaggerFile.toString
            try {
              file = FileUtils.readFileToString(new File(fileName), StandardCharsets.UTF_8)
            } catch {
              case _: Throwable =>
                return new RestResult(
                  NotFound,
                  "",
                  JsObject("error" -> s"Error reading swagger file '$fileName'".toJson).toString)
            }
            val parms = Map("namespace" -> s"${wp.namespace}".toJson, "swagger" -> file.toJson)
            val parm = Map[String, JsValue]("apidoc" -> JsObject(parms)) ++ {
              responsetype.map(r => Map("responsetype" -> r.toJson)).getOrElse(Map.empty)
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
              web = true,
              expectedExitCode = expectedExitCode)(wp)
          case None => new RestResult(NotFound, "")
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

    val parms = {
      basepathOrApiName map { b =>
        Map("basepath" -> b.toJson)
      } getOrElse Map.empty
    } ++ {
      relpath map { r =>
        Map("relpath" -> r.toJson)
      } getOrElse Map.empty
    } ++ {
      operation map { o =>
        Map("operation" -> o.toJson)
      } getOrElse Map.empty
    } ++ {
      Map("accesstoken" -> wp.authKey.toJson)
    } ++ {
      Map("spaceguid" -> wp.authKey.split(":")(0).toJson)
    }

    invokeAction(
      name = "apimgmt/getApi",
      parameters = parms,
      blocking = true,
      result = true,
      web = true,
      expectedExitCode = OK.intValue)(wp)
  }

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
    val parms = {
      basepathOrApiName map { b =>
        Map("basepath" -> b.toJson)
      } getOrElse Map.empty
    } ++ {
      Map("accesstoken" -> wp.authKey.toJson)
    } ++ {
      Map("spaceguid" -> wp.authKey.split(":")(0).toJson)
    }

    invokeAction(
      name = "apimgmt/getApi",
      parameters = parms,
      blocking = true,
      result = true,
      web = true,
      expectedExitCode = OK.intValue)(wp)
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
    val parms = Map("basepath" -> basepathOrApiName.toJson) ++ {
      relpath map { r =>
        Map("relpath" -> r.toJson)
      } getOrElse Map.empty
    } ++ {
      operation map { o =>
        Map("operation" -> o.toJson)
      } getOrElse Map.empty
    } ++ {
      Map("accesstoken" -> wp.authKey.toJson)
    } ++ {
      Map("spaceguid" -> wp.authKey.split(":")(0).toJson)
    }

    invokeAction(
      name = "apimgmt/deleteApi",
      parameters = parms,
      blocking = true,
      result = true,
      web = true,
      expectedExitCode = expectedExitCode)(wp)
  }
}

trait RunRestCmd extends Matchers with ScalaFutures with SwaggerValidator {

  val protocol: String = loadConfigOrThrow[String]("whisk.controller.protocol")
  val idleTimeout: FiniteDuration = 90 seconds
  val toStrictTimeout: FiniteDuration = 30 seconds
  val queueSize = 10
  val maxOpenRequest = 1024
  val basePath = Path("/api/v1")
  val systemNamespace = "whisk.system"
  val logger = new AkkaLogging(actorSystem.log)

  implicit val config: PatienceConfig = PatienceConfig(100 seconds, 15 milliseconds)
  implicit val actorSystem: ActorSystem
  lazy implicit val executionContext: ExecutionContext = actorSystem.dispatcher

  lazy val connectionContext =
    Https.connectionContextClient(SSL.nonValidatingContext(httpsConfig.clientAuth.toBoolean), true)

  def isStatusCodeExpected(expectedExitCode: Int, statusCode: Int): Boolean = {
    if ((expectedExitCode != DONTCARE_EXIT) && (expectedExitCode != ANY_ERROR_EXIT))
      statusCode == expectedExitCode
    else true
  }

  def validateStatusCode(expectedExitCode: Int, statusCode: Int): Unit = {
    if ((expectedExitCode != DONTCARE_EXIT) && (expectedExitCode != ANY_ERROR_EXIT))
      if (!isStatusCodeExpected(expectedExitCode, statusCode)) {
        statusCode shouldBe expectedExitCode
      }
  }

  def getNamePath(ns: String, noun: String, name: String) = Path(s"$basePath/namespaces/$ns/$noun/$name")

  def getExt(filePath: String): String = Option(FilenameUtils.getExtension(filePath)).getOrElse("")

  def requestEntity(method: HttpMethod,
                    path: Path,
                    params: Map[String, String] = Map.empty,
                    body: Option[String] = None)(implicit wp: WskProps): HttpResponse = {

    val creds = getHttpCredentials(wp)

    // startsWith(http) includes https
    val hostWithScheme = if (wp.apihost.startsWith("http")) {
      Uri(wp.apihost)
    } else {
      Uri().withScheme("https").withHost(wp.apihost)
    }

    val request = HttpRequest(
      method,
      hostWithScheme.withPath(path).withQuery(Query(params)),
      List(Authorization(creds)),
      entity =
        body.map(b => HttpEntity.Strict(ContentTypes.`application/json`, ByteString(b))).getOrElse(HttpEntity.Empty))
    val response = Http().singleRequest(request, connectionContext).flatMap { _.toStrict(toStrictTimeout) }.futureValue

    logger.debug(this, s"Request: $request")
    logger.debug(this, s"Response: $response")

    val validationErrors = validateRequestAndResponse(request, response)
    if (validationErrors.nonEmpty) {
      fail(
        s"HTTP request or response did not match the Swagger spec.\nRequest: $request\n" +
          s"Response: $response\nValidation Error: $validationErrors")
    }
    response
  }

  private def getHttpCredentials(wp: WskProps) = {
    if (wp.authKey.contains(":")) {
      val authKey = wp.authKey.split(":")
      new BasicHttpCredentials(authKey(0), authKey(1))
    } else {
      if (wp.basicAuth) {
        new BasicHttpCredentials(wp.authKey, wp.authKey)
      } else {
        OAuth2BearerToken(wp.authKey)
      }
    }
  }

  def getParamsAnnos(parameters: Map[String, JsValue] = Map.empty,
                     annotations: Map[String, JsValue] = Map.empty,
                     parameterFile: Option[String] = None,
                     annotationFile: Option[String] = None,
                     feed: Option[String] = None,
                     web: Option[String] = None): (Array[JsValue], Array[JsValue]) = {
    val params = parameterFile.map(convertStringIntoKeyValue(_)).getOrElse(convertMapIntoKeyValue(parameters))

    val annos = annotationFile
      .map(convertStringIntoKeyValue(_, feed, web))
      .getOrElse(convertMapIntoKeyValue(annotations, feed, web))

    (params, annos)
  }

  def convertStringIntoKeyValue(file: String,
                                feed: Option[String] = None,
                                web: Option[String] = None): Array[JsValue] = {
    val input = FileUtils.readFileToString(new File(file), StandardCharsets.UTF_8)
    val in = input.parseJson.convertTo[Map[String, JsValue]]
    convertMapIntoKeyValue(in, feed, web)
  }

  def convertMapIntoKeyValue(params: Map[String, JsValue],
                             feed: Option[String] = None,
                             web: Option[String] = None,
                             oldParams: List[JsObject] = List.empty): Array[JsValue] = {
    val newParams =
      params
        .map { case (key, value) => JsObject("key" -> key.toJson, "value" -> value) } ++ feed.map(f =>
        JsObject("key" -> "feed".toJson, "value" -> f.toJson))

    val paramsList = {
      if (newParams.nonEmpty) newParams
      else oldParams
    }

    val webOpt = web.map {
      case "true" | "yes" =>
        Seq(
          JsObject("key" -> "web-export".toJson, "value" -> true.toJson),
          JsObject("key" -> "raw-http".toJson, "value" -> false.toJson),
          JsObject("key" -> "final".toJson, "value" -> true.toJson))
      case "false" | "no" =>
        Seq(
          JsObject("key" -> "web-export".toJson, "value" -> false.toJson),
          JsObject("key" -> "raw-http".toJson, "value" -> false.toJson),
          JsObject("key" -> "final".toJson, "value" -> false.toJson))
      case "raw" =>
        Seq(
          JsObject("key" -> "web-export".toJson, "value" -> true.toJson),
          JsObject("key" -> "raw-http".toJson, "value" -> true.toJson),
          JsObject("key" -> "final".toJson, "value" -> true.toJson))
      case _ =>
        Seq.empty
    }

    webOpt
      .map(paramsList ++ _)
      .getOrElse(paramsList)
      .toArray
  }

  def entityName(name: String)(implicit wp: WskProps): String = {
    val sep = "/"
    if (name.startsWith(sep)) name.substring(name.indexOf(sep, name.indexOf(sep) + 1) + 1, name.length)
    else name
  }

  def fullEntityName(name: String)(implicit wp: WskProps): String = {
    val (ns, rest) = getNamespaceEntityName(name)
    if (rest.nonEmpty) s"/$ns/$rest"
    else s"/$ns"
  }

  def convertIntoComponents(comps: String)(implicit wp: WskProps): Array[JsValue] = {
    comps.split(",").filter(_.nonEmpty).map(comp => fullEntityName(comp).toJson)
  }

  def getRespData(resp: HttpResponse): String = {
    val timeout = toStrictTimeout
    Try(resp.entity.toStrict(timeout).map { _.data }.map(_.utf8String).futureValue).getOrElse("")
  }

  def getTransactionId(resp: HttpResponse): String = {
    val tidHeader = resp.headers.find(_.is(TransactionId.generatorConfig.lowerCaseHeader))
    withClue(
      s"The header ${TransactionId.generatorConfig} is not set. This means that the request did not reach nginx (or the controller if nginx is skipped in that test).") {
      tidHeader shouldBe defined
    }
    tidHeader.get.value
  }

  def getNamespaceEntityName(name: String)(implicit wp: WskProps): (String, String) = {
    name.split("/") match {
      // Example: /namespace/package_name/entity_name
      case Array(empty, namespace, packageName, entityName) if empty.isEmpty => (namespace, s"$packageName/$entityName")
      // Example: /namespace/entity_name
      case Array(empty, namespace, entityName) if empty.isEmpty => (namespace, entityName)
      // Example: namespace/package_name/entity_name
      case Array(namespace, packageName, entityName) => (namespace, s"$packageName/$entityName")
      // Example: /namespace
      case Array(empty, namespace) if empty.isEmpty => (namespace, "")
      // Example: package_name/entity_name
      case Array(packageName, entityName) if !packageName.isEmpty => (wp.namespace, s"$packageName/$entityName")
      // Example: entity_name
      case Array(entityName) => (wp.namespace, entityName)
      case _                 => (wp.namespace, name)
    }
  }

  def invokeAction(name: String,
                   parameters: Map[String, JsValue] = Map.empty,
                   parameterFile: Option[String] = None,
                   blocking: Boolean = false,
                   result: Boolean = false,
                   web: Boolean = false,
                   expectedExitCode: Int = Accepted.intValue)(implicit wp: WskProps): RestResult = {
    val (ns, actName) = getNamespaceEntityName(name)

    val path =
      if (web) Path(s"$basePath/web/$systemNamespace/$actName.http")
      else Path(s"$basePath/namespaces/$ns/actions/$actName")

    val paramMap = Map("blocking" -> blocking.toString, "result" -> result.toString)

    val input = parameterFile map { pf =>
      Some(FileUtils.readFileToString(new File(pf), StandardCharsets.UTF_8))
    } getOrElse Some(parameters.toJson.toString)

    val resp = requestEntity(POST, path, paramMap, input)

    val rr = new RestResult(resp.status.intValue, getTransactionId(resp), getRespData(resp), blocking)

    // If the statusCode does not not equal to expectedExitCode, it is acceptable that the statusCode
    // equals to 200 for the case that either blocking or result is set to true.
    if (!isStatusCodeExpected(expectedExitCode, rr.statusCode.intValue)) {
      if (blocking || result) {
        validateStatusCode(OK.intValue, rr.statusCode.intValue)
      } else {
        rr.statusCode.intValue shouldBe expectedExitCode
      }
    }

    rr
  }
}

object RestResult {
  def getField(obj: JsObject, key: String): String = {
    obj.fields.get(key).map(_.convertTo[String]).getOrElse("")
  }

  def getFieldJsObject(obj: JsObject, key: String): JsObject = {
    obj.fields.get(key).map(_.asJsObject).getOrElse(JsObject.empty)
  }

  def getFieldJsValue(obj: JsObject, key: String): JsValue = {
    obj.fields.getOrElse(key, JsObject.empty)
  }

  def getFieldListJsObject(obj: JsObject, key: String): Vector[JsObject] = {
    obj.fields.get(key).map(_.convertTo[Vector[JsObject]]).getOrElse(Vector(JsObject.empty))
  }

  def convertStausCodeToExitCode(statusCode: StatusCode, blocking: Boolean = false): Int = {
    if ((statusCode == OK) || (!blocking && (statusCode == Accepted))) 0
    else statusCode.intValue % 256
  }

  def convertHttpResponseToStderr(respData: String): String = {
    Try(getField(respData.parseJson.asJsObject, "error")).getOrElse("")
  }
}

class RestResult(val statusCode: StatusCode, val tid: String, val respData: String = "", blocking: Boolean = false)
    extends RunResult(
      RestResult.convertStausCodeToExitCode(statusCode, blocking),
      respData,
      RestResult.convertHttpResponseToStderr(respData)) {

  override def toString: String = {
    super.toString + s"""statusCode: $statusCode
       |tid: $tid
       |respData: $respData
       |blocking: $blocking""".stripMargin
  }

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

  def getBodyListJsObject: Vector[JsObject] = {
    respData.parseJson.convertTo[Vector[JsObject]]
  }

  def getBodyListString: Vector[String] = {
    respData.parseJson.convertTo[Vector[String]]
  }
}

class ApiAction(val name: String,
                val namespace: String,
                val backendMethod: String = "POST",
                val backendUrl: String,
                val authkey: String) {
  def toJson: JsObject = {
    JsObject(
      "name" -> name.toJson,
      "namespace" -> namespace.toJson,
      "backendMethod" -> backendMethod.toJson,
      "backendUrl" -> backendUrl.toJson,
      "authkey" -> authkey.toJson)
  }
}
