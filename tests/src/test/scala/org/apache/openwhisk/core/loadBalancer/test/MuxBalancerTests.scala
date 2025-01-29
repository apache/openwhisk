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

package org.apache.openwhisk.core.loadBalancer.test

import akka.actor.{ActorRef, ActorRefFactory, ActorSystem}
import akka.stream.ActorMaterializer
import akka.testkit.TestProbe
import common.StreamLogging
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.core.WhiskConfig
import org.apache.openwhisk.core.connector._
import org.apache.openwhisk.core.entity.size._
import org.apache.openwhisk.core.entity.test.ExecHelpers
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.loadBalancer._
import org.junit.runner.RunWith
import org.scalamock.scalatest.MockFactory
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.junit.JUnitRunner

import scala.collection.immutable.Map
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

/**
 * Unit tests for the MuxBalancer object.
 *
 */
@RunWith(classOf[JUnitRunner])
class MuxBalancerTests extends FlatSpec with Matchers with StreamLogging with ExecHelpers with MockFactory {
  behavior of "Mux Balancer"

  def lbConfig(activationStrategy: ActivationStrategy) =
    ShardingContainerPoolBalancerConfig(activationStrategy, 1.0 - 0.5, 0.5, 1, 1.minute)

  implicit val transId: TransactionId = TransactionId.testing

  val feedProbe = new FeedFactory {
    def createFeed(f: ActorRefFactory, m: MessagingProvider, p: (Array[Byte]) => Future[Unit]) =
      TestProbe().testActor

  }
  val invokerPoolProbe = new InvokerPoolFactory {
    override def createInvokerPool(
      actorRefFactory: ActorRefFactory,
      messagingProvider: MessagingProvider,
      messagingProducer: MessageProducer,
      sendActivationToInvoker: (MessageProducer, ActivationMessage, InvokerInstanceId) => Future[RecordMetadata],
      monitor: Option[ActorRef]): ActorRef =
      TestProbe().testActor
  }

  def mockMessaging(): MessagingProvider = {
    val messaging = stub[MessagingProvider]
    val producer = stub[MessageProducer]
    val consumer = stub[MessageConsumer]
    (messaging
      .getProducer(_: WhiskConfig, _: Option[ByteSize])(_: Logging, _: ActorSystem))
      .when(*, *, *, *)
      .returns(producer)
    (messaging
      .getConsumer(_: WhiskConfig, _: String, _: String, _: Int, _: FiniteDuration)(_: Logging, _: ActorSystem))
      .when(*, *, *, *, *, *, *)
      .returns(consumer)
    (producer
      .send(_: String, _: Message, _: Int))
      .when(*, *, *)
      .returns(Future.successful(new RecordMetadata(new TopicPartition("fake", 0), 0, 0, 0l, 0l, 0, 0)))

    messaging
  }

  it should "execute correct LoadBalancer for the default activation strategy" in {
    behave like asssertActivation(
      ActivationStrategy("org.apache.openwhisk.core.loadBalancer.test.MockLoadBalancerDefault", Map()),
      Parameters(),
      Left(ActivationId("default-mockLoadBalancerId0")))
  }

  it should "execute correct LoadBalancer for the custom activation strategy" in {
    behave like asssertActivation(
      ActivationStrategy(
        "org.apache.openwhisk.core.loadBalancer.test.MockLoadBalancerDefault",
        Map(
          "customLBStrategy01" -> StrategyConfig(
            "org.apache.openwhisk.core.loadBalancer.test.MockLoadBalancerCustom"))),
      Parameters("activationStrategy", "customLBStrategy01"),
      Left(ActivationId("custom-mockLoadBalancerId0")))
  }

  def asssertActivation(activationStrategy: ActivationStrategy,
                        annotations: Parameters,
                        expected: Either[ActivationId, WhiskActivation]) = {
    val slots = 10
    val memoryPerSlot = MemoryLimit.MIN_MEMORY
    val memory = memoryPerSlot * slots
    val config = new WhiskConfig(ExecManifest.requiredProperties)
    implicit val materializer: ActorMaterializer = ActorMaterializer()

    val balancer: LoadBalancer =
      new MuxBalancer(config, feedProbe, ControllerInstanceId("0"), mockMessaging, lbConfig(activationStrategy))
    val namespace = EntityPath("testspace")
    val name = EntityName("testname")
    val invocationNamespace = EntityName("invocationSpace")
    val concurrency = 5
    val actionMem = 256.MB
    val uuid = UUID()
    val aid = ActivationId.generate()
    val actionMetaData =
      WhiskActionMetaData(
        namespace,
        name,
        js10MetaData(Some("jsMain"), false),
        limits = actionLimits(actionMem, concurrency),
        annotations = annotations)

    val msg = ActivationMessage(
      TransactionId.testing,
      actionMetaData.fullyQualifiedName(true),
      actionMetaData.rev,
      Identity(Subject(), Namespace(invocationNamespace, uuid), BasicAuthenticationAuthKey(uuid, Secret())),
      aid,
      ControllerInstanceId("0"),
      blocking = false,
      content = None,
      initArgs = Set.empty,
      lockedArgs = Map.empty)

    val activation = balancer.publish(actionMetaData.toExecutableWhiskAction.get, msg)
    Await.ready(activation, 10.seconds)
    activation.onComplete(result => {
      result.get.onComplete(activation => {
        activation.get shouldBe expected
      })
    })
  }
}
