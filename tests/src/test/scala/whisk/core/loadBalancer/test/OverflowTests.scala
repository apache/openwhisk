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

package whisk.core.loadBalancer.test

import akka.actor.Actor
import akka.actor.ActorSystem
import akka.actor.PoisonPill
import akka.actor.Props
import akka.cluster.Cluster
import akka.testkit.ImplicitSender
import akka.testkit.TestKit
import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory
import common.StreamLogging
import java.time.Instant
import org.scalamock.scalatest.MockFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.FlatSpecLike
import org.scalatest.Matchers
import org.scalatest.concurrent.Eventually._
import org.scalatest.time.SpanSugar._
import scala.collection.mutable.ListBuffer
import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.Future
import spray.json.JsNumber
import spray.json.JsObject
import whisk.common.TransactionId
import whisk.core.WhiskConfig
import whisk.core.connector.ActivationMessage
import whisk.core.connector.CompletionMessage
import whisk.core.connector.MessagingProvider
import whisk.core.connector.test.TestConsumer
import whisk.core.loadBalancer.DistributedLoadBalancerData
import whisk.core.loadBalancer.StaticSeedNodesProvider
import whisk.core.loadBalancer.Updated
import whisk.core.connector.test.TestMessagingProvider
import whisk.core.entitlement.Privilege
import whisk.core.entity.ActivationId
import whisk.core.entity.ActivationResponse
import whisk.core.entity.AuthKey
import whisk.core.entity.CodeExecAsString
import whisk.core.entity.DocRevision
import whisk.core.entity.EntityName
import whisk.core.entity.EntityPath
import whisk.core.entity.ExecManifest
import whisk.core.entity.ExecManifest.ImageName
import whisk.core.entity.ExecManifest.RuntimeManifest
import whisk.core.entity.ExecutableWhiskAction
import whisk.core.entity.Identity
import whisk.core.entity.InstanceId
import whisk.core.entity.Secret
import whisk.core.entity.Subject
import whisk.core.entity.UUID
import whisk.core.entity.WhiskActivation
import whisk.core.entity.WhiskEntityStore
import whisk.core.loadBalancer.GetStatus
import whisk.core.loadBalancer.Healthy
import whisk.core.loadBalancer.LoadBalancerActorService
import whisk.core.loadBalancer.StatusUpdate
import whisk.core.loadBalancer.SubscribeLoadBalancer
import whisk.spi.SpiLoader
import whisk.spi.TypesafeConfigClassResolver

object LoadBlanacerTestKitConfig {
  val config = """
    akka.actor.provider = cluster
    whisk.spi.MessagingProvider = whisk.core.connector.test.TestMessagingProvider
    """
}
class OverflowTests
    extends TestKit(ActorSystem("ControllerCluster", ConfigFactory.parseString(LoadBlanacerTestKitConfig.config)))
    with ImplicitSender
    with FlatSpecLike
    with Matchers
    with BeforeAndAfterAll
    with MockFactory
    with StreamLogging {

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  def invokers(n: Int, messagingProvider: MessagingProvider, whiskConfig: WhiskConfig) =
    (0 until n).map(
      i =>
        (
          InstanceId(i),
          Healthy,
          messagingProvider
            .getConsumer(whiskConfig, "invokers", s"invoker${i}", 10, maxPollInterval = 50.milliseconds)))
  def activation(id: ActivationId) =
    WhiskActivation(
      namespace = EntityPath("ns"),
      name = EntityName("a"),
      Subject(),
      activationId = id,
      start = Instant.now(),
      end = Instant.now(),
      response = ActivationResponse.success(Some(JsObject("res" -> JsNumber(1)))),
      duration = Some(123))

  implicit val ec = system.dispatcher

  behavior of "overflow"

  //allow our config to feed the SpiLoader
  TypesafeConfigClassResolver.config = system.settings.config

  val whiskConfig = new WhiskConfig(
    WhiskEntityStore.requiredProperties ++
      ExecManifest.requiredProperties ++
      Map(WhiskConfig.loadbalancerInvokerBusyThreshold -> "1"))

  val invs = invokers(4, TestMessagingProvider, whiskConfig)
  val poolActor = system.actorOf(
    Props(new Actor {
      override def receive = {
        case SubscribeLoadBalancer(lbActor) =>
          lbActor ! StatusUpdate(invs.map(i => (i._1, i._2)))
        case GetStatus =>
          sender() ! invs.map(i => (i._1, i._2))
      }
    }),
    "testpool")
  val producer = TestMessagingProvider.getProducer(whiskConfig, system.dispatcher)

  ExecManifest.initialize(whiskConfig)
  // handle on the entity datastore
  val entityStore = WhiskEntityStore.datastore(whiskConfig)
  val authKey = AuthKey(UUID(), Secret())

  /** Specify how seed nodes are generated */
  val seedNodesProvider = new StaticSeedNodesProvider(whiskConfig.controllerSeedNodes, system.name)
  Cluster(system).joinSeedNodes(seedNodesProvider.getSeedNodes())

  it should "switch to overflow once capacity is exhausted, and switch back to underflow once capacity is available" in {

    val monitor = TestProbe()
    val controllerInstance = 9
    val messagingProvider = SpiLoader.get[MessagingProvider]
//    val pingConsumer = createPingConsumer(messagingProvider, InstanceId(controllerInstance))
//    val ackConsumer = createAckConsumer(messagingProvider, InstanceId(controllerInstance))
//    val overflowConsumer = createOverflowConsumer(messagingProvider, InstanceId(controllerInstance))

    val instance = InstanceId(controllerInstance)
    val lbData = new DistributedLoadBalancerData(instance, Some(testActor))
    val lb = new LoadBalancerActorService(
      whiskConfig,
      instance,
      poolActor,
//      pingConsumer,
//      ackConsumer,
//      overflowConsumer,
      lbData)
//    lb.updateInvokers(invs.map(i => (i._1, i._2)))
    val exec = CodeExecAsString(RuntimeManifest("actionKind", ImageName("testImage")), "testCode", None)
    val action = ExecutableWhiskAction(EntityPath("actionSpace"), EntityName("actionName"), exec)

    //verify underflow
    //    lb.overflowState.get() shouldBe false
    //TODO: use default (16) for threshold
    val futures = ListBuffer[Future[Any]]()

    //there is 1 invoker reserved for blackbox currently:
    val numInvokers = invs.size - 1
    val completions = ListBuffer[CompletionMessage]();
    (1 to numInvokers).foreach(i => {
//      futures += lb.publish(action, createActivation(action, idGen.make(), TransactionId(i)))(TransactionId(i))

      val id = idGen.make()
      //activations += id
      futures += lb.publish(action, createActivation(action, id, TransactionId(i)))(TransactionId(i))
      completions += CompletionMessage(TransactionId.testing, Right(activation(id)), invs(i - 1)._1)
    })
    //wait for queueing
    eventually(timeout(5000 millis), interval(50 millis)) {
      TestMessagingProvider.occupancy("invoker0") shouldBe 1
      TestMessagingProvider.occupancy("invoker1") shouldBe 1
      TestMessagingProvider.occupancy("invoker2") shouldBe 1
    }
    //wait for replication
//    eventually(timeout(5000 millis), interval(50 millis)) {
    expectMsg(Updated("Namespaces", Map(authKey.uuid.toString -> Map(9 -> 3))))
    expectMsg(
      Updated(
        "Invokers",
        Map("InstanceId(1)" -> Map(9 -> 1), "InstanceId(2)" -> Map(9 -> 1), "InstanceId(0)" -> Map(9 -> 1))))
    //  }
    monitor.expectNoMsg()
    //disable reading from overflow
    lb.overflowConsumer.asInstanceOf[TestConsumer].dontPeek = true
//    TestMessagingProvider.paused += "overflow"

    //1 more message will overflow
    val id = idGen.make()
    futures += lb.publish(action, createActivation(action, id, TransactionId(100)))(TransactionId(100))

    //monitor.expectMsg(Overflow)
    //wait for replication
//    expectMsg(Updated("Overflow", Map("overflow" -> Map(8 -> 1))))
//    expectMsg(Updated("Namespaces", Map(authKey.uuid.toString -> Map(8 -> 4))))

    //process messages
    (1 to invs.size - 1).foreach(index => {
      val i = invs(index - 1)
      val msgs = i._3.peek(100.milliseconds)
      //println(s"found ${msgs.size} in invoker${i._1}")
      i._3.commit()
    })

    eventually(timeout(5000 millis), interval(50 millis)) {
      TestMessagingProvider.occupancy("overflow") shouldBe 1
      TestMessagingProvider.occupancy("invoker0") shouldBe 0
      TestMessagingProvider.occupancy("invoker1") shouldBe 0
      TestMessagingProvider.occupancy("invoker2") shouldBe 0
    }
    //reenable the overflow processing
    lb.overflowConsumer.asInstanceOf[TestConsumer].dontPeek = false
//    TestMessagingProvider.paused -= "overflow"
    //send 1 completion
    //val id = activations.remove(0).activationId
    //val completion = CompletionMessage(TransactionId.testing, Right(activation(id)), InstanceId(0))
//    val completion = CompletionMessage(TransactionId.testing, Right(activation(id)), InstanceId(0))
//
//    producer.send(s"completed${controllerInstance}", completion)

    //verify underflow
    //monitor.expectMsg(Underflow)

    //complete the other activations
//    val lastActivation = activations.size
//    (1 to lastActivation).foreach(i => {
//      val id = activations.remove(0).activationId
//      val completion = CompletionMessage(TransactionId.testing, Right(activation(id)), InstanceId(i))
//      producer.send(s"completed${controllerInstance}", completion)
//
//    })
    completions.foreach(producer.send(s"completed${controllerInstance}", _))

    eventually(timeout(5000 millis), interval(50 millis)) {
      TestMessagingProvider.occupancy("invoker1") shouldBe 1

    }

    val msgs = invs(1)._3.peek(100.milliseconds)
    //println(s"found ${msgs.size} in invoker${invs(1)._1}")

//    expectMsg(Updated("Namespaces", Map(authKey.uuid.toString -> Map(8 -> 4))))
//    expectMsg(
//      Updated(
//        "Invokers",
//        Map("InstanceId(0)" -> Map(8 -> 0), "InstanceId(1)" -> Map(8 -> 1), "InstanceId(2)" -> Map(8 -> 0))))

    //invs(1)._3.commit()

//    //wait for replication
//    eventually(timeout(5000 millis), interval(50 millis)) {
//      expectMsg(Updated("Namespaces", Map(authKey.uuid.toString -> Map(8 -> 1))))
//      expectMsg(
//        Updated(
//          "Invokers",
//          Map("InstanceId(0)" -> Map(8 -> 0), "InstanceId(1)" -> Map(8 -> 1), "InstanceId(2)" -> Map(8 -> 0))))
//    }

    eventually(timeout(5000 millis), interval(50 millis)) {
      expectMsg(Updated("Overflow", Map("overflow" -> Map(9 -> 0))))
    }
    eventually(timeout(5000 millis), interval(50 millis)) {
      //expectMsg(Updated("Overflow", Map("overflow" -> Map(8 -> 0))))
      TestMessagingProvider.occupancy("invoker0") shouldBe 0
      TestMessagingProvider.occupancy("invoker1") shouldBe 0
      TestMessagingProvider.occupancy("invoker2") shouldBe 0
      TestMessagingProvider.occupancy("overflow") shouldBe 0

    }

    //println(s"sending ${completions.size} completions")
    completions.foreach(producer.send(s"completed${controllerInstance}", _))
    //println("waiting for completed9 to drain...")
    eventually(timeout(5000 millis), interval(50 millis)) {
      TestMessagingProvider.occupancy(s"completed${controllerInstance}") shouldBe 0
    }
    //println("drain completed...")
    Await.ready(Future.sequence(futures), 5.seconds)

    //send completion for the overflow msg

    producer.send(
      s"completed${controllerInstance}",
      CompletionMessage(TransactionId.testing, Right(activation(id)), invs(1)._1))

    //wait for replication
    eventually(timeout(5000 millis), interval(50 millis)) {
      expectMsg(Updated("Namespaces", Map(authKey.uuid.toString -> Map(9 -> 0))))
      //expectMsg(Updated("Overflow", Map("overflow" -> Map(8 -> 0))))
      expectMsg(
        Updated(
          "Invokers",
          Map("InstanceId(0)" -> Map(9 -> 0), "InstanceId(1)" -> Map(9 -> 0), "InstanceId(2)" -> Map(9 -> 0))))
    }

//    lb.overflowConsumer.close()
//    lb.activeAckConsumer.close()

    lb.lbActor ! PoisonPill
  }

  it should "allow controller1 to process requests from controller0 when controller0 goes into overflow" in {

    val monitor = TestProbe()
    val messagingProvider = SpiLoader.get[MessagingProvider]
    //configure lb1 to NOT process overflow messages
    val instance1 = new InstanceId(9)
    val instance2 = InstanceId(10)
//    val pingConsumer = createPingConsumer(messagingProvider, instance1)
    val maxPingsPerPoll = 54

    val maxActiveAcksPerPoll = 54
    val maxOverflowPerPoll = 5
    val overflowCapacity = Some(5)

//    val activeAckConsumer1 = createAckConsumer(messagingProvider, instance1)
//    val activeAckConsumer2 = createAckConsumer(messagingProvider, instance2)
//    val overflowConsumer1 = createOverflowConsumer(messagingProvider, instance1)
//    val overflowConsumer2 = createOverflowConsumer(messagingProvider, instance2)

    //start with nothing queued
    TestMessagingProvider.occupancy("invoker0") shouldBe 0
    TestMessagingProvider.occupancy("invoker1") shouldBe 0
    TestMessagingProvider.occupancy("invoker2") shouldBe 0

    //disable reading from overflow on lb1
//    lb1.overflowConsumer.asInstanceOf[TestConsumer].dontPeek = true
//    TestMessagingProvider.paused += "overflow"

    val lbData1 = new DistributedLoadBalancerData(instance1, Some(testActor))
    val lbData2 = new DistributedLoadBalancerData(instance2, Some(testActor))
    val lb1 =
      new LoadBalancerActorService(whiskConfig, InstanceId(9), poolActor, lbData1)
    lb1.overflowConsumer.asInstanceOf[TestConsumer].dontPeek = true
    lb1.activeAckConsumer.asInstanceOf[TestConsumer].dontPeek = true
    TestMessagingProvider.occupancy("overflow") shouldBe 0

    val lb2 =
      new LoadBalancerActorService(whiskConfig, InstanceId(10), poolActor, lbData2)
//    lb1.updateInvokers(invs.map(i => (i._1, i._2)))
//    lb2.updateInvokers(invs.map(i => (i._1, i._2)))
    val exec = CodeExecAsString(RuntimeManifest("actionKind", ImageName("testImage")), "testCode", None)
    val action = ExecutableWhiskAction(EntityPath("actionSpace"), EntityName("actionName"), exec)

    //verify underflow
    //    lb.overflowState.get() shouldBe false
    //TODO: use default (16) for threshold
    val futures = ListBuffer[Future[Any]]()

    //there is 1 invoker reserved for blackbox currently:
    val numInvokers = invs.size - 1
    //send 1 activation per invoker
    val activations = ListBuffer[ActivationId]();
    val completions = ListBuffer[CompletionMessage]();
    (1 to numInvokers).foreach(i => {
      val id = idGen.make()
      activations += id
      futures += lb1.publish(action, createActivation(action, id, TransactionId(i)))(TransactionId(i))
      completions += CompletionMessage(TransactionId.testing, Right(activation(id)), invs(i - 1)._1)
    })
    eventually(timeout(5000 millis), interval(50 millis)) {
      expectMsg(Updated("Namespaces", Map(authKey.uuid.toString -> Map(9 -> 3))))
//      expectMsg(
//        Updated(
//          "Invokers",
//          Map("InstanceId(0)" -> Map(9 -> 1), "InstanceId(1)" -> Map(9 -> 1), "InstanceId(2)" -> Map(9 -> 1))))

    }
    //

    //wait for queueing
    eventually(timeout(5000 millis), interval(50 millis)) {
      TestMessagingProvider.occupancy("invoker0") shouldBe 1
      TestMessagingProvider.occupancy("invoker1") shouldBe 1
      TestMessagingProvider.occupancy("invoker2") shouldBe 1
    }

    //TestMessagingProvider.consumers("overflow").dontPeek = true
    //overflowConsumer1.asInstanceOf[TestConsumer].dontPeek = true

    //publish one more to cause overflow
    val overflowedActivation = createActivation(action, idGen.make(), TransactionId(4))
    futures += lb1.publish(action, overflowedActivation)(TransactionId(4))

    //wait for overflow queueing
    //monitor.expectMsg(Overflow)

    eventually(timeout(5000 millis), interval(50 millis)) {
      expectMsg(Updated("Namespaces", Map(authKey.uuid.toString -> Map(9 -> 4))))

    }

    //wait for overflow draining
    //make sure it was sent to invoker1
    eventually(timeout(15000 millis), interval(50 millis)) {
      TestMessagingProvider.occupancy("invoker1") shouldBe 2

    }
    //disable processing of acks in both lbs, to verify queue routing
    lb1.activeAckConsumer.asInstanceOf[TestConsumer].dontPeek = true
    lb2.activeAckConsumer.asInstanceOf[TestConsumer].dontPeek = true
//    TestMessagingProvider.paused += "completed9"
//    TestMessagingProvider.paused += "completed10"

    //emulate completion in invoker TODO: create an invoker emulator
    val id = overflowedActivation.activationId
    val completion = CompletionMessage(TransactionId.testing, Right(activation(id)), InstanceId(1))
    producer.send(s"completed10", completion)
    //we should first get a completion for lb2 (where overflow was processed)
    //TestMessagingProvider.occupancy("completed10") shouldBe 1

    //we should first get a completion for lb2 (where overflow was processed)
    eventually(timeout(5000 millis), interval(50 millis)) {
//      activeAckConsumer2.asInstanceOf[TestConsumer].offset shouldBe 1
      TestMessagingProvider.occupancy("completed10") shouldBe 1
    }

    //disable lb1 ack processing
    //println("disabling peek on lb1")
    //activeAckConsumer1.asInstanceOf[TestConsumer].dontPeek = true
    //TestMessagingProvider.paused -= "completed9"

    //reenable lb2 ack processing
    lb2.activeAckConsumer.asInstanceOf[TestConsumer].dontPeek = false
//    TestMessagingProvider.paused -= "completed10"

    //println("processing ack2 again")
    //we should then get a completion for lb1 (where initial publish occurred)
    eventually(timeout(5000 millis), interval(50 millis)) {
//      activeAckConsumer1.asInstanceOf[TestConsumer].offset shouldBe 1
      TestMessagingProvider.occupancy("completed9") shouldBe 1
    }

    //renable lb1 ack processing
    lb1.activeAckConsumer.asInstanceOf[TestConsumer].dontPeek = false
//    TestMessagingProvider.paused -= "completed9"

    //monitor.expectMsg(Underflow)
    //println(s"waiting for ${futures.size} futures")

    completions.foreach(producer.send(s"completed9", _))

    Await.ready(Future.sequence(futures), 5.seconds)

//    lb1.activeAckConsumer.close()
//    lb2.activeAckConsumer.close()
//    lb1.overflowConsumer.close()
//    lb2.overflowConsumer.close()
    lb1.lbActor ! PoisonPill
    lb2.lbActor ! PoisonPill

    invs.foreach(i => {
      val msgs = i._3.peek(100.milliseconds)
      //println(s"found ${msgs.size} in invoker${i._1}")
    })
    //end with nothing queued
    eventually(timeout(5000 millis), interval(50 millis)) {
      TestMessagingProvider.occupancy("invoker0") shouldBe 0
      TestMessagingProvider.occupancy("invoker1") shouldBe 0
      TestMessagingProvider.occupancy("invoker2") shouldBe 0
      TestMessagingProvider.occupancy("overflow") shouldBe 0
    }
  }
  it should "rely on replicated data to decide" in {

    val monitor = TestProbe()
    val messagingProvider = SpiLoader.get[MessagingProvider]
    //configure lb1 to NOT process overflow messages
    val instance1 = InstanceId(9)
    val instance2 = InstanceId(10)
    val pingConsumer = createPingConsumer(messagingProvider, instance1)
    val maxPingsPerPoll = 54

    val maxActiveAcksPerPoll = 54
    val maxOverflowPerPoll = 5
    val overflowCapacity = Some(5)

    val activeAckConsumer1 = createAckConsumer(messagingProvider, instance1)
    val activeAckConsumer2 = createAckConsumer(messagingProvider, instance2)
    val overflowConsumer1 = createOverflowConsumer(messagingProvider, instance1)
    val overflowConsumer2 = createOverflowConsumer(messagingProvider, instance2)

    val lbData1 = new DistributedLoadBalancerData(instance1, Some(testActor))
    val lbData2 = new DistributedLoadBalancerData(instance2, Some(testActor))

    //start with nothing queued
    TestMessagingProvider.occupancy("invoker0") shouldBe 0
    TestMessagingProvider.occupancy("invoker1") shouldBe 0
    TestMessagingProvider.occupancy("invoker2") shouldBe 0
    TestMessagingProvider.occupancy("overflow") shouldBe 0

    //disable reading from overflow on lb1
    overflowConsumer1.asInstanceOf[TestConsumer].dontPeek = true

    val lb1 =
      new LoadBalancerActorService(
        whiskConfig,
        instance1,
//        entityStore,
        poolActor,
//        pingConsumer,
//        activeAckConsumer1,
//        overflowConsumer1,
        lbData1)
    val lb2 = new LoadBalancerActorService(
      whiskConfig,
      instance2,
//      entityStore,
      poolActor,
//      pingConsumer,
//      activeAckConsumer2,
//      overflowConsumer2,
      lbData2)
//    lb1.updateInvokers(invs.map(i => (i._1, i._2)))
//    lb2.updateInvokers(invs.map(i => (i._1, i._2)))

    val exec = CodeExecAsString(RuntimeManifest("actionKind", ImageName("testImage")), "testCode", None)
    val action = ExecutableWhiskAction(EntityPath("actionSpace"), EntityName("actionName"), exec)

    //verify underflow
    //    lb.overflowState.get() shouldBe false
    //TODO: use default (16) for threshold
    val futures = ListBuffer[Future[Any]]()

    //there is 1 invoker reserved for blackbox currently:
    val numInvokers = invs.size - 1
    //send 1 activation per invoker
    val activations = ListBuffer[ActivationId]();
    val completions = ListBuffer[CompletionMessage]();
    (1 to numInvokers - 1).foreach(i => {
      val id = idGen.make()
      activations += id
      futures += lb1.publish(action, createActivation(action, id, TransactionId(i)))(TransactionId(i))
      completions += CompletionMessage(TransactionId.testing, Right(activation(id)), invs(i - 1)._1)
    })

    //DO NOT WAIT FOR REPLICATION HERE

    //wait for queueing - the action hash will cause scheduling to start with invoker1
    eventually(timeout(5000 millis), interval(50 millis)) {
      TestMessagingProvider.occupancy("invoker0") shouldBe 0
      TestMessagingProvider.occupancy("invoker1") shouldBe 1
      TestMessagingProvider.occupancy("invoker2") shouldBe 1
    }
    (1 to 1).foreach(i => {
      val id = idGen.make()
      activations += id
      futures += lb2.publish(action, createActivation(action, id, TransactionId(i)))(TransactionId(i))
      completions += CompletionMessage(TransactionId.testing, Right(activation(id)), invs(i - 1)._1)
    })

    //before replication, the scheduling will still start at invoker1
    eventually(timeout(5000 millis), interval(50 millis)) {
      TestMessagingProvider.occupancy("invoker0") shouldBe 0
      TestMessagingProvider.occupancy("invoker1") shouldBe 2
      TestMessagingProvider.occupancy("invoker2") shouldBe 1
    }
    //wait for replication
    //expectMsgClass(classOf[Updated])

    eventually(timeout(5000 millis), interval(50 millis)) {
      //without shared replicator:
      expectMsg(Updated("Namespaces", Map(authKey.uuid.toString -> Map(9 -> 2, 10 -> 1))))
      expectMsg(
        Updated(
          "Invokers",
          Map("InstanceId(1)" -> Map(9 -> 1, 10 -> 1), "InstanceId(2)" -> Map(9 -> 1), "InstanceId(0)" -> Map(9 -> 0))))
      //with shared replicator:

    }

    (1 to 1).foreach(i => {
      val id = idGen.make()
      activations += id
      futures += lb2.publish(action, createActivation(action, id, TransactionId(i)))(TransactionId(i))
      completions += CompletionMessage(TransactionId.testing, Right(activation(id)), invs(i - 1)._1)
    })

    //after replication, the scheduling will still start at invoker0
    eventually(timeout(5000 millis), interval(50 millis)) {
      TestMessagingProvider.occupancy("invoker0") shouldBe 1
      TestMessagingProvider.occupancy("invoker1") shouldBe 2
      TestMessagingProvider.occupancy("invoker2") shouldBe 1
    }

    lb1.lbActor ! PoisonPill
    lb2.lbActor ! PoisonPill

  }

  val idGen = new ActivationId.ActivationIdGenerator {}

  val activations = mutable.ListBuffer[ActivationMessage]()

  def createActivation(action: ExecutableWhiskAction, id: ActivationId, transid: TransactionId) = {
    val a = ActivationMessage(
      transid = transid,
      action = action.fullyQualifiedName(true),
      revision = DocRevision.empty,
      user = Identity(Subject("unhealthyInvokerCheck"), EntityName("unhealthyInvokerCheck"), authKey, Set[Privilege]()),
      activationId = id,
      activationNamespace = EntityPath("guest"),
      rootControllerIndex = InstanceId(0),
      blocking = false,
      content = None)
    activations += a
    a
  }

  def createPingConsumer(messagingProvider: MessagingProvider, instanceId: InstanceId) =
    messagingProvider.getConsumer(
      whiskConfig,
      s"health${instanceId.toInt}",
      "health",
      maxPeek = 128,
      maxPollInterval = 200.millis)
  def createAckConsumer(messagingProvider: MessagingProvider, instanceId: InstanceId) =
    messagingProvider.getConsumer(
      whiskConfig,
      "completions",
      s"completed${instanceId.toInt}",
      maxPeek = 128,
      maxPollInterval = 200.millis)
  def createOverflowConsumer(messagingProvider: MessagingProvider, instanceId: InstanceId) =
    messagingProvider.getConsumer(whiskConfig, "overflow", s"overflow", maxPeek = 1, maxPollInterval = 200.millis)
}
