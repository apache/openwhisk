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
import java.time.Instant
import java.util.Base64
import java.security.cert.X509Certificate

import org.apache.commons.io.FileUtils
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.Span.convertDurationToSpan

import scala.collection.immutable.Seq
import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
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
import akka.http.scaladsl.model.Uri.{Path, Query}
import akka.http.scaladsl.model.HttpMethods.DELETE
import akka.http.scaladsl.model.HttpMethods.GET
import akka.http.scaladsl.model.HttpMethods.POST
import akka.http.scaladsl.model.HttpMethods.PUT
import akka.http.scaladsl.HttpsConnectionContext
import akka.stream.ActorMaterializer
import spray.json._
import spray.json.DefaultJsonProtocol._
import common._
import common.DeleteFromCollectionOperations
import common.ListOrGetFromCollectionOperations
import common.HasActivation
import common.TestUtils.SUCCESS_EXIT
import common.TestUtils.ANY_ERROR_EXIT
import common.TestUtils.DONTCARE_EXIT
import common.TestUtils.RunResult
import common.WaitFor
import common.WhiskProperties
import common.WskActorSystem
import common.WskProps
import whisk.core.entity.ByteSize
import whisk.utils.retry
import javax.net.ssl._
import com.typesafe.sslconfig.akka.AkkaSSLConfig
import java.nio.charset.StandardCharsets
import java.security.KeyStore

import akka.actor.ActorSystem
import pureconfig.loadConfigOrThrow
import whisk.common.Https.HttpsConfig

class AcceptAllHostNameVerifier extends HostnameVerifier {
  override def verify(s: String, sslSession: SSLSession): Boolean = true
}

object SSL {

  lazy val httpsConfig = loadConfigOrThrow[HttpsConfig]("whisk.controller.https")

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
      def checkClientTrusted(chain: Array[X509Certificate], authType: String) = ()
      def checkServerTrusted(chain: Array[X509Certificate], authType: String) = ()
      def getAcceptedIssuers: Array[X509Certificate] = Array.empty
    }

    val context = SSLContext.getInstance("TLS")

    context.init(keyManagers(clientAuth), Array(new IgnoreX509TrustManager), null)
    context
  }

  def httpsConnectionContext(implicit system: ActorSystem) = {
    val sslConfig = AkkaSSLConfig().mapSettings { s =>
      s.withHostnameVerifierClass(classOf[AcceptAllHostNameVerifier].asInstanceOf[Class[HostnameVerifier]])
    }
    new HttpsConnectionContext(SSL.nonValidatingContext(httpsConfig.clientAuth.toBoolean), Some(sslConfig))
  }
}

object HttpConnection {

  /**
   * Returns either the https context that is tailored for self-signed certificates on the controller, or
   * a default connection context used in Http.SingleRequest
   * @param protocol protocol used to communicate with controller API
   * @param system actor system
   * @return https connection context
   */
  def getContext(protocol: String)(implicit system: ActorSystem) = {
    if (protocol == "https") {
      SSL.httpsConnectionContext
    } else {
      // supports http
      Http().defaultClientHttpsContext
    }
  }
}

class WskRestOperations() extends RunWskRestCmd with WskOperations {
  override implicit val action = new RestActionOperations
  override implicit val trigger = new RestTriggerOperations
  override implicit val rule = new RestRuleOperations
  override implicit val activation = new RestActivationOperations
  override implicit val pkg = new RestPackageOperations
  override implicit val namespace = new RestNamespaceOperations
  override implicit val api = new RestGatewayOperations
}

trait RestListOrGetFromCollectionOperations extends RunWskRestCmd with ListOrGetFromCollectionOperations {
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
    val rr = new RestResult(resp.status, getRespData(resp))
    validateStatusCode(expectedExitCode, rr.statusCode.intValue)
    rr
  }
}

trait RestDeleteFromCollectionOperations extends RunWskRestCmd with DeleteFromCollectionOperations {

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
    val rr = new RestResult(resp.status, getRespData(resp))
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

class RestActionOperations
    extends RunWskRestCmd
    with RestListOrGetFromCollectionOperations
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
    parameterFile: Option[String] = None,
    annotationFile: Option[String] = None,
    timeout: Option[Duration] = None,
    memory: Option[ByteSize] = None,
    logsize: Option[ByteSize] = None,
    shared: Option[Boolean] = None,
    update: Boolean = false,
    web: Option[String] = None,
    websecure: Option[String] = None,
    expectedExitCode: Int = OK.intValue)(implicit wp: WskProps): RestResult = {

    val (namespace, actName) = getNamespaceEntityName(name)
    val (kindTypeByAction, code, artifactName) = artifact match {
      case Some(artifactFile) => {
        val ext = getExt(artifactFile)
        ext match {
          case ".jar" => {
            val jar = FileUtils.readFileToByteArray(new File(artifactFile))
            ("java:default", Base64.getEncoder.encodeToString(jar), artifactFile)
          }
          case ".zip" => {
            val zip = FileUtils.readFileToByteArray(new File(artifactFile))
            ("", Base64.getEncoder.encodeToString(zip), artifactFile)
          }
          case ".js" => {
            ("nodejs:default", FileUtils.readFileToString(new File(artifactFile), StandardCharsets.UTF_8), artifactFile)
          }
          case ".py" => {
            ("python:default", FileUtils.readFileToString(new File(artifactFile), StandardCharsets.UTF_8), artifactFile)
          }
          case ".swift" => {
            ("swift:default", FileUtils.readFileToString(new File(artifactFile), StandardCharsets.UTF_8), artifactFile)
          }
          case ".php" => {
            ("php:default", FileUtils.readFileToString(new File(artifactFile), StandardCharsets.UTF_8), artifactFile)
          }
          case _ => ("", "", artifactFile)
        }
      }
      case None => ("", "", "")
    }

    val kindType = docker map { d =>
      "blackbox"
    } getOrElse kindTypeByAction

    val (paramsInput, annosInput) = getParamsAnnos(parameters, annotations, parameterFile, annotationFile, web = web)

    val (params, annos, execByKind) = kind match {
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
              val params = result.getFieldListJsObject("parameters").toArray[JsValue]
              val annos = result.getFieldListJsObject("annotations").toArray[JsValue]
              val exec = result.getFieldJsObject("exec").fields
              (params, annos, exec)
            }
          }
          case "sequence" => {
            val comps = convertIntoComponents(artifactName)
            val exec =
              if (comps.size > 0) Map("components" -> comps.toJson, "kind" -> k.toJson)
              else
                Map("kind" -> k.toJson)
            (paramsInput, annosInput, exec)
          }
          case "native" => {
            val exec =
              Map("code" -> code.toJson, "kind" -> "blackbox".toJson, "image" -> "openwhisk/dockerskeleton".toJson)
            (paramsInput, annosInput, exec)
          }
          case _ => {
            val exec = Map("code" -> code.toJson, "kind" -> k.toJson)
            (paramsInput, annosInput, exec)
          }
        }
      }
      case None => (paramsInput, annosInput, Map("code" -> code.toJson, "kind" -> kindType.toJson))
    }

    val exec = execByKind ++ {
      main map { m =>
        Map("main" -> m.toJson)
      } getOrElse Map.empty
    } ++ {
      docker map { d =>
        Map("kind" -> "blackbox".toJson, "image" -> d.toJson)
      } getOrElse Map.empty
    }

    val bodyHead = Map("name" -> name.toJson, "namespace" -> namespace.toJson)

    val (updateExistence, resultExistence) = update match {
      case true => {
        val (ns, entity) = getNamespaceEntityName(name)
        val entPath = Path(s"$basePath/namespaces/$ns/$noun/$entity")
        val resp = requestEntity(GET, entPath)(wp)
        val result = if (resp == None) new RestResult(NotFound) else new RestResult(resp.status, getRespData(resp))
        if (result.statusCode == NotFound) (false, result) else (true, result)
      }
      case _ => (false, new RestResult(NotFound))
    }

    val bodyWithParamsAnnos = if (updateExistence) {
      val oldAnnos = resultExistence.getFieldListJsObject("annotations")
      val inputParams = convertMapIntoKeyValue(parameters)
      val inputAnnos = convertMapIntoKeyValue(annotations, web = web, oldParams = oldAnnos.toList)

      bodyHead ++ {
        kind match {
          case Some(k) if (k == "sequence" || k == "native") => {
            Map("exec" -> exec.toJson)
          }
          case _ => Map.empty
        }
      } ++ {
        shared map { s =>
          Map("publish" -> s.toJson)
        } getOrElse Map.empty
      } ++ {
        if (inputParams.size > 0) {
          Map("parameters" -> params.toJson)
        } else Map.empty
      } ++ {
        if (inputAnnos.size > 0) {
          Map("annotations" -> inputAnnos.toJson)
        } else Map.empty
      }
    } else {
      bodyHead ++ Map("exec" -> exec.toJson, "parameters" -> params.toJson, "annotations" -> annos.toJson)
    }

    val limits: Map[String, JsValue] = {
      timeout map { t =>
        Map("timeout" -> t.toMillis.toJson)
      } getOrElse Map.empty
    } ++ {
      logsize map { log =>
        Map("logs" -> log.toMB.toJson)
      } getOrElse Map.empty
    } ++ {
      memory map { m =>
        Map("memory" -> m.toMB.toJson)
      } getOrElse Map.empty
    }

    val bodyContent =
      if (!limits.isEmpty)
        bodyWithParamsAnnos ++ Map("limits" -> limits.toJson)
      else bodyWithParamsAnnos

    val path = Path(s"$basePath/namespaces/$namespace/$noun/$actName")
    val resp =
      if (update) requestEntity(PUT, path, Map("overwrite" -> "true"), Some(JsObject(bodyContent).toString))
      else requestEntity(PUT, path, body = Some(JsObject(bodyContent).toString))
    val r = new RestResult(resp.status, getRespData(resp))
    validateStatusCode(expectedExitCode, r.statusCode.intValue)
    r
  }

  override def invoke(name: String,
                      parameters: Map[String, JsValue] = Map.empty,
                      parameterFile: Option[String] = None,
                      blocking: Boolean = false,
                      result: Boolean = false,
                      expectedExitCode: Int = Accepted.intValue)(implicit wp: WskProps): RestResult = {
    super.invokeAction(name, parameters, parameterFile, blocking, result, expectedExitCode = expectedExitCode)
  }
}

class RestTriggerOperations
    extends RunWskRestCmd
    with RestListOrGetFromCollectionOperations
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

    val (ns, triggerName) = this.getNamespaceEntityName(name)
    val path = Path(s"$basePath/namespaces/$ns/$noun/$triggerName")
    val (params, annos) = getParamsAnnos(parameters, annotations, parameterFile, annotationFile, feed)
    var bodyContent = JsObject("name" -> name.toJson, "namespace" -> s"$ns".toJson)

    if (!update) {
      val published = shared.getOrElse(false)
      bodyContent = JsObject(
        bodyContent.fields + ("publish" -> published.toJson,
        "parameters" -> params.toJson, "annotations" -> annos.toJson))
    } else {
      bodyContent = shared map { s =>
        JsObject(bodyContent.fields + ("publish" -> s.toJson))
      } getOrElse bodyContent

      val inputParams = convertMapIntoKeyValue(parameters)
      if (inputParams.size > 0) {
        bodyContent = JsObject(bodyContent.fields + ("parameters" -> params.toJson))
      }
      val inputAnnos = convertMapIntoKeyValue(annotations)
      if (inputAnnos.size > 0) {
        bodyContent = JsObject(bodyContent.fields + ("annotations" -> annos.toJson))
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
        "authKey" -> s"${wp.authKey}".toJson)
      body = body ++ parameters
      val resp = requestEntity(POST, path, paramMap, Some(body.toJson.toString))
      val resultInvoke = new RestResult(resp.status, getRespData(resp))
      if ((expectedExitCode != DONTCARE_EXIT) && (expectedExitCode != ANY_ERROR_EXIT))
        expectedExitCode shouldBe resultInvoke.statusCode.intValue
      if (resultInvoke.statusCode != OK) {
        // Remove the trigger, because the feed failed to invoke.
        this.delete(triggerName)
        new RestResult(NotFound)
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
                    parameters: Map[String, JsValue] = Map.empty,
                    parameterFile: Option[String] = None,
                    expectedExitCode: Int = Accepted.intValue)(implicit wp: WskProps): RestResult = {
    val path = getNamePath(noun, name)
    val params = parameterFile map { l =>
      val input = FileUtils.readFileToString(new File(l), StandardCharsets.UTF_8)
      input.parseJson.convertTo[Map[String, JsValue]]
    } getOrElse parameters
    val resp =
      if (params.size == 0) requestEntity(POST, path)
      else requestEntity(POST, path, body = Some(params.toJson.toString))
    new RestResult(resp.status.intValue, getRespData(resp))
  }
}

class RestRuleOperations
    extends RunWskRestCmd
    with RestListOrGetFromCollectionOperations
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
    val path = getNamePath(noun, name)
    val annos = convertMapIntoKeyValue(annotations)
    val published = shared.getOrElse(false)
    val bodyContent = JsObject(
      "trigger" -> fullEntityName(trigger).toJson,
      "action" -> fullEntityName(action).toJson,
      "publish" -> published.toJson,
      "name" -> name.toJson,
      "status" -> "".toJson,
      "annotations" -> annos.toJson)

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

class RestActivationOperations extends RunWskRestCmd with RestActivation with WaitFor with ActivationOperations {

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
      val now = System.currentTimeMillis()
      since map { s =>
        now - s.toMillis
      } getOrElse now
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
                     docs: Boolean = true,
                     expectedExitCode: Int = SUCCESS_EXIT)(implicit wp: WskProps): RestResult = {
    val entityPath = Path(s"${basePath}/namespaces/${wp.namespace}/$noun")
    var paramMap = Map("skip" -> "0", "docs" -> docs.toString) ++ {
      limit map { l =>
        Map("limit" -> l.toString)
      } getOrElse Map.empty
    } ++ {
      filter map { f =>
        Map("name" -> f.toString)
      } getOrElse Map.empty
    } ++ {
      since map { s =>
        Map("since" -> s.toEpochMilli.toString)
      } getOrElse Map.empty
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
      case _                           => Seq.empty
    }
  }

  override def get(activationId: Option[String],
                   expectedExitCode: Int = OK.intValue,
                   fieldFilter: Option[String] = None,
                   last: Option[Boolean] = None,
                   summary: Option[Boolean] = None)(implicit wp: WskProps): RestResult = {
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

class RestNamespaceOperations extends RunWskRestCmd with NamespaceOperations {

  protected val noun = "namespaces"

  /**
   * Lists available namespaces for whisk key.
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
   * Looks up namespace for whisk props.
   *
   * @param wskprops instance of WskProps with an auth key to lookup
   * @return namespace as string
   */
  override def whois()(implicit wskprops: WskProps): String = {
    val ns = list().getBodyListString
    if (ns.size > 0) ns(0).toString else ""
  }
}

class RestPackageOperations
    extends RunWskRestCmd
    with RestListOrGetFromCollectionOperations
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
    val path = getNamePath(noun, name)
    var bodyContent = JsObject("namespace" -> s"${wp.namespace}".toJson, "name" -> name.toJson)

    val (params, annos) = this.getParamsAnnos(parameters, annotations, parameterFile, annotationFile)
    if (!update) {
      val published = shared.getOrElse(false)
      bodyContent = JsObject(
        bodyContent.fields + ("publish" -> published.toJson,
        "parameters" -> params.toJson, "annotations" -> annos.toJson))
    } else {
      if (shared != None)
        bodyContent = shared map { s =>
          JsObject(bodyContent.fields + ("publish" -> s.toJson))
        } getOrElse bodyContent

      val inputParams = convertMapIntoKeyValue(parameters)
      if (inputParams.size > 0) {
        bodyContent = JsObject(bodyContent.fields + ("parameters" -> params.toJson))
      }
      val inputAnnos = convertMapIntoKeyValue(annotations)
      if (inputAnnos.size > 0) {
        bodyContent = JsObject(bodyContent.fields + ("annotations" -> annos.toJson))
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
                    parameters: Map[String, JsValue] = Map.empty,
                    annotations: Map[String, JsValue] = Map.empty,
                    expectedExitCode: Int = OK.intValue)(implicit wp: WskProps): RestResult = {
    val params = convertMapIntoKeyValue(parameters)
    val annos = convertMapIntoKeyValue(annotations)

    val (ns, packageName) = this.getNamespaceEntityName(provider)
    val path = getNamePath(noun, name)
    val binding = JsObject("namespace" -> ns.toJson, "name" -> packageName.toJson)
    val bodyContent =
      JsObject("binding" -> binding.toJson, "parameters" -> params.toJson, "annotations" -> annos.toJson)
    val resp = requestEntity(PUT, path, Map("overwrite" -> "false"), Some(bodyContent.toString))
    val r = new RestResult(resp.status, getRespData(resp))
    validateStatusCode(expectedExitCode, r.statusCode.intValue)
    r
  }

}

class RestGatewayOperations extends RunWskRestCmd with GatewayOperations {
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
      case None => {
        swagger match {
          case Some(swaggerFile) => {
            var file = ""
            val fileName = swaggerFile.toString
            try {
              file = FileUtils.readFileToString(new File(fileName), StandardCharsets.UTF_8)
            } catch {
              case e: Throwable =>
                return new RestResult(
                  NotFound,
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
          }
          case None => {
            new RestResult(NotFound)
          }
        }
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

class RunWskRestCmd() extends FlatSpec with Matchers with ScalaFutures with WskActorSystem {

  implicit val config = PatienceConfig(100 seconds, 15 milliseconds)
  implicit val materializer = ActorMaterializer()
  val protocol = loadConfigOrThrow[String]("whisk.controller.protocol")
  val idleTimeout = 90 seconds
  val queueSize = 10
  val maxOpenRequest = 1024
  val basePath = Path("/api/v1")
  val systemNamespace = "whisk.system"

  val sslConfig = AkkaSSLConfig().mapSettings {
    _.withHostnameVerifierClass(classOf[AcceptAllHostNameVerifier].asInstanceOf[Class[HostnameVerifier]])
  }

  val connectionContext = new HttpsConnectionContext(SSL.nonValidatingContext(), Some(sslConfig))

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

  def getNamePath(noun: String, name: String)(implicit wp: WskProps): Path = {
    Path(s"$basePath/namespaces/${wp.namespace}/$noun/$name")
  }

  def getExt(filePath: String)(implicit wp: WskProps) = {
    val sep = "."
    if (filePath.contains(sep)) filePath.substring(filePath.lastIndexOf(sep), filePath.length)
    else ""
  }

  def requestEntity(method: HttpMethod,
                    path: Path,
                    params: Map[String, String] = Map.empty,
                    body: Option[String] = None)(implicit wp: WskProps): HttpResponse = {

    val creds = getBasicHttpCredentials(wp)

    // startsWith(http) includes https
    val hostWithScheme = if (wp.apihost.startsWith("http")) {
      Uri(wp.apihost)
    } else {
      Uri().withScheme("https").withHost(wp.apihost)
    }

    val entity = body map { b =>
      HttpEntity(ContentTypes.`application/json`, b)
    } getOrElse HttpEntity.Empty

    val request =
      HttpRequest(
        method,
        hostWithScheme.withPath(path).withQuery(Query(params)),
        List(Authorization(creds)),
        entity = entity)
    Http().singleRequest(request, connectionContext).futureValue
  }

  private def getBasicHttpCredentials(wp: WskProps): BasicHttpCredentials = {
    if (wp.authKey.contains(":")) {
      val authKey = wp.authKey.split(":")
      new BasicHttpCredentials(authKey(0), authKey(1))
    } else {
      new BasicHttpCredentials(wp.authKey, wp.authKey)
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

  def entityName(name: String)(implicit wp: WskProps) = {
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
    val (ns, actName) = this.getNamespaceEntityName(name)

    val path =
      if (web) Path(s"$basePath/web/$systemNamespace/$actName.http")
      else Path(s"$basePath/namespaces/$ns/actions/$actName")

    val paramMap = Map("blocking" -> blocking.toString, "result" -> result.toString)

    val input = parameterFile map { pf =>
      Some(FileUtils.readFileToString(new File(pf), StandardCharsets.UTF_8))
    } getOrElse Some(parameters.toJson.toString)

    val resp = requestEntity(POST, path, paramMap, input)

    val rr = new RestResult(resp.status.intValue, getRespData(resp), blocking)

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

  def convertStausCodeToExitCode(statusCode: StatusCode, blocking: Boolean = false): Int = {
    if ((statusCode == OK) || (!blocking && (statusCode == Accepted))) 0
    else if (statusCode.intValue < BadRequest.intValue) statusCode.intValue
    else statusCode.intValue - codeConversion
  }

  def convertHttpResponseToStderr(respData: String): String = {
    Try {
      getField(respData.parseJson.asJsObject, "error")
    } getOrElse {
      ""
    }
  }
}

class RestResult(var statusCode: StatusCode, var respData: String = "", blocking: Boolean = false)
    extends RunResult(
      RestResult.convertStausCodeToExitCode(statusCode, blocking),
      respData,
      RestResult.convertHttpResponseToStderr(respData)) {

  override def toString: String = {
    super.toString + s"""statusCode: $statusCode
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

  def getBodyListJsObject(): Vector[JsObject] = {
    respData.parseJson.convertTo[Vector[JsObject]]
  }

  def getBodyListString(): Vector[String] = {
    respData.parseJson.convertTo[Vector[String]]
  }
}

class ApiAction(var name: String,
                var namespace: String,
                var backendMethod: String = "POST",
                var backendUrl: String,
                var authkey: String) {
  def toJson() = {
    JsObject(
      "name" -> name.toJson,
      "namespace" -> namespace.toJson,
      "backendMethod" -> backendMethod.toJson,
      "backendUrl" -> backendUrl.toJson,
      "authkey" -> authkey.toJson)
  }
}
