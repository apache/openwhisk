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

package org.apache.openwhisk.core.database.persister

import akka.stream.ActorMaterializer
import common.rest.WskRestOperations
import common.{ActivationResult, FreePortFinder, TestHelpers, TestUtils, WskActorSystem, WskProps, WskTestHelpers}
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.database.ActivationStore
import org.apache.openwhisk.core.entity.{DocInfo, WhiskActivation}
import org.apache.openwhisk.standalone.StandaloneServerFixture
import org.apache.openwhisk.utils.retry
import org.junit.runner.RunWith
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.concurrent.duration._
//TODO document how to run against an existing standalone server

@RunWith(classOf[JUnitRunner])
class ActivationPersisterITTests
    extends TestHelpers
    with WskTestHelpers
    with WskActorSystem
    with ScalaFutures
    with StandaloneServerFixture
    with PersisterServiceFixture {
  private implicit val materializer: ActorMaterializer = ActorMaterializer()
  protected override val kafkaPort = sys.props.get("whisk.kafka.port").map(_.toInt).getOrElse(FreePortFinder.freePort())

  protected override val customConfig = Some(
    """
     |include classpath("standalone.conf")
     |whisk {
     |  spi {
     |    ActivationStoreProvider = org.apache.openwhisk.core.database.KafkaActivationStoreProvider
     |  }
     |  invoker {
     |    activations {
     |      activation-store-enabled = false
     |      activations-topic = "completed-others"
     |    }
     |  }
     |  kafka-activation-store {
     |    activations-topic = "completed-others"
     |    store-in-primary = false
     |  }
     |}""".stripMargin)

  protected val (activationStore, artifactStore) = createMemoryActivationStore()

  override protected def extraArgs: Seq[String] =
    Seq("--kafka", "--kafka-port", kafkaPort.toString)

  override protected def createActivationConsumer(persisterConfig: PersisterConfig, activationStore: ActivationStore) =
    ActivationPersisterService.start(persisterConfig, activationStore)

  private implicit val wskprops = WskProps().copy(apihost = serverUrl, namespace = "guest")

  private val wsk = new WskRestOperations

  behavior of "Activation Persister"

  it should "receive response for blocking actions" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val name = "basicInvoke"
    assetHelper.withCleaner(wsk.action, name) { (action, _) =>
      action.create(name, Some(TestUtils.getTestActionFilename("wc.js")))
    }

    val runResult = wsk.action
      .invoke(name, Map("payload" -> "one two three".toJson), blocking = true)
    val ar = runResult.stdout.parseJson.convertTo[ActivationResult]
    ar.response.result.get shouldBe JsObject("count" -> JsNumber(3))

    assertActivationState(ar.activationId)
  }

  it should "receive response for conductor actions" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    //This test is meant to check the integration within controller where activation records for conductor/seq
    // etc are saved
    val conductor = "conductor" // conductor action
    assetHelper.withCleaner(wsk.action, conductor) { (action, _) =>
      action.create(
        conductor,
        Some(TestUtils.getTestActionFilename("conductor.js")),
        annotations = Map("conductor" -> true.toJson))
    }

    val step = "step" // step action
    assetHelper.withCleaner(wsk.action, step) { (action, _) =>
      action.create(step, Some(TestUtils.getTestActionFilename("step.js")))
    }

    // invoke nested conductor with single step
    // 1 - Invoke `conductor` -> Returns `step` to be invoked as action=step (secondary activation)
    // 2 - Invoke `step` -> Returns the final result
    // 3 - Invoke `conductor` -> Returns final result (secondary activation)
    val run = wsk.action.invoke(conductor, Map("action" -> step.toJson, "n" -> 1.toJson), blocking = true)

    val ar = run.stdout.parseJson.convertTo[ActivationResult]
    ar.logs.get.size shouldBe 3

    val activationIds = Seq(ar.activationId) ++ ar.logs.get

    //Check all activationIds are received by persister service
    //The primaryId `ar.activationId` is the synthetic activation record stored by Controller
    activationIds.foreach(waitForActivationInPS)
  }

  private def assertActivationState(activationId: String): Unit = {
    //Activations should not be present in standalone openwhisk ArtifactStore as it uses a different MemoryStore
    //instance and by design we disable storing the activation locally
    val activation = wsk.activation.waitForActivation(activationId, totalWait = 5.seconds)
    activation shouldBe 'Left

    //Activation should eventually show up in the store used by Persister Service
    val psActivation = waitForActivationInPS(activationId)
    psActivation.activationId.asString shouldBe activationId
  }

  private def waitForActivationInPS(activationId: String) = {
    implicit val tid: TransactionId = TransactionId.testing
    retry(
      {
        val result = artifactStore.get[WhiskActivation](DocInfo(s"${wskprops.namespace}/$activationId"))
        result.futureValue
      },
      30,
      waitBeforeRetry = Some(1.second),
      retryMessage = Some(s"Waiting for activation $activationId from Persister service"))
  }

  private def logAndReset() = {
    println(logLines.mkString("\n"))
    stream.reset()
  }
}
