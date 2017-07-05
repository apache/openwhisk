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

import scala.concurrent.duration._

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
    with WordSpecLike
    with Matchers
    with BeforeAndAfterAll
    with BeforeAndAfterEach {

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "A Shared Data Service" must {

    val port = 2552
    val config = ConfigFactory
      .parseString("akka.cluster { seed-nodes = [\"akka.tcp://controller-actor-system@127.0.0" +
        s".1:$port" + "\"] }")
      .withValue("akka.remote.netty.tcp.hostname", ConfigValueFactory.fromAnyRef("127.0.0.1"))
      .withValue("akka.remote.netty.tcp.port", ConfigValueFactory.fromAnyRef(port))
      .withValue("akka.cluster.auto-down-unreachable-after", ConfigValueFactory.fromAnyRef("10s"))
      .withValue("akka.actor.provider", ConfigValueFactory.fromAnyRef("cluster"))
      .withValue("akka.remote.log-remote-lifecycle-events", ConfigValueFactory.fromAnyRef("off"))
      .withFallback(ConfigFactory.load())

    val system = ActorSystem("controller-actor-system", config)

    val sharedDataService = system.actorOf(SharedDataService.props("Candidates"), name = "busyMan")
    implicit val timeout = Timeout(5.seconds)

    "retrieve an empty map after initialization" in {
      sharedDataService ! GetTheMap()
      expectMsgPF() {
        case x: MapWithCounters if x.dataMap.size == 0 => true
      }
    }
    "increase the counter" in {
      sharedDataService ! (IncreaseCounter("Donald", 1))
      sharedDataService ! ReadCounter("Donald")
      expectMsg(1)
    }
    "decrease the counter" in {
      sharedDataService ! (IncreaseCounter("Donald", 2))
      sharedDataService ! (DecreaseCounter("Donald", 2))
      Thread.sleep(500)
      sharedDataService ! ReadCounter("Donald")
      Thread.sleep(500)
      expectMsg(1)
    }
    "return None for non existing keys" in {
      sharedDataService ! (IncreaseCounter("Donald", 1))
      Thread.sleep(500)
      sharedDataService ! (ReadCounter("Hilary"))
      expectMsg(None)
    }
    "remove the entry from the map" in {
      sharedDataService ! (IncreaseCounter("Fifi", 2))
      sharedDataService ! (RemoveCounter("Fifi"))
      Thread.sleep(500)
      sharedDataService ! (ReadCounter("Fifi"))
      expectMsg(None)
    }
    "receive the map with all counters" in {
      sharedDataService ! (IncreaseCounter("Hilary", 1))
      sharedDataService ! (GetTheMap())
      Thread.sleep(500)
      expectMsgPF() {
        case x: MapWithCounters if x.dataMap.size == 2 => true
      }
    }
  }

}
