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

import akka.actor.ActorSystem
import akka.testkit.ImplicitSender
import akka.testkit.TestKit
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import common.StreamLogging
import org.scalatest.BeforeAndAfterAll
import org.scalatest.FlatSpecLike
import org.scalatest.Matchers
//import scala.collection.mutable
import scala.concurrent.Promise
import whisk.core.entity.InstanceId
import scala.concurrent.duration._
import whisk.core.entity.ActivationId
import whisk.core.entity.UUID
import whisk.core.entity.WhiskActivation
import whisk.core.loadBalancer.ActivationEntry
import whisk.core.loadBalancer.DistributedLoadBalancerData
import whisk.core.loadBalancer.Updated

// Define your test specific configuration here

object DistributedLoadBalancerDataConfig {
  val config = """
    akka.remote.netty.tcp {
      hostname = "127.0.0.1"
      port = 2555
    }
    akka.actor.provider = cluster
    """
}

class DistributedLoadBalancerDataTests
    extends TestKit(
      ActorSystem("ControllerCluster", ConfigFactory.parseString(DistributedLoadBalancerDataConfig.config)))
    with ImplicitSender
    with FlatSpecLike
    with Matchers
    with BeforeAndAfterAll
    with StreamLogging {

  behavior of "DistributedLoadBalancerData"

  //val storageName = "Candidates"
  val controller1 = InstanceId(123)
  val controller2 = InstanceId(456)
  val controller3 = InstanceId(789)

  val invoker1 = InstanceId(0)
  val invoker2 = InstanceId(1)

  //val sharedDataService = system.actorOf(SharedDataService.props(storageName, testActor), name = "busyMan")
  implicit val timeout = Timeout(5.seconds)

  val activationIdPromise = Promise[Either[ActivationId, WhiskActivation]]()
  val namespace1 = UUID()
  val firstEntry: ActivationEntry =
    ActivationEntry(ActivationId(), namespace1, Some(invoker1), activationIdPromise)
  val secondEntry: ActivationEntry =
    ActivationEntry(ActivationId(), namespace1, Some(invoker2), activationIdPromise)
  val firstOverflowEnty: ActivationEntry = ActivationEntry(ActivationId(), namespace1, None, activationIdPromise)
  val secondOverflowEnty: ActivationEntry = ActivationEntry(ActivationId(), namespace1, None, activationIdPromise)

  val lbd1 = new DistributedLoadBalancerData(controller1, Some(testActor))
  val lbd2 = new DistributedLoadBalancerData(controller2, Some(testActor))

  it should "reflect local changes immediately and replicated changes eventually" in {

    //store 1 activation per lbd
    lbd1.putActivation(firstEntry.id, firstEntry)
    lbd2.putActivation(secondEntry.id, secondEntry)

    //only local changes are visible before replication completes
    lbd1.activationCountOn(firstEntry.namespaceId) shouldBe 1
    lbd1.activationCountPerInvoker shouldBe Map(firstEntry.invokerName.get.toString -> 1)
    lbd1.activationById(firstEntry.id) shouldBe Some(firstEntry)

    //1 Updated msg per storagename per LBD (4 total when not in overflow)
    //we cannot predict order of these updates
    expectMsgClass(classOf[Updated])
    expectMsgClass(classOf[Updated])
    expectMsgClass(classOf[Updated])
    expectMsgClass(classOf[Updated])

    //after replication, verify udpates
    lbd1.activationCountOn(firstEntry.namespaceId) shouldBe 2
    lbd1.activationCountPerInvoker shouldBe Map(
      firstEntry.invokerName.get.toString -> 1,
      secondEntry.invokerName.get.toString -> 1)
    lbd1.activationById(firstEntry.id) shouldBe Some(firstEntry)
    lbd2.activationById(secondEntry.id) shouldBe Some(secondEntry)

    //both entries should NOT be visible to the other (only counters are replicated)
    lbd2.activationById(firstEntry.id) shouldBe None
    lbd1.activationById(secondEntry.id) shouldBe None

    // clean up activations
    lbd1.removeActivation(firstEntry.id)
    lbd2.removeActivation(secondEntry.id)

    //verify local changes on lbd1
    lbd1.activationCountOn(firstEntry.namespaceId) shouldBe 1
    lbd1.activationCountPerInvoker shouldBe Map(
      firstEntry.invokerName.get.toString -> 0,
      secondEntry.invokerName.get.toString -> 1)
    lbd1.activationById(firstEntry.id) shouldBe None

    //verify local changes on lbd2
    lbd2.activationCountOn(secondEntry.namespaceId) shouldBe 1
    lbd2.activationCountPerInvoker shouldBe Map(
      firstEntry.invokerName.get.toString -> 1,
      secondEntry.invokerName.get.toString -> 0)
    lbd2.activationById(secondEntry.id) shouldBe None

    //wait for replication
    expectMsgAllClassOf(classOf[Updated], classOf[Updated], classOf[Updated], classOf[Updated])

    //verify replicated changes on lbd1
    lbd1.activationCountOn(firstEntry.namespaceId) shouldBe 0
    lbd1.activationCountPerInvoker shouldBe Map(
      firstEntry.invokerName.get.toString -> 0,
      secondEntry.invokerName.get.toString -> 0)

    //verify replicated changes on lbd2
    lbd2.activationCountOn(secondEntry.namespaceId) shouldBe 0
    lbd2.activationCountPerInvoker shouldBe Map(
      firstEntry.invokerName.get.toString -> 0,
      secondEntry.invokerName.get.toString -> 0)

  }

}
