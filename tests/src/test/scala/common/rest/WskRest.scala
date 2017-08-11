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

import akka.util.Timeout

import java.io.File
import java.time.Clock
import java.time.Instant
import java.util.Base64

import org.apache.commons.io.FileUtils
import org.scalatest.Matchers
import org.scalatest.FlatSpec
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.Span.convertDurationToSpan

import scala.Left
import scala.Right
import scala.collection.JavaConversions.mapAsJavaMap
import scala.collection.mutable.Buffer
import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.{Success, Failure}
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

import spray.client.pipelining.Get
import spray.client.pipelining.WithTransformerConcatenation
import spray.client.pipelining.addCredentials
import spray.client.pipelining.sendReceive
import spray.http.BasicHttpCredentials
import spray.http.HttpEntity
import spray.http.StatusCode
import spray.http.ContentTypes
import spray.http.HttpRequest
import spray.http.HttpResponse
import spray.http.HttpHeader
import spray.http.StatusCodes.Accepted
import spray.http.StatusCodes.OK
import spray.http.StatusCodes.NotFound
import spray.http.Uri
import spray.http.Uri.Path
import spray.httpx.unmarshalling.FromResponseUnmarshaller
import spray.httpx.unmarshalling.MalformedContent
import spray.httpx.unmarshalling._
import spray.httpx.PipelineException
import spray.client.pipelining.Put
import spray.client.pipelining.Post
import spray.client.pipelining.Delete
//import spray.json.JsObject
//import spray.json.JsValue
//import spray.json.JsArray
//import spray.json.pimpString
import spray.json._
import spray.json.DefaultJsonProtocol._

import common.TestUtils
import common.TestUtils.SUCCESS_EXIT
import common.TestUtils.DONTCARE_EXIT
import common.TestUtils.ANY_ERROR_EXIT
import common.TestUtils.DONTCARE_EXIT
import common.TestUtils.RunResult
import common.RunWskCmd
import common.WskProps
import common.WaitFor
import common.WskActorSystem
import common.WhiskProperties
import common.DeleteFromCollection
import common.FullyQualifiedNames
import common.ListOrGetFromCollection
import common.HasActivation
import common._

//import system.rest.RestUtil
import common.TestUtils.RunResult
import whisk.utils.retry

class WskRest() {
    implicit val action = new WskRestAction("actions")
    implicit val trigger = new WskRestTrigger("triggers")
    implicit val rule = new WskRestRule("rules")
    implicit val activation = new WskRestActivation(new RunWskRestCmd("activations"))
    implicit val pkg = new WskRestPackage("packages")
    implicit val namespace = new WskRestNamespace("namespaces")
    implicit val api = new WskRestApi("apis")
}

trait FullyQualifiedNamesRest extends FullyQualifiedNames {

    def getExt(filePath: String)(implicit wp: WskProps) = {
        val sep = "."
        if (filePath.contains(sep)) filePath.substring(filePath.lastIndexOf(sep), filePath.length())
        else null
    }
}

trait ListOrGetFromCollectionRest extends ListOrGetFromCollection
    with FullyQualifiedNamesRest {
    self: RunWskRestCmd =>

    /**
     * List entities in collection.
     *
     * @param namespace (optional) if specified must be  fully qualified namespace
     * @param expectedExitCode (optional) the expected exit code for the command
     * if the code is anything but DONTCARE_EXIT, assert the code is as expected
     */
    override def list(
        namespace: Option[String] = None,
        limit: Option[Int] = None,
        expectedExitCode: Int = SUCCESS_EXIT)(
            implicit wp: WskProps): RestResult = {
        val NS = if (namespace == None) "" else namespace.get
        val (ns, name) = getNamespaceActionName(NS)

        val entPath = if (name != "") Path(s"$basePath/namespaces/$ns/$entityName/$name/") else Path(s"$basePath/namespaces/$ns/$entityName")
        var paramMap = Map("skip" -> "0".toString, "docs" -> true.toString)
        if (limit != None)
            paramMap += ("limit" -> limit.get.toString)
        else
            paramMap += ("limit" -> "30".toString)
        val resp = getWhiskEntity(entPath, paramMap).futureValue
        if (resp == None) {
            return new RestResult(NotFound, new HttpResponse())
        }
        else {
            val r = new RestResult(resp.get.status, resp.get)
            r
        }

    }

    /**
     * Gets entity from collection.
     *
     * @param name either a fully qualified name or a simple entity name
     * @param expectedExitCode (optional) the expected exit code for the command
     * if the code is anything but DONTCARE_EXIT, assert the code is as expected
     */
    
    override def get(
        name: String,
        expectedExitCode: Int = OK.intValue,
        summary: Boolean = false,
        fieldFilter: Option[String] = None,
        url: Option[Boolean] = None)(
            implicit wp: WskProps): RestResult = {
        val (ns, entity) = getNamespaceActionName(name)
        val entPath = Path(s"$basePath/namespaces/$ns/$entityName/$entity")
        val resp = getWhiskEntity(entPath)(wp).futureValue
        val result = new RestResult(resp.get.status, resp.get)
        if (expectedExitCode != DONTCARE_EXIT)
            expectedExitCode shouldBe result.statusCode.intValue
        result
    }
}

trait DeleteFromCollectionRest extends DeleteFromCollection 
    with FullyQualifiedNamesRest {
    self: RunWskRestCmd =>

    /**
     * Deletes entity from collection.
     *
     * @param name either a fully qualified name or a simple entity name
     * @param expectedExitCode (optional) the expected exit code for the command
     * if the code is anything but DONTCARE_EXIT, assert the code is as expected
     */
    override def delete(
        entity: String, expectedExitCode: Int = OK.intValue)(
            implicit wp: WskProps): RestResult = {
        val result = deleteEntity(entity)(wp)
        if (expectedExitCode != DONTCARE_EXIT)
            result.statusCode.intValue shouldBe expectedExitCode
        result
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
        Try {
            // try to interpret the run result as the result of an invoke
            extractActivationIdFromInvoke(result.asInstanceOf[RestResult]).get
        } toOption
    }

    /**
     * Extracts activation id from 'wsk action invoke' or 'wsk trigger invoke'
     */
    private def extractActivationIdFromInvoke(result: RestResult): Option[String] = {
        Try {
            var activationID = if ((result.statusCode == OK) || (result.statusCode == Accepted)) result.getField("activationId") else ""
            activationID
        } toOption
    }
}

class WskRestAction(var entity: String)
    extends RunWskRestCmd(entity)
    with ListOrGetFromCollectionRest
    with DeleteFromCollectionRest
    with HasActivationRest {

    override protected val noun = "action"
    //override lazy val entityName = "actions"
    override implicit val config = PatienceConfig(100 seconds, 15 milliseconds)

    /**
     * Creates action. Parameters mirror those available in the REST.
     *
     * @param name either a fully qualified name or a simple entity name
     * @param expectedExitCode (optional) the expected exit code for the command
     * if the code is anything but DONTCARE_EXIT, assert the code is as expected
     */
    def create(
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
        shared: Option[Boolean] = None,
        update: Boolean = false,
        web: Option[String] = None,
        expectedExitCode: Int = OK.intValue)(
            implicit wp: WskProps): RestResult = {

        val (namespace, actName) = getNamespaceActionName(name)
        var params = JsArray(Vector())
        var annos = JsArray(Vector())
        var exec: JsObject = JsObject()

        val ext = if (artifact != None) getExt(artifact.get) else null
        var code = ""
        var kindType = ""
        if (ext == ".jar") {
            kindType = "java:default"
            val jar = FileUtils.readFileToByteArray(new File(artifact.get))
            code = Base64.getEncoder.encodeToString(jar)
        }
        else if (ext == ".zip") {
            val zip = FileUtils.readFileToByteArray(new File(artifact.get))
            code = Base64.getEncoder.encodeToString(zip)
        }
        else if (ext == ".js") {
            kindType = "nodejs:default"
            code = FileUtils.readFileToString(new File(artifact.get))
        }
        else if (ext == ".py") {
             kindType = "python:default"
             code = FileUtils.readFileToString(new File(artifact.get))
        }
        else if (ext == ".swift") {
             kindType = "swift:default"
             code = FileUtils.readFileToString(new File(artifact.get))
        }

        if (docker != None) {
            kindType = "blackbox"
        }

        val (p, a) = getParamsAnnos(parameters, annotations, parameterFile, annotationFile, web=web)

        if (kind == None) {
            params = p
            annos = a
            exec = JsObject("code" -> code.toJson,
                "kind" -> kindType.toJson)
        }
        else if (kind.get == "copy") {
            val actName = entityName(artifact.get)
            val actionPath = Path(s"$basePath/namespaces/$namespace/actions/$actName")
            val resp = getWhiskEntity(actionPath).futureValue
            if (resp == None)
                return new RestResult(NotFound, new HttpResponse())
            else {
                val result = new RestResult(resp.get.status, resp.get)
                params = JsArray(result.getFieldListJsObject("parameters"))
                annos = JsArray(result.getFieldListJsObject("annotations"))
                exec = result.getFieldJsObject("exec")
            }
        }
        else if (kind.get == "sequence") {
            kindType = "sequence"
            val (p, a) = getParamsAnnos(parameters, annotations, parameterFile, annotationFile)
            params = p
            annos = a
            val comps = convertIntoComponents(artifact.get)
            exec = JsObject("components" -> comps.toJson, "kind" -> kindType.toJson)
        }
        else if (kind.get == "native") {
            val (p, a) = getParamsAnnos(parameters, annotations, parameterFile, annotationFile)
            params = p
            annos = a
            exec = JsObject("code" -> code.toJson,
                "kind" -> "blackbox".toJson, "image" -> "openwhisk/dockerskeleton".toJson)
        }
        else {
            kindType = kind.get
            val (p, a) = getParamsAnnos(parameters, annotations, parameterFile, annotationFile)
            params = p
            annos = a
            exec = JsObject("code" -> code.toJson,
                "kind" -> kindType.toJson)
        }

        if (main != None) {
            exec = JsObject(exec.fields + ("main" -> main.get.toJson))
        }

        if (docker != None) {
            exec = JsObject("kind" -> "blackbox".toJson, "image" -> docker.get.toJson)
        }

        var bodyContent = JsObject("exec" -> exec.toJson,
            "name" -> s"$name".toJson, "namespace" -> s"$namespace".toJson,
            "parameters" -> params, "annotations" -> annos)

        if (timeout != None) {
            bodyContent = JsObject(bodyContent.fields + ("limits" -> JsObject("timeout" -> timeout.get.toMillis.toJson)))
        }

        if (update) {
            bodyContent = JsObject("name" -> name.toJson,
                "namespace" -> namespace.toJson)

            if ((kind != None)  && ((kind.get == "sequence") || (kind.get == "native"))) {
                bodyContent = JsObject(bodyContent.fields + ("exec" -> exec.toJson))
            }

            if (shared != None)
                bodyContent = JsObject(bodyContent.fields + ("publish" -> shared.get.toJson))

            if (params.elements.size > 0) {
                val params = convertMapIntoKeyValue(parameters)
                bodyContent = JsObject(bodyContent.fields + ("parameters" -> params))
            }
            if (annos.elements.size > 0) {
                val annos = convertMapIntoKeyValue(annotations)
                bodyContent = JsObject(bodyContent.fields + ("annotations" -> annos))
            }
        }

        val path = Path(s"$basePath/namespaces/$namespace/actions/$actName")
        val respFuture = if (update) createEntity(path, bodyContent.toString, Map("overwrite" -> "true")) else createEntity(path, bodyContent.toString)
        val resp = respFuture.futureValue.get
        val r = new RestResult(resp.status, resp)
        if ((expectedExitCode != DONTCARE_EXIT) && (expectedExitCode != ANY_ERROR_EXIT))
            r.statusCode.intValue shouldBe expectedExitCode
        r
    }
}

class WskRestTrigger(var entity: String)
    extends RunWskRestCmd(entity)
    with ListOrGetFromCollectionRest
    with DeleteFromCollectionRest
    with HasActivationRest {

    override protected val noun = "trigger"
    //override lazy val entityName = "triggers"

    /**
     * Creates trigger. Parameters mirror those available in the REST.
     *
     * @param name either a fully qualified name or a simple entity name
     * @param expectedExitCode (optional) the expected exit code for the command
     * if the code is anything but DONTCARE_EXIT, assert the code is as expected
     */
    def create(
        name: String,
        parameters: Map[String, JsValue] = Map(),
        annotations: Map[String, JsValue] = Map(),
        parameterFile: Option[String] = None,
        annotationFile: Option[String] = None,
        feed: Option[String] = None,
        shared: Option[Boolean] = None,
        update: Boolean = false,
        expectedExitCode: Int = OK.intValue)(
            implicit wp: WskProps): RestResult = {

        val (ns, triggerName) = this.getNamespaceActionName(name)
        val path = Path(s"$basePath/namespaces/$ns/triggers/$triggerName")

        val (params, annos) = getParamsAnnos(parameters, annotations, parameterFile, annotationFile, feed)

        var bodyContent = JsObject("name" -> name.toJson,
            "namespace" -> s"$ns".toJson)

        if (!update) {
            val published = if (shared == None) false else shared.get
            bodyContent = JsObject(bodyContent.fields + ("publish" -> published.toJson,
                "parameters" -> params, "annotations" -> annos))
        }
        else {
            if (shared != None)
                bodyContent = JsObject(bodyContent.fields + ("publish" -> shared.get.toJson))

            if (params.elements.size > 0) {
                val params = convertMapIntoKeyValue(parameters)
                bodyContent = JsObject(bodyContent.fields + ("parameters" -> params))
            }
            if (annos.elements.size > 0) {
                val annos = convertMapIntoKeyValue(annotations)
                bodyContent = JsObject(bodyContent.fields + ("annotations" -> annos))
            }
        }

        val respFuture = if (update) createEntity(path, bodyContent.toString, Map("overwrite" -> "true")) else createEntity(path, bodyContent.toString)
        val resp = respFuture.futureValue.get
        val result = new RestResult(resp.status, resp)
        if ((feed == None) || (result.statusCode != OK)) {
            expectedExitCode shouldBe result.statusCode.intValue
            result
        }
        else {
            // Invoke the feed
            val (nsFeed, feedName) = this.getNamespaceActionName(feed.get)
            val path = Path(s"$basePath/namespaces/$nsFeed/actions/$feedName")
            val paramMap = Map("blocking" -> "true".toString, "result" -> "false".toString)
            var params: Map[String, JsValue] = Map("lifecycleEvent" -> "CREATE".toJson,
                "triggerName" -> s"/$ns/$triggerName".toJson, "authKey" -> s"${getAuthKey(wp)}".toJson)
            params = params ++ parameters
            val resp = invokeAction(path, params.toJson.toString(), paramMap).futureValue.get
            val resultInvoke = new RestResult(resp.status, resp)
            expectedExitCode shouldBe resultInvoke.statusCode.intValue
            if (resultInvoke.statusCode != OK) {
                // Remove the trigger, because the feed failed to invoke.
                val r = this.delete(triggerName)
                r
            }
            else {
                result
            }
        }

    }

    /**
     * Fires trigger. Parameters mirror those available in the REST.
     *
     * @param name either a fully qualified name or a simple entity name
     * @param expectedExitCode (optional) the expected exit code for the command
     * if the code is anything but DONTCARE_EXIT, assert the code is as expected
     */
    def fire(
        name: String,
        parameters: Map[String, JsValue] = Map(),
        parameterFile: Option[String] = None,
        expectedExitCode: Int = SUCCESS_EXIT)(
            implicit wp: WskProps): RestResult = {
        val path = Path(s"$basePath/namespaces/${wp.namespace}/triggers/$name")
        var params: Map[String, JsValue] = Map()
        if (parameterFile != None) {
            val input = FileUtils.readFileToString(new File(parameterFile.get))
            params = input.parseJson.convertTo[Map[String, JsValue]]
        }
        else
            params = parameters

        parameters.toJson.toString()
        val resp = if (params.size == 0) postEntity(path).futureValue.get else postEntity(path, params.toJson.toString()).futureValue.get
        val r = new RestResult(resp.status.intValue, resp)
        r
    }
}

class WskRestRule(var entity: String)
    extends RunWskRestCmd(entity)
    with ListOrGetFromCollectionRest
    with DeleteFromCollectionRest
    with WaitFor {

    override protected val noun = "rule"
    //override lazy val entityName = "rules"

    /**
     * Creates rule. Parameters mirror those available in the REST.
     *
     * @param name either a fully qualified name or a simple entity name
     * @param trigger must be a simple name
     * @param action must be a simple name
     * @param expectedExitCode (optional) the expected exit code for the command
     * if the code is anything but DONTCARE_EXIT, assert the code is as expected
     */
    def create(
        name: String,
        trigger: String,
        action: String,
        annotations: Map[String, JsValue] = Map(),
        shared: Option[Boolean] = None,
        update: Boolean = false,
        expectedExitCode: Int = SUCCESS_EXIT)(
            implicit wp: WskProps): RestResult = {
        val path = Path(s"$basePath/namespaces/${wp.namespace}/rules/$name")
        val annos = convertMapIntoKeyValue(annotations)
        val published = if (shared == None) false else shared.get
        val bodyContent = JsObject("trigger" -> fullEntityName(trigger).toJson,
            "action" -> fullEntityName(action).toJson, "name" -> name.toJson, "status" -> "".toJson)
        val respFuture = if (update) createEntity(path, bodyContent.toString, Map("overwrite" -> "true")) else createEntity(path, bodyContent.toString)
        val resp = respFuture.futureValue.get
        val r = new RestResult(resp.status, resp)
        r
    }

    /**
     * Enables rule.
     *
     * @param name either a fully qualified name or a simple entity name
     * @param expectedExitCode (optional) the expected exit code for the command
     * if the code is anything but DONTCARE_EXIT, assert the code is as expected
     */
    def enable(
        name: String,
        expectedExitCode: Int = SUCCESS_EXIT)(
            implicit wp: WskProps): RestResult = {
        changeRuleState(name, "active")
    }

    /**
     * Disables rule.
     *
     * @param name either a fully qualified name or a simple entity name
     * @param expectedExitCode (optional) the expected exit code for the command
     * if the code is anything but DONTCARE_EXIT, assert the code is as expected
     */
    def disable(
        name: String,
        expectedExitCode: Int = SUCCESS_EXIT)(
            implicit wp: WskProps): RestResult = {
        changeRuleState(name, "inactive")
    }

    /**
     * Checks state of rule.
     *
     * @param name either a fully qualified name or a simple entity name
     * @param expectedExitCode (optional) the expected exit code for the command
     * if the code is anything but DONTCARE_EXIT, assert the code is as expected
     */
    def state(
        name: String,
        expectedExitCode: Int = OK.intValue)(
            implicit wp: WskProps): RestResult = {
        get(name, expectedExitCode = expectedExitCode)
    }
}

/*class WskRestActivation()
    extends RunWskRestCmd
    with HasActivationRest
    with WaitFor {*/

class WskRestActivation(var restComm: RunWskRestCmd)
    extends WskActivation
    with HasActivationRest
    with WaitFor
    with BaseRestWsk {

    //override protected val noun = "activation"
    //override lazy val entityName = "activations"

    /**
     * Activation polling console.
     *
     * @param duration exits console after duration
     * @param since (optional) time travels back to activation since given duration
     */
    override def console(
        duration: Duration,
        since: Option[Duration] = None,
        expectedExitCode: Int = SUCCESS_EXIT)(
            implicit wp: WskProps): RestResult = {
        var sinceTime = System.currentTimeMillis()
        val utc = Instant.now(Clock.systemUTC()).toEpochMilli
        if (since != None) {
            sinceTime = sinceTime - (since.get).toMillis
        }
        val pollTimeout = duration.toSeconds
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
    def listActivation(
        filter: Option[String] = None,
        limit: Option[Int] = None,
        since: Option[Instant] = None,
        docs: Boolean = true,
        expectedExitCode: Int = SUCCESS_EXIT)(
            implicit wp: WskProps): RestResult = {
        val entityPath = Path(s"${restComm.basePath}/namespaces/${wp.namespace}/activations")
        var paramMap = Map("skip" -> "0".toString, "docs" -> docs.toString)
        if (limit != None)
            paramMap += ("limit" -> limit.get.toString)
        else
            paramMap += ("limit" -> "30".toString)
        if (filter != None)
            paramMap += ("name" -> filter.get.toString)
        if (since != None)
            paramMap += ("since" -> since.get.toEpochMilli().toString)
        val resp = restComm.getWhiskEntity(entityPath, paramMap).futureValue
        if (resp == None) {
            return new RestResult(NotFound, new HttpResponse())
        }
        else {
            val r = new RestResult(resp.get.status, resp.get)
            r
        }
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
    def activationLogs(
        activationId: String,
        expectedExitCode: Int = OK.intValue)(
            implicit wp: WskProps): RestResult = {
        val path = Path(s"${restComm.basePath}/namespaces/${wp.namespace}/activations/$activationId/logs")
        val resp = restComm.getWhiskEntity(path).futureValue
        var r = new RestResult(resp.get.status, resp.get)
        if (expectedExitCode != DONTCARE_EXIT) {
            r.statusCode.intValue shouldBe expectedExitCode
        }
        r
    }

    /**
     * Gets activation result by id.
     *
     * @param activationId the activation id
     * @param expectedExitCode (optional) the expected exit code for the command
     * if the code is anything but DONTCARE_EXIT, assert the code is as expected
     */
    def activationResult(
        activationId: String,
        expectedExitCode: Int = OK.intValue)(
            implicit wp: WskProps): RestResult = {
        val path = Path(s"${restComm.basePath}/namespaces/${wp.namespace}/activations/$activationId/result")
        val resp = restComm.getWhiskEntity(path).futureValue
        var r = new RestResult(resp.get.status, resp.get)
        if (expectedExitCode != DONTCARE_EXIT) {
            r.statusCode.intValue shouldBe expectedExitCode
        }
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
    override def pollFor(
        N: Int,
        entity: Option[String],
        limit: Option[Int] = Some(30),
        since: Option[Instant] = None,
        retries: Int = 10,
        pollPeriod: Duration = 1.second)(
            implicit wp: WskProps): Seq[String] = {
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

    def getActivation(activationId: String, expectedExitCode: Int = OK.intValue)(implicit wp: WskProps): RestResult = {
        val path = Path(s"${restComm.basePath}/namespaces/${wp.namespace}/activations/$activationId")
        val resp = restComm.getWhiskEntity(path).futureValue
        var r = new RestResult(NotFound, new HttpResponse())
        if (resp != None) {
            r = new RestResult(resp.get.status, resp.get)
        }
        r
    }

    /**
     * Polls for an activation matching the given id. If found
     * return Right(activation) else Left(result of calling REST API).
     *
     * @return either Left(error message) or Right(activation as JsObject)
     */
    override def waitForActivation(
        activationId: String,
        initialWait: Duration = 1 second,
        pollPeriod: Duration = 1 second,
        totalWait: Duration = 30 seconds)(
            implicit wp: WskProps): Either[String, JsObject] = {
        val activation = waitfor(() => {
            val result = getActivation(activationId)(wp)
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

    def waitForActivationConsole(
        totalWait: Duration = 30 seconds,
        sinceTime: Instant)(
            implicit wp: WskProps): RestResult = {
        var result = new RestResult(NotFound, new HttpResponse())
        Thread.sleep(totalWait.toMillis)
        result = listActivation(since=Some(sinceTime))(wp)
        result

    }

    /** Used in polling for activations to record partial results from retry poll. */
    private case class PartialResult(ids: Seq[String]) extends Throwable
}

class WskRestNamespace(var entity: String)
    extends RunWskRestCmd(entity)
    with FullyQualifiedNamesRest {

    protected val noun = "namespace"
    //override lazy val entityName = "namespaces"

    /**
     * Lists available namespaces for whisk properties.
     *
     * @param expectedExitCode (optional) the expected exit code for the command
     * if the code is anything but DONTCARE_EXIT, assert the code is as expected
     */
    def list(
        namespace: Option[String] = None,
        limit: Option[Int] = None,
        expectedExitCode: Int = OK.intValue)(
            implicit wp: WskProps): RestResult = {
        val entPath = Path(s"$basePath/namespaces")
        val resp = getWhiskEntity(entPath).futureValue
        val result = if (resp == None) new RestResult(NotFound, new HttpResponse()) else new RestResult(resp.get.status, resp.get)
        if (expectedExitCode != DONTCARE_EXIT)
            result.statusCode.intValue shouldBe expectedExitCode
        result
    }

    /**
     * Gets entities in namespace.
     *
     * @param namespace (optional) if specified must be  fully qualified namespace
     * @param expectedExitCode (optional) the expected exit code for the command
     * if the code is anything but DONTCARE_EXIT, assert the code is as expected
     */
    def get(
        namespace: Option[String] = None,
        expectedExitCode: Int = OK.intValue)(
            implicit wp: WskProps): RestResult = {
        val (ns, _) = if (namespace == None) (wp.namespace, "") else this.getNamespaceActionName(namespace.get)
        val entPath = Path(s"$basePath/namespaces/$ns/")
        val resp = getWhiskEntity(entPath).futureValue
        val r = new RestResult(resp.get.status, resp.get)
        if (expectedExitCode != DONTCARE_EXIT) {
            r.statusCode.intValue shouldBe expectedExitCode
        }
        r
    }

    /**
     * Looks up namespace for whisk props.
     *
     * @param wskprops instance of WskProps with an auth key to lookup
     * @return namespace as string
     */
    def whois()(implicit wskprops: WskProps): String = {
        // the invariant that list() returns a conforming result is enforced in a test in WskBasicTests
        val ns = list().getBodyListString
        //.stdout.lines.toSeq.last.trim
        if (ns.size > 0) ns(0).toString() else ""
    }
}

class WskRestPackage(var entity: String)
    extends RunWskRestCmd(entity)
    with ListOrGetFromCollectionRest
    with DeleteFromCollectionRest
    with BasePackage {
    override protected val noun = "package"
    //override lazy val entityName = "packages"

    /**
     * Creates package. Parameters mirror those available in the REST.
     *
     * @param name either a fully qualified name or a simple entity name
     * @param expectedExitCode (optional) the expected exit code for the command
     * if the code is anything but DONTCARE_EXIT, assert the code is as expected
     */
    def create(
        name: String,
        parameters: Map[String, JsValue] = Map(),
        annotations: Map[String, JsValue] = Map(),
        parameterFile: Option[String] = None,
        annotationFile: Option[String] = None,
        shared: Option[Boolean] = None,
        update: Boolean = false,
        expectedExitCode: Int = OK.intValue)(
            implicit wp: WskProps): RestResult = {
        val path = Path(s"$basePath/namespaces/${wp.namespace}/packages/$name")

        var bodyContent = JsObject("namespace" -> s"${wp.namespace}".toJson, "name" -> name.toJson)

        val (params, annos) = this.getParamsAnnos(parameters, annotations, parameterFile, annotationFile)
        if (!update) {
            val published = if (shared == None) false else shared.get
            bodyContent = JsObject(bodyContent.fields + ("publish" -> published.toJson,
                "parameters" -> params, "annotations" -> annos))
        }
        else {
            if (shared != None)
                bodyContent = JsObject(bodyContent.fields + ("publish" -> shared.get.toJson))

            if (params.elements.size > 0) {
                val params = convertMapIntoKeyValue(parameters)
                bodyContent = JsObject(bodyContent.fields + ("parameters" -> params))
            }
            if (annos.elements.size > 0) {
                val annos = convertMapIntoKeyValue(annotations)
                bodyContent = JsObject(bodyContent.fields + ("annotations" -> annos))
            }
        }
        val respFuture = if (update) createEntity(path, bodyContent.toString, Map("overwrite" -> "true")) else createEntity(path, bodyContent.toString)
        val resp = respFuture.futureValue.get
        val r = new RestResult(resp.status, resp)
        expectedExitCode shouldBe r.statusCode.intValue
        r
    }

    /**
     * Binds package. Parameters mirror those available in the REST.
     *
     * @param name either a fully qualified name or a simple entity name
     * @param expectedExitCode (optional) the expected exit code for the command
     * if the code is anything but DONTCARE_EXIT, assert the code is as expected
     */
    def bind(
        provider: String,
        name: String,
        parameters: Map[String, JsValue] = Map(),
        annotations: Map[String, JsValue] = Map(),
        expectedExitCode: Int = OK.intValue)(
            implicit wp: WskProps): RestResult = {
        val params = convertMapIntoKeyValue(parameters)
        val annos = convertMapIntoKeyValue(annotations)

        val (ns, packageName) = this.getNamespaceActionName(provider)
        val path = Path(s"$basePath/namespaces/${wp.namespace}/packages/$name")
        val binding = JsObject("namespace" -> ns.toJson,
            "name" -> packageName.toJson)
        val bodyContent = JsObject("binding" -> binding.toJson,
            "parameters" -> params, "annotations" -> annos)
        val respFuture = createEntity(path, bodyContent.toString, Map("overwrite" -> "false"))
        val resp = respFuture.futureValue.get
        val r = new RestResult(resp.status, resp)
        expectedExitCode shouldBe r.statusCode.intValue
        r
    }

}

class WskRestApi(var entity: String)
    extends RunWskRestCmd(entity) {
    protected val noun = "api"
    //override lazy val entityName = "apis"

    /**
     * Creates and API endpoint. Parameters mirror those available in the REST.
     *
     * @param expectedExitCode (optional) the expected exit code for the command
     * if the code is anything but DONTCARE_EXIT, assert the code is as expected
     */
    def create(
        basepath: Option[String] = None,
        relpath: Option[String] = None,
        operation: Option[String] = None,
        apiname: Option[String] = None,
        action: Option[String] = None,
        swagger: Option[String] = None,
        accesstoken: Option[String] = None,
        spaceguid: Option[String] = None,
        responsetype: Option[String] = Some("http"),
        expectedExitCode: Int = OK.intValue)
        (implicit wp: WskProps): RestResult = {
        val (ns, actionName) = this.getNamespaceActionName(if (action == None) "" else action.get)
        val actionUrl = s"https://${WhiskProperties.getBaseControllerHost()}$basePath/web/$ns/default/$actionName.http"
        val actionAuthKey = this.getAuthKey(wp)
        val testaction = Some(ApiAction(name = actionName, namespace = ns, backendUrl = actionUrl, authkey = actionAuthKey))

        val parms = Map[String, JsValue]() ++
            { Map("namespace" -> ns.toJson) } ++
            { basepath map { b => Map("gatewayBasePath" -> b.toJson) } getOrElse Map[String, JsValue]() } ++
            { relpath map { r => Map("gatewayPath" -> r.toJson) } getOrElse Map[String, JsValue]() } ++
            { operation map { o => Map("gatewayMethod" -> o.toJson) } getOrElse Map[String, JsValue]() } ++
            { apiname map { an => Map("apiName" -> an.toJson) } getOrElse Map[String, JsValue]() } ++
            { testaction map { a => Map("action" -> a.toJson) } getOrElse Map[String, JsValue]() } ++
            { swagger map {
                s =>
                    val swaggerFile = FileUtils.readFileToString(new File(swagger.get))
                    Map("swagger" -> swaggerFile.toJson)
                } getOrElse Map[String, JsValue]() }

        val parm = Map[String, JsValue]("apidoc" -> JsObject(parms)) ++
            { Map("__ow_user" -> ns.toJson) } ++
            { responsetype map { r => Map("responsetype" -> r.toJson) } getOrElse Map[String, JsValue]() } ++
            { accesstoken map { t => Map("accesstoken" -> t.toJson) } getOrElse Map("accesstoken" -> wp.authKey.toJson) } ++
            { spaceguid map { s => Map("spaceguid" -> s.toJson) } getOrElse Map("spaceguid" -> wp.authKey.split(":")(0).toJson) }

        val rr = invoke(
            name = "apimgmt/createApi",
            parameters = parm,
            blocking = true,
            result = true,
            expectedExitCode = expectedExitCode)(wp)
        return rr
    }

    /**
     * Retrieve a list of API endpoints. Parameters mirror those available in the REST.
     *
     * @param expectedExitCode (optional) the expected exit code for the command
     * if the code is anything but DONTCARE_EXIT, assert the code is as expected
     */
    def list(
        basepathOrApiName: Option[String] = None,
        relpath: Option[String] = None,
        operation: Option[String] = None,
        docid: Option[String] = None,
        accesstoken: Option[String] = None,
        spaceguid: Option[String] = None,
        expectedExitCode: Int = OK.intValue)(
            implicit wp: WskProps): RestResult = {

        val parms = Map[String, JsValue]() ++
            Map("__ow_user" -> wp.namespace.toJson) ++
            { basepathOrApiName map { b => Map("basepath" -> b.toJson) } getOrElse Map[String, JsValue]() } ++
            { relpath map { r => Map("relpath" -> r.toJson) } getOrElse Map[String, JsValue]() } ++
            { operation map { o => Map("operation" -> o.toJson) } getOrElse Map[String, JsValue]() } ++
            { docid map { d => Map("docid" -> d.toJson) } getOrElse Map[String, JsValue]() } ++
            { accesstoken map { t => Map("accesstoken" -> t.toJson) } getOrElse Map("accesstoken" -> wp.authKey.toJson) } ++
            { spaceguid map { s => Map("spaceguid" -> s.toJson) } getOrElse Map("spaceguid" -> wp.authKey.split(":")(0).toJson) }
        val rr = invoke(
            name = "apimgmt/getApi",
            parameters = parms,
            blocking = true,
            result = true,
            expectedExitCode = OK.intValue)(wp)
        rr
    }

    /**
     * Retieves an API's configuration. Parameters mirror those available in the REST.
     * Runs a command wsk [params] where the arguments come in as a sequence.
     *
     * @param expectedExitCode (optional) the expected exit code for the command
     * if the code is anything but DONTCARE_EXIT, assert the code is as expected
     */
    def get(
        basepathOrApiName: Option[String],
        relpath: Option[String] = None,
        operation: Option[String] = None,
        docid: Option[String] = None,
        accesstoken: Option[String] = None,
        spaceguid: Option[String] = None,
        expectedExitCode: Int = OK.intValue)(
            implicit wp: WskProps): JsObject = {
        val parms = Map[String, JsValue]() ++
            Map("__ow_user" -> wp.namespace.toJson) ++
            { basepathOrApiName map { b => Map("basepath" -> b.toJson) } getOrElse Map[String, JsValue]() } ++
            { relpath map { r => Map("relpath" -> r.toJson) } getOrElse Map[String, JsValue]() } ++
            { operation map { o => Map("operation" -> o.toJson) } getOrElse Map[String, JsValue]() } ++
            { docid map { d => Map("docid" -> d.toJson) } getOrElse Map[String, JsValue]() } ++
            { accesstoken map { t => Map("accesstoken" -> t.toJson) } getOrElse Map("accesstoken" -> wp.authKey.toJson) } ++
            { spaceguid map { s => Map("spaceguid" -> s.toJson) } getOrElse Map("spaceguid" -> wp.authKey.split(":")(0).toJson) }

        val result = invoke(
            name = "apimgmt/getApi",
            parameters = parms,
            blocking = true,
            result = true,
            expectedExitCode = OK.intValue)(wp)

        val apis = result.getFieldListJsObject("apis")
        for (api <- apis) {
            val apiValue = RestResult.getFieldJsObject(api, "value")
            if (RestResult.getField(apiValue, "gwApiUrl").endsWith(basepathOrApiName.get)) {
                return api
            }
        }
        JsObject()
    }

    /**
     * Delete an entire API or a subset of API endpoints. Parameters mirror those available in the REST.
     *
     * @param expectedExitCode (optional) the expected exit code for the command
     * if the code is anything but DONTCARE_EXIT, assert the code is as expected
     */
    def delete(
        basepathOrApiName: Option[String],
        relpath: Option[String] = None,
        operation: Option[String] = None,
        apiname: Option[String] = None,
        accesstoken: Option[String] = None,
        spaceguid: Option[String] = None,
        expectedExitCode: Int = OK.intValue)(
            implicit wp: WskProps): RestResult = {
        val parms = Map[String, JsValue]() ++
            { Map("__ow_user" -> wp.namespace.toJson) } ++
            { basepathOrApiName map { b => Map("basepath" -> b.toJson) } getOrElse Map[String, JsValue]() } ++
            { relpath map { r => Map("relpath" -> r.toJson) } getOrElse Map[String, JsValue]() } ++
            { operation map { o => Map("operation" -> o.toJson) } getOrElse Map[String, JsValue]() } ++
            { apiname map { an => Map("apiname" -> an.toJson) } getOrElse Map[String, JsValue]() } ++
            { accesstoken map { t => Map("accesstoken" -> t.toJson) } getOrElse Map("accesstoken" -> wp.authKey.toJson) } ++
            { spaceguid map { s => Map("spaceguid" -> s.toJson) } getOrElse Map("spaceguid" -> wp.authKey.split(":")(0).toJson) }

        val rr = invoke(
            name = "apimgmt/deleteApi",
            parameters = parms,
            blocking = true,
            result = true,
            expectedExitCode = expectedExitCode)(wp)
        return rr
    }

    def getApi(
        basepathOrApiName: String,
        params: Map[String, String] = null,
        expectedExitCode: Int = OK.intValue)(
            implicit wp: WskProps): RestResult = {
        val whiskUrl = Uri(s"http://${WhiskProperties.getBaseControllerHost()}:9001")
        val path = Path(s"/api/${wp.authKey.split(":")(0)}$basepathOrApiName/path")
        val resp = getWhiskEntity(path, params, whiskUrl=whiskUrl).futureValue
        val result = new RestResult(resp.get.status, resp.get)
        result
    }
}

trait BaseRestWsk extends ScalaFutures {
    var restComm: RunWskRestCmd
}

class RunWskRestCmd(var entityName: String) extends FlatSpec
    with RunWskCmd
    with Matchers
    with ScalaFutures
    with WskActorSystem {
    
    implicit val config = PatienceConfig(100 seconds, 15 milliseconds)
    val whiskRestUrl = Uri(s"http://${WhiskProperties.getBaseControllerAddress()}")
    val basePath = Path("/api/v1")
    implicit val timeout: Timeout = Timeout(4.minutes)

    def customUnmarshal[T](implicit respUnmarshaller: FromResponseUnmarshaller[T]): Future[HttpResponse] => Future[Option[T]] =
        (respFuture: Future[HttpResponse]) =>
            respFuture.map {
                response =>
                    response.as[T] match {
                        case Right(value) ⇒ Some(value)
                        case Left(error: MalformedContent) ⇒
                            throw new PipelineException(error.errorMessage, error.cause.orNull)
                        case Left(error) ⇒ throw new PipelineException(error.toString)
                    }
            }


    def pipeline[T](request: HttpRequest,
        creds: BasicHttpCredentials)(implicit respUnmarshaller: FromResponseUnmarshaller[T]): Future[Option[T]] = (
        addCredentials(creds)
        ~> sendReceive
        ~> customUnmarshal[T]
    ).apply(request)

    def createEntity(path: Path, body: String, params: Map[String, String] = null, whiskUrl: Uri = whiskRestUrl)(
            implicit wp: WskProps): Future[Option[HttpResponse]] = {
        val creds = getBasicHttpCredentials(wp)
        if (params != null)
            pipeline[HttpResponse](Put(whiskUrl.withPath(path).copy(query = Uri.Query(params)))
                .withEntity(HttpEntity(contentType = ContentTypes.`application/json`, string = body)), creds = creds)
        else
            pipeline[HttpResponse](Put(whiskUrl.withPath(path))
                .withEntity(HttpEntity(contentType = ContentTypes.`application/json`, string = body)), creds = creds)
    }

    def invokeAction(path: Path, body: String, params: Map[String, String] = null, whiskUrl: Uri = whiskRestUrl)(
            implicit wp: WskProps): Future[Option[HttpResponse]] = {
        val creds = getBasicHttpCredentials(wp)
        if (params != null)
            pipeline[HttpResponse](Post(whiskUrl.withPath(path).copy(query = Uri.Query(params)))
                .withEntity(HttpEntity(contentType = ContentTypes.`application/json`, string = body)), creds = creds)
        else
            pipeline[HttpResponse](Post(whiskUrl.withPath(path))
                .withEntity(HttpEntity(contentType = ContentTypes.`application/json`, string = body)), creds = creds)
    }

    def postEntity(path: Path, body: String = null, whiskUrl: Uri = whiskRestUrl)(
            implicit wp: WskProps): Future[Option[HttpResponse]] = {
        val creds = getBasicHttpCredentials(wp)
        if (body != null)
            pipeline[HttpResponse](Post(whiskUrl.withPath(path))
                .withEntity(HttpEntity(contentType = ContentTypes.`application/json`, string = body)), creds = creds)
        else
            pipeline[HttpResponse](Post(whiskUrl.withPath(path)), creds = creds)
    }

    def deleteEntity(path: Path, whiskUrl: Uri = whiskRestUrl)(
            implicit wp: WskProps): Future[Option[HttpResponse]] = {
        val creds = getBasicHttpCredentials(wp)
        pipeline[HttpResponse](Delete(whiskUrl.withPath(path)), creds = creds)
    }

    def getWhiskEntity(path: Path, params: Map[String, String] = null, whiskUrl: Uri = whiskRestUrl)(
            implicit wp: WskProps): Future[Option[HttpResponse]] = {
        val creds = getBasicHttpCredentials(wp)
        if (params != null)
            pipeline[HttpResponse](Get(whiskUrl.withPath(path).copy(query = Uri.Query(params))), creds = creds)
        else
            pipeline[HttpResponse](Get(whiskUrl.withPath(path)), creds = creds)
    }

    private def getBasicHttpCredentials(wp: WskProps): BasicHttpCredentials = {
        if (wp.authKey.contains(":")) {
            val authKey = wp.authKey.split(":")
            new BasicHttpCredentials(authKey(0), authKey(1))
        }
        else {
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
        annotationFile: Option[String] = None, feed: Option[String] = None,
        web: Option[String] = None): (JsArray, JsArray) = {
        val params = if (parameterFile == None) convertMapIntoKeyValue(parameters) else
            convertStringIntoKeyValue(parameterFile.get)
        val annos = if (annotationFile == None) convertMapIntoKeyValue(annotations, feed, web) else
            convertStringIntoKeyValue(annotationFile.get, feed, web)
        (params, annos)
    }

    def convertStringIntoKeyValue(file: String, feed: Option[String] = None,
          web: Option[String] = None): JsArray = {
        var paramsList = Vector[JsObject]()
        val input = FileUtils.readFileToString(new File(file))
        val in = input.parseJson.convertTo[Map[String, JsValue]]
        convertMapIntoKeyValue(in, feed, web)
    }

    def convertMapIntoKeyValue(params: Map[String, JsValue], feed: Option[String] = None,
          web: Option[String] = None): JsArray = {
        var paramsList = Vector[JsObject]()
        params foreach {case (key, value) => paramsList :+= JsObject("key" -> key.toJson, "value" -> value.toJson)}
        if (feed != None) {
            paramsList :+= JsObject("key" -> "feed".toJson, "value" -> feed.get.toJson)
        }
        if (web != None) {
            paramsList :+= JsObject("key" -> "web-export".toJson, "value" -> web.get.toJson)
        }
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

    def deleteEntity(name: String)(implicit wp: WskProps): RestResult = {
        val (ns, entity) = getNamespaceActionName(name)
        val enName = entityName(name)
        val path = Path(s"$basePath/namespaces/$ns/$entityName/$entity")
        val resp = deleteEntity(path)(wp).futureValue
        if (resp == None) {
            new RestResult(NotFound, new HttpResponse())
        }
        else {
            val r = new RestResult(resp.get.status, resp.get)
            r
        }
    }

    def changeRuleState(ruleName: String, state: String = "active")(implicit wp: WskProps): RestResult = {
        val namespace = wp.namespace
        val enName = entityName(ruleName)
        val path = Path(s"$basePath/namespaces/$namespace/$entityName/$enName")
        val bodyContent = JsObject("status" -> state.toJson)
        val resp = postEntity(path, bodyContent.toString).futureValue
        new RestResult(resp.get.status, resp.get)
    }

    def getNamespaceActionName(name: String)(implicit wp: WskProps): (String, String) = {
        val seq = "/"
        var ns = ""
        var entityName = ""
        if (name.startsWith(seq)) {
            val eN = name.substring(1, name.length())
            if (eN.contains(seq)) {
                ns = eN.split(seq)(0)
                entityName = eN.substring(eN.indexOf(seq) + 1, eN.length())
            }
            else {
                ns = eN
            }
        }
        else {
            ns = wp.namespace
            entityName = name
        }

        (ns, entityName)
    }

    def invoke(
        name: String,
        parameters: Map[String, JsValue] = Map(),
        parameterFile: Option[String] = None,
        blocking: Boolean = false,
        result: Boolean = false,
        namespace: String = null,
        expectedExitCode: Int = Accepted.intValue)(
            implicit wp: WskProps): RestResult = {
        val (ns, actName) = this.getNamespaceActionName(name)
        val path = Path(s"$basePath/namespaces/$ns/actions/$actName")
        var paramMap = Map("blocking" -> blocking.toString, "result" -> result.toString)
        var input = ""
        if (parameterFile != None) {
            input = FileUtils.readFileToString(new File(parameterFile.get))
        }
        else {
            input = parameters.toJson.toString()
        }
        val resp = invokeAction(path, input, paramMap).futureValue.get
        val r = new RestResult(resp.status.intValue, resp)
        if (expectedExitCode != DONTCARE_EXIT)
            r.statusCode.intValue shouldBe expectedExitCode
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
        wskadmin.cli(Seq("user", "list", namespace, "--pick", pick.toString))
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
        val rr = TestUtils.runCmd(DONTCARE_EXIT, workingDir, TestUtils.logger, sys.env ++ env, stdinFile.getOrElse(null), args ++ params: _*)

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
    def getField(obj: JsObject, key: String): String = {
        val list = obj.getFields(key)
        if (list.size == 0) {
            ""
        }
        else {
            Try {
                list(0).convertTo[String]
            } getOrElse
                list(0).convertTo[Int].toString
        }
    }

    def getFieldJsObject(obj: JsObject, key: String): JsObject = {
        val list = obj.getFields(key)
        if (list.size == 0) JsObject() else list(0).toJson.asJsObject
    }
    
    def getFieldJsValue(obj: JsObject, key: String): JsValue = {
        val list = obj.getFields(key)
        if (list.size == 0) JsObject() else list(0).toJson
    }

    def getFieldListJsObject(obj: JsObject, key: String): Vector[JsObject] = {
        val list = obj.getFields(key)
        if (list.size == 0) Vector(JsObject()) else list(0).toJson.convertTo[Vector[JsObject]]
    }
}

class RestResult(var statusCode: StatusCode, var resp: HttpResponse = new HttpResponse())
    extends RunResult(if (statusCode.intValue < 400) statusCode.intValue else statusCode.intValue - 256, resp.entity.data.asString, ""){

    override def toString = s"Status code = ${statusCode.intValue}.\n Response data = $respData."
    def headers: List[HttpHeader] = resp.headers
    def respData: String = resp.entity.data.asString
    def respBody: JsObject = resp.entity.data.asString.parseJson.asJsObject

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
        resp.entity.data.asString.parseJson.convertTo[Vector[JsObject]]
    }

    def getBodyListString(): Vector[String] = {
        resp.entity.data.asString.parseJson.convertTo[Vector[String]]
    }
}

case class ApiAction(
    name: String,
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

