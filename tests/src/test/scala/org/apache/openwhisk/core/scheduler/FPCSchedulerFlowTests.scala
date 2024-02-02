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

package org.apache.openwhisk.core.scheduler

import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.testkit.{TestKit, TestProbe}
import com.ibm.etcd.api.Event.EventType
import com.ibm.etcd.client.kv.WatchUpdate
import common.rest.WskRestOperations
import common.{ActivationResponse => _, _}
import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.core.containerpool.ContainerProxyTimeoutConfig
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.etcd.EtcdKV.ContainerKeys.{
  containerPrefix,
  inProgressPrefix,
  namespacePrefix,
  warmedPrefix
}
import org.apache.openwhisk.core.etcd.EtcdKV.{QueueKeys, ThrottlingKeys}
import org.apache.openwhisk.core.etcd.{EtcdClient, EtcdConfig}
import org.apache.openwhisk.core.scheduler.queue.QueueConfig
import org.apache.openwhisk.http.Messages
import org.apache.openwhisk.utils.retry
import org.junit.runner.RunWith
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike}
import pureconfig.loadConfigOrThrow
import spray.json.DefaultJsonProtocol._
import spray.json._
import pureconfig.generic.auto._

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.language.postfixOps
import scala.util.Random
import scala.util.control.Breaks._

@RunWith(classOf[JUnitRunner])
class FPCSchedulerFlowTests
    extends TestKit(ActorSystem("SchedulerFlow"))
    with FlatSpecLike
    with BeforeAndAfterAll
    with WskTestHelpers
    with ScalaFutures {
  private implicit val ece: ExecutionContextExecutor = system.dispatcher
  private val wsk = new WskRestOperations
  private val defaultAction: Some[String] = Some(TestUtils.getTestActionFilename("hello.js"))
  private val namespace = "schedulerFlowNamespace"

  private val queueConfig = loadConfigOrThrow[QueueConfig](ConfigKeys.schedulerQueue)
  private val containerConfig = loadConfigOrThrow[ContainerProxyTimeoutConfig](ConfigKeys.containerProxyTimeouts)
  private val idleGrace = queueConfig.idleGrace
  private val flushGrace = queueConfig.flushGrace
  private val stopGrace = queueConfig.stopGrace
  private val pauseGrace = containerConfig.pauseGrace

  private val creationJobBaseTimeout = loadConfigOrThrow[FiniteDuration](ConfigKeys.schedulerInProgressJobRetention)

  private var monitor: Option[TestProbe] = None
  private val etcd = EtcdClient.apply(loadConfigOrThrow[EtcdConfig](ConfigKeys.etcd))

  private val clusterName = loadConfigOrThrow[String](ConfigKeys.whiskClusterName)

  val wskadmin = new RunCliCmd {
    override def baseCommand: mutable.Buffer[String] = WskAdmin.baseCommand
  }
  private val auth = BasicAuthenticationAuthKey()
  implicit val wskprops = WskProps(authKey = auth.compact, namespace = namespace)

  private def getPrefixFromInProgressContainerKey(key: String): String = {
    val prefixWithRevision = key.split("/creationId")
    val prefix = prefixWithRevision(0).split("/").dropRight(3)
    s"${prefix.mkString("/")}/"
  }

  private def getPrefixFromContainerKey(key: String): String = {
    val prefixWithRevision = key.split("/invoker")
    val prefix = prefixWithRevision(0).split("/").dropRight(1)
    s"${prefix.mkString("/")}/"
  }

  private def watchEtcd(res: WatchUpdate): Unit = {
    res.getEvents.asScala.foreach { event =>
      val key = event.getKv.getKey.toString(StandardCharsets.UTF_8)
      // only watch specified namespace
      if (key.contains(namespace)) {
        val processedKey =
          if (key.startsWith(inProgressPrefix))
            getPrefixFromInProgressContainerKey(key)
          else if (key.startsWith(warmedPrefix))
            getPrefixFromContainerKey(key)
          else if (key.startsWith(namespacePrefix))
            getPrefixFromContainerKey(key)
          else
            key
        event.getType match {
          // since warmed container will be exist for a long time, we will not watch the deletion of it
          case EventType.DELETE if (!key.startsWith(warmedPrefix)) =>
            monitor.foreach(_.ref ! DeleteEvent(processedKey))
          case EventType.PUT =>
            monitor.foreach(_.ref ! PutEvent(processedKey))
          case _ =>
        }
      }
    }
  }

  private val watcher = etcd.watchAllKeys(watchEtcd)

  override def beforeAll(): Unit = {
    wskadmin.cli(Seq("user", "create", namespace, "-u", auth.compact))
    retry(etcd.getCount("queue/").futureValue shouldBe 0, 100, Some(2.seconds)) // wait all other queues timed out
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    watcher.cancel(true)
    watcher.close()
    wskadmin.cli(Seq("user", "delete", namespace))
    etcd.close()
    super.afterAll()
  }

  private def checkNormalFlow(watcher: TestProbe, fqn: FullyQualifiedEntityName, error: Boolean = false): Unit = {
    // create one queue and one container
    watcher.expectMsgAllOf(
      20.seconds,
      PutEvent(QueueKeys.queue(namespace, fqn, true)),
      PutEvent(ThrottlingKeys.namespace(fqn.namespace)),
      PutEvent(ThrottlingKeys.action(namespace, fqn)),
      PutEvent(containerPrefix(inProgressPrefix, namespace, fqn)),
      PutEvent(containerPrefix(namespacePrefix, namespace, fqn)),
      DeleteEvent(containerPrefix(inProgressPrefix, namespace, fqn)))

    val additionalContainers = checkAdditionalContainers(watcher, fqn, error)

    // if container is failed to create or run activation, it will not goto Paused state
    if (error) {
      (0 to additionalContainers).foreach { _ =>
        watcher.expectMsg(pauseGrace + 5.seconds, DeleteEvent(containerPrefix(namespacePrefix, namespace, fqn)))
      }
    } else {
      if (additionalContainers >= 0) {
        // only one container will goto warmed state
        var messages =
          Seq.fill[Any](additionalContainers + 1)(DeleteEvent(containerPrefix(namespacePrefix, namespace, fqn)))
        messages :+= PutEvent(containerPrefix(warmedPrefix, namespace, fqn))
        watcher.expectMsgAllOf(pauseGrace + 5.seconds, messages: _*)
      }
    }

    // delete queue after timed out
    watcher.expectMsgAllOf(
      2 * (idleGrace + stopGrace) + 5.seconds,
      DeleteEvent(QueueKeys.queue(namespace, fqn, true)),
      DeleteEvent(ThrottlingKeys.namespace(fqn.namespace)),
      DeleteEvent(ThrottlingKeys.action(namespace, fqn)))
  }

  private def checkAdditionalContainers(watcher: TestProbe, fqn: FullyQualifiedEntityName, error: Boolean): Int = {
    // it may create more containers for old action
    var additionalContainers = 0
    breakable {
      while (true) {
        try {
          watcher.expectMsgAllOf(
            PutEvent(containerPrefix(inProgressPrefix, namespace, fqn)),
            PutEvent(containerPrefix(namespacePrefix, namespace, fqn)),
            DeleteEvent(containerPrefix(inProgressPrefix, namespace, fqn)))
          additionalContainers += 1
        } catch {
          case t: Throwable =>
            // it got one container deletion message for container failure case
            if (t.getMessage.contains("got 1"))
              additionalContainers -= 1
            else if (t.getMessage.contains("got 2")) {
              if (error) {
                additionalContainers -= 2
              } else {
                additionalContainers -= 1
              }
            }
            break
        }
      }
    }
    additionalContainers
  }

  behavior of "Wsk actions"

  it should "invoke an action successfully" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val watcher = TestProbe()
    monitor = Some(watcher)
    val name = "hello"
    val fqn = FullyQualifiedEntityName(EntityPath(namespace), EntityName(name), Some(SemVer()))
    assetHelper.withCleaner(wsk.action, name) { (action, _) =>
      action.create(name, defaultAction)
    }

    withActivation(wsk.activation, wsk.action.invoke(name, Map("payload" -> "stranger".toJson))) { activation =>
      activation.response.status shouldBe "success"
      activation.response.result shouldBe Some(JsObject("payload" -> "hello, stranger!".toJson))
    }

    checkNormalFlow(watcher, fqn)
  }

  it should "invoke an action successfully while updating it" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val watcher = TestProbe()
    monitor = Some(watcher)
    val name = "updating"
    val fqn = FullyQualifiedEntityName(EntityPath(namespace), EntityName(name), Some(SemVer()))
    assetHelper.withCleaner(wsk.action, name) { (action, _) =>
      action.create(name, defaultAction)
    }

    withActivation(wsk.activation, wsk.action.invoke(name, Map("payload" -> "stranger".toJson))) { activation =>
      activation.response.status shouldBe "success"
      activation.response.result shouldBe Some(JsObject("payload" -> "hello, stranger!".toJson))
    }

    // create one queue and one container
    watcher.expectMsgAllOf(
      20.seconds,
      PutEvent(QueueKeys.queue(namespace, fqn, true)),
      PutEvent(ThrottlingKeys.namespace(fqn.namespace)),
      PutEvent(ThrottlingKeys.action(namespace, fqn)),
      PutEvent(containerPrefix(inProgressPrefix, namespace, fqn)),
      PutEvent(containerPrefix(namespacePrefix, namespace, fqn)),
      DeleteEvent(containerPrefix(inProgressPrefix, namespace, fqn)))

    wsk.action.create(name, Some(TestUtils.getTestActionFilename("echo.js")), update = true)

    val additionalContainers = checkAdditionalContainers(watcher, fqn, false)
    if (additionalContainers >= 0) {
      // only one container will goto warmed state
      var messages =
        Seq.fill[Any](additionalContainers + 1)(DeleteEvent(containerPrefix(namespacePrefix, namespace, fqn)))
      messages :+= PutEvent(containerPrefix(warmedPrefix, namespace, fqn))
      watcher.expectMsgAllOf(pauseGrace + 5.seconds, messages: _*)
    }

    val newFqn = fqn.copy(version = Some(SemVer(0, 0, 2))) // version is updated from 0.0.1 to 0.0.2

    withActivation(wsk.activation, wsk.action.invoke(name, Map("payload" -> "stranger".toJson))) { activation =>
      activation.response.status shouldBe "success"
      activation.response.result shouldBe Some(JsObject("payload" -> "stranger".toJson))
    }

    // create 1 new container
    watcher.expectMsgAllOf(
      PutEvent(containerPrefix(inProgressPrefix, namespace, newFqn)),
      PutEvent(containerPrefix(namespacePrefix, namespace, newFqn)),
      DeleteEvent(containerPrefix(inProgressPrefix, namespace, newFqn)),
    )

    // pause new containers and delete additional new containers(if created)
    val additionalNewContainers = checkAdditionalContainers(watcher, newFqn, false)
    if (additionalNewContainers >= 0) {
      // only one container will goto warmed state
      var messages =
        Seq.fill[Any](additionalNewContainers + 1)(DeleteEvent(containerPrefix(namespacePrefix, namespace, newFqn)))
      messages :+= PutEvent(containerPrefix(warmedPrefix, namespace, newFqn))
      watcher.expectMsgAllOf(pauseGrace + 5.seconds, messages: _*)
    }

    watcher.expectMsgAllOf(
      2 * (idleGrace + stopGrace) + 5.seconds,
      DeleteEvent(QueueKeys.queue(namespace, fqn, true)),
      DeleteEvent(ThrottlingKeys.namespace(fqn.namespace)),
      DeleteEvent(ThrottlingKeys.action(namespace, fqn)))
  }

  it should "invoke an action that exits during initialization and get appropriate error" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val watcher = TestProbe()
      monitor = Some(watcher)
      val name = "abort init"
      val fqn = FullyQualifiedEntityName(EntityPath(namespace), EntityName(name), Some(SemVer()))
      assetHelper.withCleaner(wsk.action, name) { (action, _) =>
        action.create(name, Some(TestUtils.getTestActionFilename("initexit.js")))
      }

      withActivation(wsk.activation, wsk.action.invoke(name)) { activation =>
        val response = activation.response
        response.result.get.asJsObject().getFields("error") shouldBe Messages.abnormalInitialization.toJson
        response.status shouldBe ActivationResponse.messageForCode(ActivationResponse.DeveloperError)
      }

      checkNormalFlow(watcher, fqn, true)
  }

  it should "invoke an action that hangs during initialization and get appropriate error" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val watcher = TestProbe()
      monitor = Some(watcher)
      val name = "hang init"
      val fqn = FullyQualifiedEntityName(EntityPath(namespace), EntityName(name), Some(SemVer()))
      assetHelper.withCleaner(wsk.action, name) { (action, _) =>
        action.create(name, Some(TestUtils.getTestActionFilename("initforever.js")), timeout = Some(3 seconds))
      }

      withActivation(wsk.activation, wsk.action.invoke(name)) { activation =>
        val response = activation.response
        response.result.get.asJsObject().getFields("error") shouldBe Messages.timedoutActivation(3 seconds, true).toJson
        response.status shouldBe ActivationResponse.messageForCode(ActivationResponse.DeveloperError)
      }

      checkNormalFlow(watcher, fqn, true)
  }

  it should "invoke an action that exits during run and get appropriate error" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val watcher = TestProbe()
      monitor = Some(watcher)
      val name = "abort run"
      val fqn = FullyQualifiedEntityName(EntityPath(namespace), EntityName(name), Some(SemVer()))
      assetHelper.withCleaner(wsk.action, name) { (action, _) =>
        action.create(name, Some(TestUtils.getTestActionFilename("runexit.js")))
      }

      withActivation(wsk.activation, wsk.action.invoke(name)) { activation =>
        val response = activation.response
        response.result.get.asJsObject().getFields("error") shouldBe Messages.abnormalRun.toJson
        response.status shouldBe ActivationResponse.messageForCode(ActivationResponse.DeveloperError)
      }

      checkNormalFlow(watcher, fqn, true)
  }

  it should "create, and invoke an action that utilizes an invalid docker container with appropriate error" in withAssetCleaner(
    wskprops) {
    val watcher = TestProbe()
    val name = "invalidDockerContainer"
    val fqn = FullyQualifiedEntityName(EntityPath(namespace), EntityName(name), Some(SemVer()))
    val containerName = s"bogus${Random.alphanumeric.take(16).mkString.toLowerCase}"
    val inProgressContainerkey = containerPrefix(inProgressPrefix, namespace, fqn)
    watcher.ignoreMsg {
      case PutEvent(key)    => key == inProgressContainerkey
      case DeleteEvent(key) => key == inProgressContainerkey
    }
    monitor = Some(watcher)

    (wp, assetHelper) =>
      assetHelper.withCleaner(wsk.action, name) {
        // docker name is a randomly generate string
        (action, _) =>
          action.create(name, None, docker = Some(containerName))
      }

      val run = wsk.action.invoke(name)
      withActivation(wsk.activation, run) { activation =>
        activation.response.status shouldBe ActivationResponse.messageForCode(ActivationResponse.DeveloperError)
        activation.response.result.get
          .asJsObject()
          .getFields("error") shouldBe s"Failed to pull container image '$containerName'.".toJson
      }

      val timeout = creationJobBaseTimeout.toSeconds * 3
      // create one queue and failed to create container
      watcher.expectMsgAllOf(
        FiniteDuration(timeout, TimeUnit.SECONDS),
        PutEvent(QueueKeys.queue(namespace, fqn, true)),
        PutEvent(ThrottlingKeys.namespace(fqn.namespace)),
        PutEvent(ThrottlingKeys.action(namespace, fqn)))

      // delete queue after timed out
      watcher.expectMsgAllOf(
        flushGrace + 5.seconds,
        DeleteEvent(QueueKeys.queue(namespace, fqn, true)),
        DeleteEvent(ThrottlingKeys.namespace(fqn.namespace)),
        DeleteEvent(ThrottlingKeys.action(namespace, fqn)))
  }

  it should "invoke a long action several times successfully" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val watcher = TestProbe()
    val name = "hello-long"
    val fqn = FullyQualifiedEntityName(EntityPath(namespace), EntityName(name), Some(SemVer()))
    // ignore inProgressContainers&Throttling&warmedContainer as it may create many containers and some of them may failed or not used,
    // which make them hard to monitor
    val inProgressContainerkey = containerPrefix(inProgressPrefix, namespace, fqn)
    val warmedContainerKey = containerPrefix(warmedPrefix, namespace, fqn)
    watcher.ignoreMsg {
      case PutEvent(key) =>
        key == inProgressContainerkey || key.startsWith(s"${clusterName}/throttling") || key == warmedContainerKey || key
          .contains("invalidDockerContainer")
      case DeleteEvent(key) =>
        key == inProgressContainerkey || key.startsWith(s"${clusterName}/throttling") || key.contains(
          "invalidDockerContainer")
    }
    monitor = Some(watcher)

    assetHelper.withCleaner(wsk.action, name) { (action, _) =>
      action.create(name, Some(TestUtils.getTestActionFilename("sleep.js")))
    }

    val runs = (0 to 4).map(_ => wsk.action.invoke(name, Map("sleepTimeInMs" -> 30000.toJson)))
    runs.foreach { run =>
      withActivation(wsk.activation, run) { activation =>
        activation.response.status shouldBe "success"
        activation.response.result.get.toString should include("""Terminated successfully after around""")
      }
    }

    // create one queue and five containers at least
    watcher.expectMsgAllOf(
      20.seconds,
      PutEvent(QueueKeys.queue(namespace, fqn, true)),
      PutEvent(containerPrefix(namespacePrefix, namespace, fqn)),
      PutEvent(containerPrefix(namespacePrefix, namespace, fqn)),
      PutEvent(containerPrefix(namespacePrefix, namespace, fqn)),
      PutEvent(containerPrefix(namespacePrefix, namespace, fqn)),
      PutEvent(containerPrefix(namespacePrefix, namespace, fqn)))

    // since it may create more than 5 containers, ignore these containers
    var additionalContainers = 0
    breakable {
      while (true) {
        try {
          watcher.expectMsg(PutEvent(containerPrefix(namespacePrefix, namespace, fqn)))
          additionalContainers += 1
        } catch {
          case t: Throwable =>
            // need to minus 1 as it already got a DeleteEvent(existingContainers)
            if (t.getMessage.contains(s"found DeleteEvent(${containerPrefix(namespacePrefix, namespace, fqn)})")) {
              additionalContainers -= 1
            }
            break
        }
      }
    }

    // delete all 5 + additionalContainers containers after time out
    val containers = (1 to 5 + additionalContainers).toList.map { _ =>
      DeleteEvent(containerPrefix(namespacePrefix, namespace, fqn))
    }
    watcher.expectMsgAllOf(pauseGrace + 5.seconds, containers: _*)

    // delete queue after timed out
    watcher.expectMsg(2 * (idleGrace + stopGrace) + 5.seconds, DeleteEvent(QueueKeys.queue(namespace, fqn, true)))
  }
}

case class DeleteEvent(key: String)
case class PutEvent(key: String)
