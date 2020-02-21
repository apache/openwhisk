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

package org.apache.openwhisk.core.database

import java.io.File
import java.nio.file.Paths
import java.time.Instant

import akka.actor.ActorSystem
import akka.http.scaladsl.model.HttpRequest
import akka.stream.ActorMaterializer
import akka.testkit.TestKit
import common.StreamLogging
import org.junit.runner.RunWith
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import spray.json.DefaultJsonProtocol._
import spray.json._
import org.apache.openwhisk.common.{Logging, TransactionId, WhiskInstants}
import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.entity.size.SizeInt
import pureconfig._
import pureconfig.generic.auto._

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.io.Source

@RunWith(classOf[JUnitRunner])
class ArtifactWithFileStorageActivationStoreTests()
    extends TestKit(ActorSystem("ArtifactWithFileStorageActivationStoreTests"))
    with FlatSpecLike
    with Matchers
    with BeforeAndAfterAll
    with ScalaFutures
    with StreamLogging
    with WhiskInstants {

  implicit val transid: TransactionId = TransactionId.testing
  implicit val notifier: Option[CacheChangeNotification] = None

  private val materializer = ActorMaterializer()
  private val uuid = UUID()
  private val subject = Subject()
  private val user =
    Identity(subject, Namespace(EntityName(subject.asString), uuid), BasicAuthenticationAuthKey(uuid, Secret()))
  private val context = UserContext(user, HttpRequest())

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }

  private def await[T](awaitable: Future[T], timeout: FiniteDuration = 10.seconds) = Await.result(awaitable, timeout)

  def responsePermutations = {
    val message = JsObject("result key" -> JsString("result value"))
    Seq(
      ActivationResponse.success(None),
      ActivationResponse.success(Some(message)),
      ActivationResponse.applicationError(message),
      ActivationResponse.whiskError(message))
  }

  def logPermutations = {
    Seq(
      ActivationLogs(),
      ActivationLogs(Vector("2018-03-05T02:10:38.196689520Z stdout: single log line")),
      ActivationLogs(
        Vector(
          "2018-03-05T02:10:38.196689522Z stdout: first log line of multiple lines",
          "2018-03-05T02:10:38.196754258Z stdout: second log line of multiple lines")))
  }

  def expectedFileContent(activation: WhiskActivation,
                          includeResult: Boolean,
                          additionalFieldsForLogs: Seq[JsField] = Seq(),
                          additionalFieldsForActivation: Seq[JsField] = Seq()) = {
    val expectedLogs = activation.logs.logs.map { log =>
      {
        JsObject(
          Seq(
            "type" -> "user_log".toJson,
            "message" -> log.toJson,
            "activationId" -> activation.activationId.toJson,
            "namespace" -> activation.namespace.asString.toJson,
            "namespaceId" -> user.namespace.uuid.toJson)
            ++ additionalFieldsForLogs: _*)
      }
    }
    val expectedResult = if (includeResult) {
      JsString(activation.response.result.getOrElse(JsNull).compactPrint)
    } else {
      JsString(s"Activation record '${activation.activationId}' for entity '${activation.name}'")
    }
    val expectedActivation = JsObject(
      Seq(
        "type" -> "activation_record".toJson,
        "duration" -> activation.duration.toJson,
        "name" -> activation.name.toJson,
        "subject" -> activation.subject.toJson,
        "waitTime" -> activation.annotations.get("waitTime").toJson.toJson,
        "activationId" -> activation.activationId.toJson,
        "namespaceId" -> user.namespace.uuid.toJson,
        "publish" -> activation.publish.toJson,
        "version" -> activation.version.toJson,
        "response" -> activation.response.withoutResult.toExtendedJson,
        "end" -> activation.end.toEpochMilli.toJson,
        "message" -> expectedResult,
        "kind" -> activation.annotations.get("kind").toJson.toJson,
        "start" -> activation.start.toEpochMilli.toJson,
        "limits" -> activation.annotations.get("limits").toJson.toJson,
        "initTime" -> activation.annotations.get("initTime").toJson,
        "namespace" -> activation.namespace.toJson)
        ++ additionalFieldsForActivation: _*)

    expectedLogs ++ Seq(expectedActivation)
  }

  it should "store activations in artifact store and to file without result" in {
    val config = ArtifactWithFileStorageActivationStoreConfig("userlogs", "logs", "namespaceId", false)
    val activationStore = new ArtifactWithFileStorageActivationStore(system, materializer, logging, config)
    val logDir = new File(new File(".").getCanonicalPath, config.logPath)

    try {
      logDir.mkdir

      val activations = responsePermutations.map { response =>
        logPermutations.map { logs =>
          val activation = WhiskActivation(
            namespace = EntityPath(subject.asString),
            name = EntityName("name"),
            subject = subject,
            activationId = ActivationId.generate(),
            start = Instant.now.inMills,
            end = Instant.now.inMills,
            response = response,
            logs = logs,
            duration = Some(101L),
            annotations = Parameters("kind", "nodejs:10") ++ Parameters(
              "limits",
              ActionLimits(TimeLimit(60.second), MemoryLimit(256.MB), LogLimit(10.MB)).toJson) ++
              Parameters("waitTime", 16.toJson) ++
              Parameters("initTime", 44.toJson))
          val docInfo = await(activationStore.store(activation, context))
          val fullyQualifiedActivationId = ActivationId(docInfo.id.asString)

          await(activationStore.get(fullyQualifiedActivationId, context)) shouldBe activation
          await(activationStore.delete(fullyQualifiedActivationId, context))
          activation
        }
      }.flatten

      Source
        .fromFile(activationStore.getLogFile.toFile.getAbsoluteFile)
        .getLines
        .toList
        .map(_.parseJson)
        .toJson
        .convertTo[JsArray] shouldBe activations
        .map(a => expectedFileContent(a, false))
        .flatten
        .toJson
        .convertTo[JsArray]
    } finally {
      activationStore.getLogFile.toFile.getAbsoluteFile.delete
      logDir.delete
    }
  }

  it should "store activations in artifact store and to file with result" in {
    val config = ArtifactWithFileStorageActivationStoreConfig("userlogs", "logs", "namespaceId", true)
    val activationStore = new ArtifactWithFileStorageActivationStore(system, materializer, logging, config)
    val logDir = new File(new File(".").getCanonicalPath, config.logPath)

    try {
      logDir.mkdir

      val activations = responsePermutations.map { response =>
        logPermutations.map { logs =>
          val activation = WhiskActivation(
            namespace = EntityPath(subject.asString),
            name = EntityName("name"),
            subject = subject,
            activationId = ActivationId.generate(),
            start = Instant.now.inMills,
            end = Instant.now.inMills,
            response = response,
            logs = logs,
            duration = Some(101L),
            annotations = Parameters("kind", "nodejs:10") ++ Parameters(
              "limits",
              ActionLimits(TimeLimit(60.second), MemoryLimit(256.MB), LogLimit(10.MB)).toJson) ++
              Parameters("waitTime", 16.toJson) ++
              Parameters("initTime", 44.toJson))
          val docInfo = await(activationStore.store(activation, context))
          val fullyQualifiedActivationId = ActivationId(docInfo.id.asString)

          await(activationStore.get(fullyQualifiedActivationId, context)) shouldBe activation
          await(activationStore.delete(fullyQualifiedActivationId, context))
          activation
        }
      }.flatten

      Source
        .fromFile(activationStore.getLogFile.toFile.getAbsoluteFile)
        .getLines
        .toList
        .map(_.parseJson)
        .toJson
        .convertTo[JsArray] shouldBe activations
        .map(a => expectedFileContent(a, true))
        .flatten
        .toJson
        .convertTo[JsArray]
    } finally {
      activationStore.getLogFile.toFile.getAbsoluteFile.delete
      logDir.delete
    }
  }

  for (includeResult <- Seq(false, true)) {

    it should "test activationToFileExtended: store activations in artifact store and in file " +
      (if (includeResult) "with" else "without") + " result" in {

      // used in ArtifactWithFileStorageActivationStoreExtended and for test data
      val additionalFieldsForLogs = Map("field1" -> JsString("value1"))
      val additionalFieldsForActivation = Map("field2" -> JsString("value2"))

      // START - example of a simple ArtifactActivationStore implementation that uses activationToFileExtended

      case class ArtifactWithFileStorageActivationStoreConfigExtendedTest(logFilePrefix: String,
                                                                          logPath: String,
                                                                          userIdField: String,
                                                                          writeResultToFile: Boolean)

      class ArtifactWithFileStorageActivationStoreExtendedTest(
        actorSystem: ActorSystem,
        actorMaterializer: ActorMaterializer,
        logging: Logging,
        config: ArtifactWithFileStorageActivationStoreConfigExtendedTest =
          loadConfigOrThrow[ArtifactWithFileStorageActivationStoreConfigExtendedTest](
            ConfigKeys.activationStoreWithFileStorage))
          extends ArtifactActivationStore(actorSystem, actorMaterializer, logging) {

        private val activationFileStorage =
          new ActivationFileStorage(
            config.logFilePrefix,
            Paths.get(config.logPath),
            config.writeResultToFile,
            actorMaterializer,
            logging)

        def getLogFile = activationFileStorage.getLogFile

        def shallResultBeIncluded: Boolean = includeResult
        // other simple example for the flag: (includeResult && !activation.response.isSuccess)

        override def store(activation: WhiskActivation, context: UserContext)(
          implicit transid: TransactionId,
          notifier: Option[CacheChangeNotification]): Future[DocInfo] = {

          val additionalFields = Map(config.userIdField -> context.user.namespace.uuid.toJson)

          activationFileStorage.activationToFileExtended(
            activation,
            context,
            additionalFields ++ additionalFieldsForLogs ++ Map("namespace" -> JsString(subject.asString)),
            additionalFields ++ additionalFieldsForActivation,
            shallResultBeIncluded)

          super.store(activation, context)
        }

      }

      // END - example of a simple ArtifactActivationStore implementation that uses activationToFileExtended

      // writeResultToFile is defined with the inverted value of includeResult and should be overriden by the test
      val config =
        ArtifactWithFileStorageActivationStoreConfigExtendedTest("userlogs", "logs", "namespaceId", !includeResult)

      val activationStore =
        new ArtifactWithFileStorageActivationStoreExtendedTest(system, materializer, logging, config)
      val logDir = new File(new File(".").getCanonicalPath, config.logPath)

      try {
        logDir.mkdir

        val activations = responsePermutations.flatMap { response =>
          logPermutations.map { logs =>
            val activation = WhiskActivation(
              namespace = EntityPath(subject.asString),
              name = EntityName("name"),
              subject = subject,
              activationId = ActivationId.generate(),
              start = Instant.now.inMills,
              end = Instant.now.inMills,
              response = response,
              logs = logs,
              duration = Some(101L),
              annotations = Parameters("kind", "nodejs:10") ++ Parameters(
                "limits",
                ActionLimits(TimeLimit(60.second), MemoryLimit(256.MB), LogLimit(10.MB)).toJson) ++
                Parameters("waitTime", 16.toJson) ++
                Parameters("initTime", 44.toJson))
            val docInfo = await(activationStore.store(activation, context))
            val fullyQualifiedActivationId = ActivationId(docInfo.id.asString)

            await(activationStore.get(fullyQualifiedActivationId, context)) shouldBe activation
            await(activationStore.delete(fullyQualifiedActivationId, context))
            activation
          }
        }

        Source
          .fromFile(activationStore.getLogFile.toFile.getAbsoluteFile)
          .getLines
          .toList
          .map(_.parseJson)
          .toJson
          .convertTo[JsArray] shouldBe activations
          .flatMap(a =>
            expectedFileContent(a, includeResult, additionalFieldsForLogs.toSeq, additionalFieldsForActivation.toSeq))
          .toJson
          .convertTo[JsArray]
      } finally {
        activationStore.getLogFile.toFile.getAbsoluteFile.delete
        logDir.delete
      }
    }
  }

}
