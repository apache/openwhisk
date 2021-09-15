package org.apache.openwhisk.core.loadBalancer.test

import akka.actor.{ActorRef, ActorRefFactory, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.testkit.TestProbe
import common.{StreamLogging, WskActorSystem}
import org.apache.openwhisk.common.InvokerState.{Healthy, Offline, Unhealthy}
import org.apache.openwhisk.common.{InvokerHealth, Logging, TransactionId}
import org.apache.openwhisk.core.connector._
import org.apache.openwhisk.core.connector.test.TestConnector
import org.apache.openwhisk.core.database.test.DbUtils
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.entity.size._
import org.apache.openwhisk.core.entity.test.ExecHelpers
import org.apache.openwhisk.core.etcd.EtcdKV.{InvokerKeys, ThrottlingKeys}
import org.apache.openwhisk.core.etcd.{EtcdClient, EtcdConfig}
import org.apache.openwhisk.core.loadBalancer.{FPCPoolBalancer, FeedFactory, ShardingContainerPoolBalancerConfig}
import org.apache.openwhisk.core.scheduler.{SchedulerEndpoints, SchedulerStates}
import org.apache.openwhisk.core.{ConfigKeys, WhiskConfig}
import org.apache.openwhisk.utils.{retry => utilRetry}
import org.junit.runner.RunWith
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterEach, FlatSpecLike, Matchers}
import pureconfig._
import pureconfig.generic.auto._

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.language.postfixOps
import scala.util.Try

@RunWith(classOf[JUnitRunner])
class FPCPoolBalancerTests
    extends FlatSpecLike
    with Matchers
    with StreamLogging
    with ExecHelpers
    with MockFactory
    with ScalaFutures
    with WskActorSystem
    with BeforeAndAfterEach
    with DbUtils {

  private implicit val transId = TransactionId.testing
  implicit val ece: ExecutionContextExecutor = actorSystem.dispatcher
  private val etcd = EtcdClient(loadConfigOrThrow[EtcdConfig](ConfigKeys.etcd).hosts)

  private val testInvocationNamespace = "test-invocation-namespace"

  private var httpBound: Option[Http.ServerBinding] = None

  override def afterAll(): Unit = {
    httpBound.foreach(_.unbind())
    etcd.close()
    super.afterAll()
  }

  private val whiskConfig = new WhiskConfig(ExecManifest.requiredProperties)
  private def feedProbe(connector: Option[TestConnector] = None) = new FeedFactory {
    def createFeed(f: ActorRefFactory, m: MessagingProvider, p: (Array[Byte]) => Future[Unit]): ActorRef =
      connector
        .map { c =>
          f.actorOf(Props {
            new MessageFeed("activeack", logging, c, 128, 1.second, p)
          })
        }
        .getOrElse(TestProbe().testActor)
  }

  private val lbConfig: ShardingContainerPoolBalancerConfig =
    loadConfigOrThrow[ShardingContainerPoolBalancerConfig](ConfigKeys.loadbalancer)

  private def fakeMessageProvider(consumer: TestConnector): MessagingProvider = {
    new MessagingProvider {
      override def getConsumer(
        whiskConfig: WhiskConfig,
        groupId: String,
        topic: String,
        maxPeek: Int,
        maxPollInterval: FiniteDuration)(implicit logging: Logging, actorSystem: ActorSystem): MessageConsumer =
        consumer

      override def getProducer(config: WhiskConfig, maxRequestSize: Option[ByteSize])(
        implicit logging: Logging,
        actorSystem: ActorSystem): MessageProducer = consumer.getProducer()

      override def ensureTopic(config: WhiskConfig,
                               topic: String,
                               topicConfig: String,
                               maxMessageBytes: Option[ByteSize])(implicit logging: Logging): Try[Unit] = Try {}
    }
  }

  private val etcdDocsToDelete = ListBuffer[(EtcdClient, String)]()
  private def etcdPut(etcdClient: EtcdClient, key: String, value: String) = {
    etcdDocsToDelete += ((etcdClient, key))
    Await.result(etcdClient.put(key, value), 10.seconds)
  }

  override def afterEach(): Unit = {
    etcdDocsToDelete.map { etcd =>
      Try {
        Await.result(etcd._1.del(etcd._2), 10.seconds)
      }
    }
    etcdDocsToDelete.clear()
    cleanup()
    super.afterEach()
  }

  it should "watch the throttler flag from ETCD, and keep them in memory" in {
    val mockConsumer = new TestConnector("fake", 4, true)
    val messageProvider = fakeMessageProvider(mockConsumer)
    val poolBalancer =
      new FPCPoolBalancer(whiskConfig, ControllerInstanceId("0"), etcd, feedProbe(), lbConfig, messageProvider)
    val action = FullyQualifiedEntityName(EntityPath("testns/pkg"), EntityName("action"))

    val actionKey = ThrottlingKeys.action(testInvocationNamespace, action)
    val namespaceKey = ThrottlingKeys.namespace(EntityName(testInvocationNamespace))

    Thread.sleep(1000) // wait for the watcher active

    // set the throttle flag to true for action, the checkThrottle should return true
    etcdPut(etcd, actionKey, "true")
    utilRetry(
      poolBalancer.checkThrottle(EntityPath(testInvocationNamespace), action.fullPath.asString) shouldBe true,
      10)

    // set the throttle flag to false for action, the checkThrottle should return false
    etcdPut(etcd, actionKey, "false")
    utilRetry(
      poolBalancer.checkThrottle(EntityPath(testInvocationNamespace), action.fullPath.asString) shouldBe false,
      10)

    // set the throttle flag to true for action's namespace, the checkThrottle should still return false
    etcdPut(etcd, namespaceKey, "true")
    utilRetry(
      poolBalancer.checkThrottle(EntityPath(testInvocationNamespace), action.fullPath.asString) shouldBe false,
      10)

    // delete the action throttle flag, then the checkThrottle should return true
    Await.result(etcd.del(actionKey), 10.seconds)
    utilRetry(
      poolBalancer.checkThrottle(EntityPath(testInvocationNamespace), action.fullPath.asString) shouldBe true,
      10)

    // set the throttle flag to false for action's namespace, the checkThrottle should return false
    etcdPut(etcd, namespaceKey, "false")
    utilRetry(
      poolBalancer.checkThrottle(EntityPath(testInvocationNamespace), action.fullPath.asString) shouldBe false,
      10)

    // delete the namespace throttle flag, the checkThrottle should return false
    Await.result(etcd.del(namespaceKey), 10.seconds)
    utilRetry(
      poolBalancer.checkThrottle(EntityPath(testInvocationNamespace), action.fullPath.asString) shouldBe false,
      10)
  }

  it should "return the InvokerHealth" in {
    val mockConsumer = new TestConnector("fake", 4, true)
    val messageProvider = fakeMessageProvider(mockConsumer)
    val poolBalancer =
      new FPCPoolBalancer(whiskConfig, ControllerInstanceId("0"), etcd, feedProbe(), lbConfig, messageProvider)
    val invokers = IndexedSeq(
      InvokerInstanceId(0, Some("0"), userMemory = 0 bytes),
      InvokerInstanceId(1, Some("1"), userMemory = 0 bytes),
      InvokerInstanceId(2, Some("2"), userMemory = 0 bytes))

    val resource1 = InvokerResourceMessage(Healthy.asString, 0, 0, 0, Seq.empty[String], Seq.empty[String])
    val resource2 = InvokerResourceMessage(Unhealthy.asString, 0, 0, 0, Seq.empty[String], Seq.empty[String])
    val resource3 = InvokerResourceMessage(Offline.asString, 0, 0, 0, Seq.empty[String], Seq.empty[String])

    etcdPut(etcd, InvokerKeys.health(invokers(0)), resource1.serialize)
    etcdPut(etcd, InvokerKeys.health(invokers(1)), resource2.serialize)
    etcdPut(etcd, InvokerKeys.health(invokers(2)), resource3.serialize)

    val expectedHealth = IndexedSeq(
      InvokerHealth(invokers(0), Healthy),
      InvokerHealth(invokers(1), Unhealthy),
      InvokerHealth(invokers(2), Offline))

    poolBalancer
      .invokerHealth()
      .futureValue
      .map(i => i.id.toString -> i.status.asString) should contain theSameElementsAs expectedHealth.map(i =>
      i.id.toString -> i.status.asString)
  }

  it should "return Offline for missing invokers" in {
    val mockConsumer = new TestConnector("fake", 4, true)
    val messageProvider = fakeMessageProvider(mockConsumer)
    val poolBalancer =
      new FPCPoolBalancer(whiskConfig, ControllerInstanceId("0"), etcd, feedProbe(), lbConfig, messageProvider)
    val invokers = IndexedSeq(
      InvokerInstanceId(0, Some("0"), userMemory = 0 bytes),
      InvokerInstanceId(1, Some("1"), userMemory = 0 bytes),
      InvokerInstanceId(2, Some("2"), userMemory = 0 bytes),
      InvokerInstanceId(3, Some("3"), userMemory = 0 bytes),
      InvokerInstanceId(4, Some("4"), userMemory = 0 bytes),
      InvokerInstanceId(5, Some("5"), userMemory = 0 bytes))

    val resource1 = InvokerResourceMessage(Healthy.asString, 0, 0, 0, Seq.empty[String], Seq.empty[String])
    val resource2 = InvokerResourceMessage(Unhealthy.asString, 0, 0, 0, Seq.empty[String], Seq.empty[String])

    etcdPut(etcd, InvokerKeys.health(invokers(0)), resource1.serialize)
    etcdPut(etcd, InvokerKeys.health(invokers(5)), resource2.serialize)

    val expectedHealth = IndexedSeq(
      InvokerHealth(invokers(0), Healthy),
      InvokerHealth(invokers(1), Offline),
      InvokerHealth(invokers(2), Offline),
      InvokerHealth(invokers(3), Offline),
      InvokerHealth(invokers(4), Offline),
      InvokerHealth(invokers(5), Unhealthy))

    poolBalancer
      .invokerHealth()
      .futureValue
      .map(i => i.id.toString -> i.status.asString) should contain theSameElementsAs expectedHealth.map(i =>
      i.id.toString -> i.status.asString)
  }

  it should "loads scheduler endpoints from specified clusterName only" in {
    val host = "127.0.0.1"
    val rpcPort1 = 19090
    val rpcPort2 = 19091
    val rpcPort3 = 19092
    val rpcPort4 = 19090
    val rpcPort5 = 19091
    val rpcPort6 = 19092
    val akkaPort = 0
    val mockConsumer = new TestConnector("fake", 4, true)
    val messageProvider = fakeMessageProvider(mockConsumer)
    val clusterName1 = loadConfigOrThrow[String](ConfigKeys.whiskClusterName)
    val clusterName2 = "clusterName2"

    etcd.put(
      s"$clusterName1/scheduler/0",
      SchedulerStates(SchedulerInstanceId("0"), queueSize = 0, SchedulerEndpoints(host, rpcPort1, akkaPort)).serialize)
    etcd.put(
      s"$clusterName1/scheduler/1",
      SchedulerStates(SchedulerInstanceId("1"), queueSize = 0, SchedulerEndpoints(host, rpcPort2, akkaPort)).serialize)
    etcd.put(
      s"$clusterName1/scheduler/2",
      SchedulerStates(SchedulerInstanceId("2"), queueSize = 0, SchedulerEndpoints(host, rpcPort3, akkaPort)).serialize)
    etcd.put(
      s"$clusterName2/scheduler/3",
      SchedulerStates(SchedulerInstanceId("3"), queueSize = 0, SchedulerEndpoints(host, rpcPort4, akkaPort)).serialize)
    etcd.put(
      s"$clusterName2/scheduler/4",
      SchedulerStates(SchedulerInstanceId("4"), queueSize = 0, SchedulerEndpoints(host, rpcPort5, akkaPort)).serialize)
    etcd.put(
      s"$clusterName2/scheduler/5",
      SchedulerStates(SchedulerInstanceId("5"), queueSize = 0, SchedulerEndpoints(host, rpcPort6, akkaPort)).serialize)
    val poolBalancer =
      new FPCPoolBalancer(
        whiskConfig,
        ControllerInstanceId("0"),
        etcd,
        feedProbe(),
        lbConfig,
        messagingProvider = messageProvider)
    // Make sure poolBalancer instance is initialized
    Thread.sleep(5.seconds.toMillis)
    poolBalancer.getSchedulerEndpoint().toList.length shouldBe 3
    poolBalancer.getSchedulerEndpoint().values.foreach { scheduler =>
      List("scheduler0", "scheduler1", "scheduler2") should contain(scheduler.sid.toString)
    }
    // Delete etcd data finally
    List(
      s"$clusterName1/scheduler/0",
      s"$clusterName1/scheduler/1",
      s"$clusterName1/scheduler/2",
      s"$clusterName2/scheduler/3",
      s"$clusterName2/scheduler/4",
      s"$clusterName2/scheduler/5").foreach(etcd.del)
  }
}
