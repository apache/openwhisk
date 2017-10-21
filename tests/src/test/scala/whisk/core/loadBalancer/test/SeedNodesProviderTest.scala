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

import akka.actor.Address
import org.scalatest.{FlatSpec, Matchers}
import whisk.core.loadBalancer.{StaticSeedNodesProvider}

class SeedNodesProviderTest extends FlatSpec with Matchers {

  val actorSystemName = "controller-actor-system"
  val host = "192.168.99.100"
  val port = 8000
  val oneFakeSeedNode = s"$host:$port"
  val twoFakeSeedNodes = s"$host:$port $host:${port + 1}"
  val twoFakeSeedNodesWithoutWhitespace = s"$host:$port$host:${port + 1}"

  behavior of "StaticSeedNodesProvider"

  it should "return a sequence with a single seed node" in {
    val seedNodesProvider = new StaticSeedNodesProvider(oneFakeSeedNode, actorSystemName)
    val seqWithOneSeedNode = seedNodesProvider.getSeedNodes()
    seqWithOneSeedNode shouldBe IndexedSeq(Address("akka.tcp", actorSystemName, host, port))
  }
  it should "return a sequence with more then one seed node" in {
    val seedNodesProvider = new StaticSeedNodesProvider(twoFakeSeedNodes, actorSystemName)
    val seqWithTwoSeedNodes = seedNodesProvider.getSeedNodes()
    seqWithTwoSeedNodes shouldBe IndexedSeq(
      Address("akka.tcp", actorSystemName, host, port),
      Address("akka.tcp", actorSystemName, host, port + 1))
  }
  it should "return an empty sequence if seed nodes are provided in the wrong format" in {
    val seedNodesProvider = new StaticSeedNodesProvider(twoFakeSeedNodesWithoutWhitespace, actorSystemName)
    val noneResponse = seedNodesProvider.getSeedNodes()
    noneResponse shouldBe IndexedSeq.empty[Address]
  }
  it should "return an empty sequence if no seed nodes specified" in {
    val seedNodesProvider = new StaticSeedNodesProvider("", actorSystemName)
    val noneResponse = seedNodesProvider.getSeedNodes()
    noneResponse shouldBe IndexedSeq.empty[Address]
  }

}
