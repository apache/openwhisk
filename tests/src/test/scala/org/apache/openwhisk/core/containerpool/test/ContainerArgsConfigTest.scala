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

package org.apache.openwhisk.core.containerpool.test

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner
import pureconfig._
import pureconfig.generic.auto._
import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.core.containerpool.ContainerArgsConfig

@RunWith(classOf[JUnitRunner])
class ContainerArgsConfigTest extends FlatSpec with Matchers {

  it should "use defaults for container args map" in {
    val config = loadConfigOrThrow[ContainerArgsConfig](ConfigKeys.containerArgs)

    //check defaults
    config.network shouldBe "bridge"
    config.dnsServers shouldBe Seq[String]()
    config.dnsSearch shouldBe Seq[String]()
    config.dnsOptions shouldBe Seq[String]()
    config.extraArgs shouldBe Map[String, Set[String]]()
  }

  it should "override defaults from system properties" in {
    System.setProperty("whisk.container-factory.container-args.extra-args.label.0", "l1")
    System.setProperty("whisk.container-factory.container-args.extra-args.label.1", "l2")
    System.setProperty("whisk.container-factory.container-args.extra-args.label.3", "l3")
    System.setProperty("whisk.container-factory.container-args.extra-args.env.0", "e1")
    System.setProperty("whisk.container-factory.container-args.extra-args.env.1", "e2")

    System.setProperty("whisk.container-factory.container-args.dns-servers.0", "google.com")
    System.setProperty("whisk.container-factory.container-args.dns-servers.1", "1.2.3.4")

    System.setProperty("whisk.container-factory.container-args.dns-search.0", "a.b.c")
    System.setProperty("whisk.container-factory.container-args.dns-search.1", "a.b")

    System.setProperty("whisk.container-factory.container-args.dns-options.0", "ndots:5")

    val config = loadConfigOrThrow[ContainerArgsConfig](ConfigKeys.containerArgs)
    //check defaults
    config.network shouldBe "bridge"
    config.dnsServers shouldBe Seq[String]("google.com", "1.2.3.4")
    config.dnsSearch shouldBe Seq[String]("a.b.c", "a.b")
    config.dnsOptions shouldBe Seq[String]("ndots:5")
    //check map parsing of extra-args config
    config.extraArgs.get("label") shouldBe Some(Set("l1", "l2", "l3"))
    config.extraArgs.get("env") shouldBe Some(Set("e1", "e2"))

  }
}
