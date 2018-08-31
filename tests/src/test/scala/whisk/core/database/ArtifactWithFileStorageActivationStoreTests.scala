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

package whisk.core.database

import java.time.Instant
import java.io.File

import scala.concurrent.Future
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.testkit.TestKit
import akka.http.scaladsl.model.HttpRequest
import common.StreamLogging
import org.junit.runner.RunWith
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpecLike, Matchers}
import spray.json._
import spray.json.DefaultJsonProtocol._
import whisk.common.TransactionId
import whisk.core.entity._
import whisk.core.entity.size.SizeInt

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.io.Source

@RunWith(classOf[JUnitRunner])
class ArtifactWithFileStorageActivationStoreTests()
    extends TestKit(ActorSystem("ArtifactWithFileStorageActivationStoreTests"))
    with FlatSpecLike
    with Matchers
    with ScalaFutures
    with StreamLogging {

  implicit val transid: TransactionId = TransactionId.testing
  implicit val notifier: Option[CacheChangeNotification] = None

  private val materializer = ActorMaterializer()
  private val uuid = UUID()
  private val subject = Subject()
  private val user =
    Identity(subject, Namespace(EntityName("testSpace"), uuid), BasicAuthenticationAuthKey(uuid, Secret()), Set())
  private val context = UserContext(user, HttpRequest())
  private val config = ArtifactWithFileStorageActivationStoreConfig("userlogs", "logs", "namespaceId", true, true, true)

  private def await[T](awaitable: Future[T], timeout: FiniteDuration = 10.seconds) = Await.result(awaitable, timeout)

  it should "store an activation in artifact store and to file" in {
    val activation = WhiskActivation(
      namespace = EntityPath(subject.asString),
      name = EntityName("name"),
      subject = subject,
      activationId = ActivationId.generate(),
      start = Instant.now,
      end = Instant.now,
      response = ActivationResponse.success(Some(JsObject("result key" -> JsString("result value")))),
      logs = ActivationLogs(
        Vector(
          "2018-03-05T02:10:38.196689522Z stdout: first log line",
          "2018-03-05T02:10:38.196754258Z stdout: second log line")),
      duration = Some(101L),
      annotations = Parameters("kind", "nodejs:6") ++ Parameters(
        "limits",
        ActionLimits(TimeLimit(60.second), MemoryLimit(256.MB), LogLimit(10.MB)).toJson) ++
        Parameters("waitTime", 16.toJson) ++
        Parameters("initTime", 44.toJson))
    val expectedFileContent = JsArray(
      Vector(
        JsObject(
          "message" -> activation.logs.logs(0).toJson,
          "activationId" -> activation.activationId.toJson,
          "namespaceId" -> user.namespace.uuid.toJson),
        JsObject(
          "message" -> activation.logs.logs(1).toJson,
          "activationId" -> activation.activationId.toJson,
          "namespaceId" -> user.namespace.uuid.toJson),
        JsObject(
          "duration" -> activation.duration.toJson,
          "name" -> activation.name.toJson,
          "subject" -> activation.subject.toJson,
          "waitTime" -> activation.annotations.get("waitTime").toJson.toJson,
          "activationId" -> activation.activationId.toJson,
          "namespaceId" -> user.namespace.uuid.toJson,
          "publish" -> activation.publish.toJson,
          "version" -> activation.version.toJson,
          "response" -> JsObject("success" -> true.toJson, "status" -> "success".toJson),
          "end" -> activation.end.toEpochMilli.toJson,
          "message" -> s"Activation record '${activation.activationId}' for entity '${activation.name}'".toJson,
          "kind" -> activation.annotations.get("kind").toJson.toJson,
          "start" -> activation.start.toEpochMilli.toJson,
          "limits" -> activation.annotations.get("limits").toJson.toJson,
          "initTime" -> activation.annotations.get("initTime").toJson,
          "namespace" -> activation.namespace.toJson)))

    val logDir = new File(new File(".").getCanonicalPath, config.logPath)
    logDir.mkdir
    val activationStore = new ArtifactWithFileStorageActivationStore(system, materializer, logging, config)
    val docInfo = await(activationStore.store(activation, context))
    val fullyQualifiedActivationId = ActivationId(docInfo.id.asString)
    val logFile: Array[File] = logDir.listFiles.filter(_.isFile)

    try {
      activation shouldBe await(activationStore.get(fullyQualifiedActivationId, context))
      Source
        .fromFile(logFile.head)
        .getLines
        .toList
        .map(_.parseJson)
        .toJson
        .convertTo[JsArray] shouldBe expectedFileContent
    } finally {
      await(activationStore.delete(fullyQualifiedActivationId, context))
      logFile.head.delete
      logDir.delete
    }
  }

}
