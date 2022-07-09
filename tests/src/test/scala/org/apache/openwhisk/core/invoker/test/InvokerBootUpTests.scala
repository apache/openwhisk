package org.apache.openwhisk.core.invoker.test

import java.nio.charset.StandardCharsets
import akka.actor.ActorSystem
import akka.testkit.TestKit
import common.WskTestHelpers
import org.apache.openwhisk.common.InvokerState.Healthy
import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.core.connector.InvokerResourceMessage
import org.apache.openwhisk.core.containerpool.v2.InvokerHealthManager.healthActionNamePrefix
import org.apache.openwhisk.core.entity.InvokerInstanceId
import org.apache.openwhisk.core.etcd.EtcdKV.ContainerKeys.namespacePrefix
import org.apache.openwhisk.core.etcd.EtcdKV.{InstanceKeys, InvokerKeys}
import org.apache.openwhisk.core.etcd.{EtcdClient, EtcdConfig}
import org.junit.runner.RunWith
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike}
import pureconfig.loadConfigOrThrow
import org.apache.openwhisk.core.entity.size._

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._
import pureconfig.generic.auto._

@RunWith(classOf[JUnitRunner])
class InvokerBootUpTests
    extends TestKit(ActorSystem("SchedulerFlow"))
    with FlatSpecLike
    with BeforeAndAfterAll
    with WskTestHelpers
    with ScalaFutures {
  private implicit val ec: ExecutionContextExecutor = system.dispatcher

  private val systemNamespace = "whisk.system"
  private val etcd = EtcdClient.apply(loadConfigOrThrow[EtcdConfig](ConfigKeys.etcd))

  override def afterAll(): Unit = {
    etcd.close()
    super.afterAll()
  }

  behavior of "Invoker Etcd Key"
  it should "haven't health action key" in {
    val healthActionPrefix = s"$namespacePrefix/namespace/$systemNamespace/$systemNamespace/$healthActionNamePrefix"
    awaitAssert({
      etcd.getPrefix(healthActionPrefix).futureValue.getKvsList.size() shouldBe 0
    }, 10.seconds)
  }

  it should "have lease key" in {
    val leasePrefix = s"$namespacePrefix/instance"
    awaitAssert({
      val leases = etcd.getPrefix(leasePrefix).futureValue.getKvsList.asScala.toArray

      // validate size
      leases.length > 0

      // validate key
      for (i <- leases.indices) {
        val invokerId = InvokerInstanceId(i, userMemory = 256.MB)
        leases(i).getKey.toString(StandardCharsets.UTF_8) shouldBe InstanceKeys.instanceLease(invokerId)
      }
    }, 10.seconds)
  }

  it should "have invoker key" in {
    val invokerPrefix = InvokerKeys.prefix
    awaitAssert(
      {
        val invokers = etcd.getPrefix(invokerPrefix).futureValue.getKvsList.asScala.toArray

        // validate size
        invokers.length > 0

        for (i <- invokers.indices) {
          val invokerId = InvokerInstanceId(i, uniqueName = Some(s"$i"), userMemory = 256.MB)
          // validate key
          invokers(i).getKey.toString(StandardCharsets.UTF_8) shouldBe InvokerKeys.health(invokerId)

          // validate if all invoker is healthy
          InvokerResourceMessage
            .parse(invokers(i).getValue.toString(StandardCharsets.UTF_8))
            .map { resource =>
              resource.status shouldBe Healthy.asString
            }
        }
      },
      10.seconds)
  }
}
