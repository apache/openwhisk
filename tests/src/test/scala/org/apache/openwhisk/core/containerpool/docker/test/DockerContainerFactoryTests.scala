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

package org.apache.openwhisk.core.containerpool.docker.test

import common.StreamLogging
import common.TimingHelpers
import common.WskActorSystem
import org.junit.runner.RunWith
import org.scalamock.scalatest.MockFactory
import org.scalatest.BeforeAndAfterEach
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.{ConfigKeys, WhiskConfig}
import org.apache.openwhisk.core.containerpool.{
  ContainerAddress,
  ContainerArgsConfig,
  ContainerId,
  RuntimesRegistryConfig
}
import org.apache.openwhisk.core.containerpool.docker.DockerApiWithFileAccess
import org.apache.openwhisk.core.containerpool.docker.DockerContainerFactory
import org.apache.openwhisk.core.containerpool.docker.DockerContainerFactoryConfig
import org.apache.openwhisk.core.containerpool.docker.RuncApi
import org.apache.openwhisk.core.entity.{ByteSize, ExecManifest, InvokerInstanceId}
import org.apache.openwhisk.core.entity.size._
import pureconfig._
import pureconfig.generic.auto._

@RunWith(classOf[JUnitRunner])
class DockerContainerFactoryTests
    extends FlatSpec
    with Matchers
    with MockFactory
    with StreamLogging
    with BeforeAndAfterEach
    with WskActorSystem
    with TimingHelpers {

  implicit val config = new WhiskConfig(ExecManifest.requiredProperties)
  ExecManifest.initialize(config) should be a 'success
  val runtimesRegistryConfig = loadConfigOrThrow[RuntimesRegistryConfig](ConfigKeys.runtimesRegistry)
  val userImagesRegistryConfig = loadConfigOrThrow[RuntimesRegistryConfig](ConfigKeys.userImagesRegistry)

  behavior of "DockerContainerFactory"

  val defaultUserMemory: ByteSize = 1024.MB

  it should "set the docker run args based on ContainerArgsConfig" in {

    val image = ExecManifest.runtimesManifest.manifests("nodejs:14").image

    implicit val tid = TransactionId.testing
    val dockerApiStub = mock[DockerApiWithFileAccess]
    //setup run expectation
    (dockerApiStub
      .run(_: String, _: Seq[String])(_: TransactionId))
      .expects(
        image.resolveImageName(Some(runtimesRegistryConfig.url)),
        List(
          "--cpu-shares",
          "32", //should be calculated as 1024/(numcore * sharefactor) via ContainerFactory.cpuShare
          "--memory",
          "10m",
          "--memory-swap",
          "10m",
          "--network",
          "net1",
          "-e",
          "__OW_API_HOST=",
          "-e",
          "k1=v1",
          "-e",
          "k2=v2",
          "-e",
          "k3=",
          "--dns",
          "dns1",
          "--dns",
          "dns2",
          "--name",
          "testContainer",
          "--extra1",
          "e1",
          "--extra1",
          "e2",
          "--extra2",
          "e3",
          "--extra2",
          "e4"),
        *)
      .returning(Future.successful { ContainerId("fakecontainerid") })
    //setup inspect expectation
    (dockerApiStub
      .inspectIPAddress(_: ContainerId, _: String)(_: TransactionId))
      .expects(ContainerId("fakecontainerid"), "net1", *)
      .returning(Future.successful { ContainerAddress("1.2.3.4", 1234) })
    //setup rm expectation
    (dockerApiStub
      .rm(_: ContainerId)(_: TransactionId))
      .expects(ContainerId("fakecontainerid"), *)
      .returning(Future.successful(()))
    //setup clientVersion exceptation
    (dockerApiStub.clientVersion _)
      .expects()
      .returning("mock_test_client")

    val factory =
      new DockerContainerFactory(
        InvokerInstanceId(0, userMemory = defaultUserMemory),
        Map.empty,
        ContainerArgsConfig(
          "net1",
          Seq("dns1", "dns2"),
          Seq.empty,
          Seq.empty,
          Seq("k1=v1", "k2=v2", "k3"),
          Map("extra1" -> Set("e1", "e2"), "extra2" -> Set("e3", "e4"))),
        runtimesRegistryConfig,
        userImagesRegistryConfig,
        DockerContainerFactoryConfig(true))(actorSystem, executionContext, logging, dockerApiStub, mock[RuncApi])

    val cf = factory.createContainer(tid, "testContainer", image, false, 10.MB, 32)

    val c = Await.result(cf, 5000.milliseconds)

    Await.result(c.destroy(), 500.milliseconds)

  }

}
