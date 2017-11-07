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
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.Timeout
import com.typesafe.config.ConfigValueFactory
import com.typesafe.config.ConfigFactory
import org.scalatest._
import whisk.core.loadBalancer._
import org.scalatest.FlatSpecLike
import scala.concurrent.duration._
import whisk.core.entity.InstanceId

// Define your test specific configuration here

object TestKitConfig {
  val config = """
    akka.remote.netty.tcp {
      hostname = "127.0.0.1"
      port = 2555
    }
    """
}

class SharedDataServiceTests()
    extends TestKit(ActorSystem("ControllerCluster", ConfigFactory.parseString(TestKitConfig.config)))
    with ImplicitSender
    with FlatSpecLike
    with Matchers
    with BeforeAndAfterAll {

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  behavior of "SharedDataService"

  val port = 2552
  val host = "127.0.0.1"
  val config = ConfigFactory
    .parseString(s"akka.remote.netty.tcp.hostname=$host")
    .withValue("akka.remote.netty.tcp.port", ConfigValueFactory.fromAnyRef(port))
    .withValue("akka.actor.provider", ConfigValueFactory.fromAnyRef("cluster"))
    .withFallback(ConfigFactory.load())

  val s = ActorSystem("controller-actor-system", config)
  val storageName = "Candidates"
  val instance = InstanceId(123)
  val sharedDataService = s.actorOf(SharedDataService.props(storageName, testActor), name = "busyMan")
  implicit val timeout = Timeout(5.seconds)

  it should "retrieve an empty map after initialization" in {
    sharedDataService ! GetMap
    val msg = Map()
    expectMsg(msg)
  }
  it should "increase the counter" in {
    sharedDataService ! (IncreaseCounter("Donald", instance, 1))
    val msg = Map("Donald" -> Map(instance.toInt -> 1))
    expectMsg(Updated(storageName, msg))
    sharedDataService ! GetMap
    expectMsg(msg)
  }
  it should "decrease the counter" in {
    //verify starting at 1
    sharedDataService ! GetMap
    val msg = Map("Donald" -> Map(instance.toInt -> 1))
    expectMsg(msg)

    //increase and verify change
    sharedDataService ! (IncreaseCounter("Donald", instance, 2))
    val msg2 = Map("Donald" -> Map(instance.toInt -> 3))
    expectMsg(Updated(storageName, msg2))
    sharedDataService ! GetMap
    expectMsg(msg2)

    //decrease and verify change
    sharedDataService ! (DecreaseCounter("Donald", instance, 2))
    val msg3 = Map("Donald" -> Map(instance.toInt -> 1))
    expectMsg(Updated(storageName, msg3))
    sharedDataService ! GetMap
    expectMsg(msg3)
  }
  it should "receive the map with all counters" in {
    sharedDataService ! (IncreaseCounter("Hilary", instance, 1))
    val msg = Map("Hilary" -> Map(instance.toInt -> 1), "Donald" -> Map(instance.toInt -> 1))
    expectMsg(Updated(storageName, msg))
    sharedDataService ! GetMap
    expectMsg(msg)
  }
}
