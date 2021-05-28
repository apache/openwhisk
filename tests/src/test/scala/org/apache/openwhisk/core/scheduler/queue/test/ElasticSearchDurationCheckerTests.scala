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

package org.apache.openwhisk.core.scheduler.queue.test

import com.sksamuel.elastic4s.http.ElasticDsl._
import com.sksamuel.elastic4s.http.{ElasticClient, ElasticProperties, NoOpRequestConfigCallback}
import common._
import common.rest.WskRestOperations
import org.apache.http.auth.{AuthScope, UsernamePasswordCredentials}
import org.apache.http.impl.client.BasicCredentialsProvider
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.core.database.elasticsearch.ElasticSearchActivationStoreConfig
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.entity.size._
import org.apache.openwhisk.core.entity.test.ExecHelpers
import org.apache.openwhisk.core.scheduler.queue.{DurationCheckResult, ElasticSearchDurationChecker}
import org.elasticsearch.client.RestClientBuilder.HttpClientConfigCallback
import org.junit.runner.RunWith
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, FlatSpec, Matchers}
import org.scalatestplus.junit.JUnitRunner
import pureconfig.generic.auto._
import pureconfig.loadConfigOrThrow
import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.language.postfixOps

import scala.collection.mutable
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._

/**
 * This test will try to fetch the average duration from activation documents. This class guarantee the minimum compatibility.
 * In case there are any updates in the activation document, it will catch the difference between the expected and the real.
 */
@RunWith(classOf[JUnitRunner])
class ElasticSearchDurationCheckerTests
    extends FlatSpec
    with Matchers
    with ScalaFutures
    with WskTestHelpers
    with StreamLogging
    with ExecHelpers
    with BeforeAndAfterAll
    with BeforeAndAfter {

  private val namespace = "durationCheckNamespace"
  val wskadmin: RunCliCmd = new RunCliCmd {
    override def baseCommand: mutable.Buffer[String] = WskAdmin.baseCommand
  }
  implicit val ec: ExecutionContextExecutor = actorSystem.dispatcher
  implicit val timeoutConfig: PatienceConfig = PatienceConfig(5 seconds, 15 milliseconds)

  private val auth = BasicAuthenticationAuthKey()
  implicit val wskprops: WskProps = WskProps(authKey = auth.compact, namespace = namespace)
  implicit val transid: TransactionId = TransactionId.testing

  val wsk = new WskRestOperations
  val elasticSearchConfig: ElasticSearchActivationStoreConfig =
    loadConfigOrThrow[ElasticSearchActivationStoreConfig](ConfigKeys.elasticSearchActivationStore)

  val testIndex: String = generateIndex(namespace)
  val concurrency = 1
  val actionMem: ByteSize = 256.MB
  val defaultDurationCheckWindow = 5.seconds

  private val httpClientCallback = new HttpClientConfigCallback {
    override def customizeHttpClient(httpClientBuilder: HttpAsyncClientBuilder): HttpAsyncClientBuilder = {
      val provider = new BasicCredentialsProvider
      provider.setCredentials(
        AuthScope.ANY,
        new UsernamePasswordCredentials(elasticSearchConfig.username, elasticSearchConfig.password))
      httpClientBuilder.setDefaultCredentialsProvider(provider)
    }
  }

  private val client =
    ElasticClient(
      ElasticProperties(s"${elasticSearchConfig.protocol}://${elasticSearchConfig.hosts}"),
      NoOpRequestConfigCallback,
      httpClientCallback)

  private val elasticSearchDurationChecker = new ElasticSearchDurationChecker(client, defaultDurationCheckWindow)

  override def beforeAll(): Unit = {
    val res = wskadmin.cli(Seq("user", "create", namespace, "-u", auth.compact))
    res.exitCode shouldBe 0

    println(s"namespace: $namespace, auth: ${auth.compact}")
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    client.execute {
      deleteIndex(testIndex)
    }
    wskadmin.cli(Seq("user", "delete", namespace))
    logLines.foreach(println)
    super.afterAll()
  }

  behavior of "ElasticSearchDurationChecker"

  it should "fetch the proper duration from ES" in withAssetCleaner(wskprops) { (_, assetHelper) =>
    val actionName = "avgDuration"
    val dummyActionName = "dummyAction"

    var totalDuration = 0L
    val count = 3

    assetHelper.withCleaner(wsk.action, actionName) { (action, _) =>
      action.create(actionName, Some(TestUtils.getTestActionFilename("hello.js")))
    }

    assetHelper.withCleaner(wsk.action, dummyActionName) { (action, _) =>
      action.create(dummyActionName, Some(TestUtils.getTestActionFilename("hello.js")))
    }

    val actionMetaData =
      WhiskActionMetaData(
        EntityPath(namespace),
        EntityName(actionName),
        jsMetaData(Some("jsMain"), binary = false),
        limits = actionLimits(actionMem, concurrency))

    val run1 = wsk.action.invoke(actionName, Map())
    withActivation(wsk.activation, run1) { activation =>
      activation.response.status shouldBe "success"
    }
    // wait for 1s
    Thread.sleep(1000)

    val start = Instant.now()
    val run2 = wsk.action.invoke(dummyActionName, Map())
    withActivation(wsk.activation, run2) { activation =>
      activation.response.status shouldBe "success"
    }

    1 to count foreach { _ =>
      val run = wsk.action.invoke(actionName, Map())
      withActivation(wsk.activation, run) { activation =>
        activation.response.status shouldBe "success"
        totalDuration += activation.duration
      }
    }
    val end = Instant.now()
    val timeWindow = math.ceil(ChronoUnit.MILLIS.between(start, end) / 1000.0).seconds
    val durationChecker = new ElasticSearchDurationChecker(client, timeWindow)

    // it should aggregate the recent activations in 5 seconds
    val durationCheckResult: DurationCheckResult =
      durationChecker.checkAverageDuration(namespace, actionMetaData)(res => res).futureValue

    /**
     * Expected sample data
      {
          "_shards": {
              "failed": 0,
              "skipped": 0,
              "successful": 5,
              "total": 5
          },
          "aggregations": {
              "filterAggregation": {
                  "averageAggregation": {
                      "value": 14
                  },
                  "doc_count": 3
              }
          },
          "hits": {
              "hits": [],
              "max_score": 0,
              "total": 3
          },
          "timed_out": false,
          "took": 2
      }
     */
    truncateDouble(durationCheckResult.averageDuration.getOrElse(0.0)) shouldBe truncateDouble(
      totalDuration.toDouble / count.toDouble)
    durationCheckResult.hitCount shouldBe count
  }

  it should "fetch proper average duration for a package action" in withAssetCleaner(wskprops) { (_, assetHelper) =>
    val packageName = "samplePackage"
    val actionName = "packageAction"
    val fqn = s"$namespace/$packageName/$actionName"

    val actionMetaData =
      WhiskActionMetaData(
        EntityPath(s"$namespace/$packageName"),
        EntityName(actionName),
        jsMetaData(Some("jsMain"), binary = false),
        limits = actionLimits(actionMem, concurrency))

    var totalDuration = 0L
    val count = 3

    assetHelper.withCleaner(wsk.pkg, packageName) { (pkg, _) =>
      pkg.create(packageName)
    }

    assetHelper.withCleaner(wsk.action, fqn) { (action, _) =>
      action.create(fqn, Some(TestUtils.getTestActionFilename("hello.js")))
    }

    1 to count foreach { _ =>
      val run = wsk.action.invoke(fqn, Map())
      withActivation(wsk.activation, run) { activation =>
        activation.response.status shouldBe "success"
      }
    }
    // wait for 1s
    Thread.sleep(1000)

    val start = Instant.now()
    1 to count foreach { _ =>
      val run = wsk.action.invoke(fqn, Map())
      withActivation(wsk.activation, run) { activation =>
        activation.response.status shouldBe "success"
        totalDuration += activation.duration
      }
    }
    val end = Instant.now()
    val timeWindow = math.ceil(ChronoUnit.MILLIS.between(start, end) / 1000.0).seconds
    val durationChecker = new ElasticSearchDurationChecker(client, timeWindow)
    val durationCheckResult: DurationCheckResult =
      durationChecker.checkAverageDuration(namespace, actionMetaData)(res => res).futureValue

    /**
     * Expected sample data
      {
          "_shards": {
              "failed": 0,
              "skipped": 0,
              "successful": 5,
              "total": 5
          },
          "aggregations": {
              "filterAggregation": {
                  "averageAggregation": {
                      "value": 13
                  },
                  "doc_count": 3
              }
          },
          "hits": {
              "hits": [],
              "max_score": 0,
              "total": 6
          },
          "timed_out": false,
          "took": 0
      }
     */
    truncateDouble(durationCheckResult.averageDuration.getOrElse(0.0)) shouldBe truncateDouble(
      totalDuration.toDouble / count.toDouble)
    durationCheckResult.hitCount shouldBe count
  }

  it should "fetch the duration for binding action" in withAssetCleaner(wskprops) { (_, assetHelper) =>
    val packageName = "testPackage"
    val actionName = "testAction"
    val originalFQN = s"$namespace/$packageName/$actionName"
    val boundPackageName = "boundPackage"

    val actionMetaData =
      WhiskActionMetaData(
        EntityPath(s"$namespace/$boundPackageName"),
        EntityName(actionName),
        jsMetaData(Some("jsMain"), binary = false),
        limits = actionLimits(actionMem, concurrency),
        binding = Some(EntityPath(s"$namespace/$packageName")))

    var totalDuration = 0L
    val count = 3

    assetHelper.withCleaner(wsk.pkg, packageName) { (pkg, _) =>
      pkg.create(packageName, shared = Some(true))
    }

    assetHelper.withCleaner(wsk.action, originalFQN) { (action, _) =>
      action.create(originalFQN, Some(TestUtils.getTestActionFilename("hello.js")))
    }

    assetHelper.withCleaner(wsk.pkg, boundPackageName) { (pkg, _) =>
      pkg.bind(packageName, boundPackageName)
    }

    1 to count foreach { _ =>
      val run = wsk.action.invoke(s"$boundPackageName/$actionName", Map())
      withActivation(wsk.activation, run) { activation =>
        activation.response.status shouldBe "success"
      }
    }
    // wait for 1s
    Thread.sleep(1000)

    val start = Instant.now()
    1 to count foreach { _ =>
      val run = wsk.action.invoke(s"$boundPackageName/$actionName", Map())
      withActivation(wsk.activation, run) { activation =>
        activation.response.status shouldBe "success"
        totalDuration += activation.duration
      }
    }
    val end = Instant.now()
    val timeWindow = math.ceil(ChronoUnit.MILLIS.between(start, end) / 1000.0).seconds
    val durationChecker = new ElasticSearchDurationChecker(client, timeWindow)
    val durationCheckResult: DurationCheckResult =
      durationChecker.checkAverageDuration(namespace, actionMetaData)(res => res).futureValue

    /**
     * Expected sample data
      {
          "_shards": {
              "failed": 0,
              "skipped": 0,
              "successful": 5,
              "total": 5
          },
          "aggregations": {
              "averageAggregation": {
                  "value": 14
              }
          },
          "hits": {
              "hits": [],
              "max_score": 0,
              "total": 3
          },
          "timed_out": false,
          "took": 0
      }
     */
    truncateDouble(durationCheckResult.averageDuration.getOrElse(0.0)) shouldBe truncateDouble(
      totalDuration.toDouble / count.toDouble)
    durationCheckResult.hitCount shouldBe count
  }

  it should "return nothing properly if there is no activation yet" in withAssetCleaner(wskprops) { (_, _) =>
    val actionName = "noneAction"

    val actionMetaData =
      WhiskActionMetaData(
        EntityPath(s"$namespace"),
        EntityName(actionName),
        jsMetaData(Some("jsMain"), binary = false),
        limits = actionLimits(actionMem, concurrency))

    val durationCheckResult: DurationCheckResult =
      elasticSearchDurationChecker.checkAverageDuration(namespace, actionMetaData)(res => res).futureValue

    durationCheckResult.averageDuration shouldBe None
    durationCheckResult.hitCount shouldBe 0
  }

  it should "return nothing properly if there is no activation for binding action yet" in withAssetCleaner(wskprops) {
    (_, _) =>
      val packageName = "testPackage2"
      val actionName = "noneAction"
      val boundPackageName = "boundPackage2"

      val actionMetaData =
        WhiskActionMetaData(
          EntityPath(s"$namespace/$boundPackageName"),
          EntityName(actionName),
          jsMetaData(Some("jsMain"), false),
          limits = actionLimits(actionMem, concurrency),
          binding = Some(EntityPath(s"${namespace}/${packageName}")))

      val durationCheckResult: DurationCheckResult =
        elasticSearchDurationChecker.checkAverageDuration(namespace, actionMetaData)(res => res).futureValue

      durationCheckResult.averageDuration shouldBe None
      durationCheckResult.hitCount shouldBe 0
  }

  private def truncateDouble(number: Double, scale: Int = 2) = {
    BigDecimal(number).setScale(scale, BigDecimal.RoundingMode.HALF_UP).toDouble
  }

  private def generateIndex(namespace: String): String = {
    elasticSearchConfig.indexPattern.dropWhile(_ == '/') format namespace.toLowerCase
  }
}
