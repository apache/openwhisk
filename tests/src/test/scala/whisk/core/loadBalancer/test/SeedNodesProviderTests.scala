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
import akka.actor.Address
import akka.testkit.TestKit
import com.typesafe.config.ConfigFactory
import common.StreamLogging
import org.junit.runner.RunWith
import org.scalatest.FlatSpecLike
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner
import org.scalatest.mockito.MockitoSugar
import whisk.core.WhiskConfig
import whisk.core.loadBalancer.StaticSeedNodesClusterProvider

object SeedNodesTestConfig {
  val config =
    """
      akka.cluster {
        name = "testcluster"
      }
      whisk.cluster.discovery {
        marathon-app-url = "http://192.168.99.100:8080/v2/apps/myapp/tasks"
        marathon-check-interval-seconds = 1
      }
    """
}
@RunWith(classOf[JUnitRunner])
class SeedNodesProviderTest
    extends TestKit(ActorSystem("SeedNodesTest", ConfigFactory.parseString(SeedNodesTestConfig.config)))
    with FlatSpecLike
    with Matchers
    with StreamLogging
    with MockitoSugar {

  val host = "192.168.99.100"
  val port = 8000
  val oneFakeSeedNode = s"$host:$port"
  val twoFakeSeedNodes = s"$host:$port $host:${port + 1}"
  val twoFakeSeedNodesWithoutWhitespace = s"$host:$port$host:${port + 1}"

  behavior of "StaticSeedNodesProvider"

  it should "return a sequence with a single seed node" in {
    val seqWithOneSeedNode =
      StaticSeedNodesClusterProvider.seedNodes(
        new WhiskConfig(Map("akka.cluster.seed.nodes" -> oneFakeSeedNode)),
        system)
    seqWithOneSeedNode shouldBe IndexedSeq(Address("akka.tcp", system.name, host, port))
  }
  it should "return a sequence with more then one seed node" in {
    val seqWithTwoSeedNodes =
      StaticSeedNodesClusterProvider.seedNodes(
        new WhiskConfig(Map("akka.cluster.seed.nodes" -> twoFakeSeedNodes)),
        system)
    seqWithTwoSeedNodes shouldBe IndexedSeq(
      Address("akka.tcp", system.name, host, port),
      Address("akka.tcp", system.name, host, port + 1))
  }
  it should "return an empty sequence if seed nodes are provided in the wrong format" in {
    val noneResponse = StaticSeedNodesClusterProvider.seedNodes(
      new WhiskConfig(Map("akka.cluster.seed.nodes" -> twoFakeSeedNodesWithoutWhitespace)),
      system)
    noneResponse shouldBe IndexedSeq.empty[Address]
  }
  it should "return an empty sequence if no seed nodes specified" in {
    val noneResponse =
      StaticSeedNodesClusterProvider.seedNodes(new WhiskConfig(Map("akka.cluster.seed.nodes" -> "")), system)
    noneResponse shouldBe IndexedSeq.empty[Address]
  }

}
