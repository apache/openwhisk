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

package org.apache.openwhisk.core.containerpool.yarn.test

import akka.actor.ActorSystem
import akka.http.scaladsl.model.DateTime
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.WhiskConfig
import org.apache.openwhisk.core.WhiskConfig._
import org.apache.openwhisk.core.containerpool.ContainerArgsConfig
import org.apache.openwhisk.core.entity.ExecManifest.ImageName
import org.apache.openwhisk.core.entity.{ByteSize, ExecManifest, InvokerInstanceId, SizeUnits}
import org.apache.openwhisk.core.yarn.{YARNConfig, YARNContainerFactory, YARNRESTUtil, YARNTask}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, FlatSpecLike, Suite}
import org.apache.openwhisk.core.entity.test.ExecHelpers

import scala.collection.immutable.Map
import scala.concurrent.Await
import scala.concurrent.duration._

@RunWith(classOf[JUnitRunner])
class YARNContainerFactoryTests
    extends Suite
    with BeforeAndAfter
    with FlatSpecLike
    with ExecHelpers
    with BeforeAndAfterAll {

  implicit val whiskConfig: WhiskConfig = new WhiskConfig(
    ExecManifest.requiredProperties ++ Map(wskApiHostname -> "apihost") ++ wskApiHost)

  val customManifest = Some(s"""
                               |{ "runtimes": {
                               |    "runtime1": [
                               |      {
                               |        "kind": "somekind:1",
                               |        "deprecated": false,
                               |        "default": true,
                               |        "image": {
                               |          "registry": "local-docker:8888",
                               |          "prefix": "openwhisk",
                               |          "name": "somekind",
                               |          "tag": "latest"
                               |        }
                               |      }
                               |    ],
                               |    "runtime2": [
                               |      {
                               |        "kind": "anotherkind:1",
                               |        "deprecated": false,
                               |        "default": true,
                               |        "image": {
                               |          "registry": "local-docker:8888",
                               |          "prefix": "openwhisk",
                               |          "name": "anotherkind",
                               |          "tag": "latest"
                               |        }
                               |      }
                               |    ]
                               |  }
                               |}
                               |""".stripMargin)
  val images = Array(
    ImageName("somekind", Option("local-docker:8888"), Option("openwhisk"), Some("latest")),
    ImageName("anotherkind", Option("local-docker:8888"), Option("openwhisk"), Some("latest")))
  val containerArgsConfig =
    new ContainerArgsConfig(
      "net1",
      Seq("dns1", "dns2"),
      Seq.empty,
      Seq.empty,
      Seq.empty,
      Map("extra1" -> Set("e1", "e2"), "extra2" -> Set("e3", "e4")))
  val yarnConfig =
    YARNConfig(
      "http://localhost:8088",
      yarnLinkLogMessage = true,
      "openwhisk-action-service",
      YARNRESTUtil.SIMPLEAUTH,
      "",
      "",
      "default",
      "256",
      1)
  val instance0 = new InvokerInstanceId(0, Some("invoker0"), Some("invoker0"), ByteSize(0, SizeUnits.BYTE))
  val instance1 = new InvokerInstanceId(1, Some("invoker1"), Some("invoker1"), ByteSize(0, SizeUnits.BYTE))
  val serviceName0 = yarnConfig.serviceName + "-0"
  val serviceName1 = yarnConfig.serviceName + "-1"
  val properties: Map[String, Set[String]] = Map[String, Set[String]]()

  behavior of "YARNContainerFactory"

  override def beforeAll = {
    ExecManifest.initialize(whiskConfig, customManifest)
    super.beforeAll()
  }

  override def afterAll = {
    super.afterAll()
    ExecManifest.initialize(whiskConfig)
  }

  it should "initialize correctly with zero containers" in {

    val rm = new MockYARNRM(8088, 1000)
    rm.start()
    val factory =
      new YARNContainerFactory(
        ActorSystem(),
        logging,
        whiskConfig,
        instance0,
        properties,
        containerArgsConfig,
        yarnConfig)
    factory.init()

    //Service was created
    assert(rm.services.contains(serviceName0))

    //Factory waited until service was stable
    assert(rm.initCompletionTimes.getOrElse(serviceName0, DateTime.MaxValue) < DateTime.now)

    //No containers were created
    assert(rm.flexCompletionTimes.getOrElse(serviceName0, Map[String, DateTime]()).isEmpty)

    val componentNamesInService = rm.services.get(serviceName0).orNull.components.map(c => c.name)
    val missingImages = images
      .map(e => e.name)
      .filter(imageName => !componentNamesInService.contains(imageName))

    //All images types were created
    assert(missingImages.isEmpty)

    //All components have zero containers
    assert(rm.services.get(serviceName0).orNull.components.forall(c => c.number_of_containers.get == 0))

    rm.stop()
  }

  it should "create a container" in {
    val rm = new MockYARNRM(8088, 1000)
    rm.start()
    val factory =
      new YARNContainerFactory(
        ActorSystem(),
        logging,
        whiskConfig,
        instance0,
        properties,
        containerArgsConfig,
        yarnConfig)
    factory.init()

    val imageToCreate = images(0)
    val containerFuture = factory.createContainer(
      TransactionId.testing,
      "name",
      imageToCreate,
      unuseduserProvidedImage = true,
      ByteSize(256, SizeUnits.MB),
      1)

    Await.result(containerFuture, 60.seconds)

    //Container of the correct type was created
    assert(rm.services.contains(serviceName0))
    assert(
      rm.services
        .get(serviceName0)
        .orNull
        .components
        .find(c => c.name.equals(imageToCreate.name))
        .orNull != null)
    assert(
      rm.services
        .get(serviceName0)
        .orNull
        .components
        .find(c => c.name.equals(imageToCreate.name))
        .orNull
        .number_of_containers
        .get == 1)

    //Factory waited for container to be stable
    assert(
      rm.flexCompletionTimes
        .getOrElse(serviceName0, Map[String, DateTime]())
        .getOrElse(imageToCreate.name, DateTime.MaxValue) < DateTime.now)

    rm.stop()
  }

  it should "destroy the correct container" in {
    val rm = new MockYARNRM(8088, 1000)
    rm.start()
    val factory =
      new YARNContainerFactory(
        ActorSystem(),
        logging,
        whiskConfig,
        instance0,
        properties,
        containerArgsConfig,
        yarnConfig)
    factory.init()

    val imageToDelete = images(0)
    val imageNotToDelete = images(1)

    val containerFuture1 = factory.createContainer(
      TransactionId.testing,
      "name",
      imageNotToDelete,
      unuseduserProvidedImage = true,
      ByteSize(256, SizeUnits.MB),
      1)

    val containerFuture2 = factory.createContainer(
      TransactionId.testing,
      "name",
      imageToDelete,
      unuseduserProvidedImage = true,
      ByteSize(256, SizeUnits.MB),
      1)

    val containerFuture3 = factory.createContainer(
      TransactionId.testing,
      "name",
      imageToDelete,
      unuseduserProvidedImage = true,
      ByteSize(256, SizeUnits.MB),
      1)

    val containerFuture4 = factory.createContainer(
      TransactionId.testing,
      "name",
      imageToDelete,
      unuseduserProvidedImage = true,
      ByteSize(256, SizeUnits.MB),
      1)

    val container1 = Await.result(containerFuture1, 30.seconds)
    val container2 = Await.result(containerFuture2, 30.seconds)
    val container3 = Await.result(containerFuture3, 30.seconds)
    val container4 = Await.result(containerFuture4, 30.seconds)

    //Ensure container was created
    val containerToRemoveName = container2.asInstanceOf[YARNTask].component_instance_name
    assert(
      rm.services
        .get(serviceName0)
        .orNull
        .components
        .find(c => c.name.equals(imageToDelete.name))
        .orNull
        .containers
        .get
        .map(c => c.component_instance_name)
        .contains(containerToRemoveName))

    val destroyFuture = container2.destroy()(TransactionId.testing)
    Await.result(destroyFuture, 30.seconds)

    //Ensure container of the correct type was deleted
    assert(rm.services.contains(serviceName0))
    assert(
      rm.services
        .get(serviceName0)
        .orNull
        .components
        .find(c => c.name.equals(imageNotToDelete.name))
        .orNull != null)
    assert(
      rm.services
        .get(serviceName0)
        .orNull
        .components
        .find(c => c.name.equals(imageNotToDelete.name))
        .orNull
        .number_of_containers
        .get == 1)

    assert(
      rm.services
        .get(serviceName0)
        .orNull
        .components
        .find(c => c.name.equals(imageToDelete.name))
        .orNull != null)
    assert(
      rm.services
        .get(serviceName0)
        .orNull
        .components
        .find(c => c.name.equals(imageToDelete.name))
        .orNull
        .number_of_containers
        .get == 2)

    assert(
      !rm.services
        .get(serviceName0)
        .orNull
        .components
        .find(c => c.name.equals(imageToDelete.name))
        .orNull
        .containers
        .get
        .map(c => c.component_instance_name)
        .contains(containerToRemoveName))

    rm.stop()
  }
  it should "create and destroy multiple containers" in {
    val rm = new MockYARNRM(8088, 1000)
    rm.start()
    val factory =
      new YARNContainerFactory(
        ActorSystem(),
        logging,
        whiskConfig,
        instance0,
        properties,
        containerArgsConfig,
        yarnConfig)
    factory.init()

    val container1Future = factory.createContainer(
      TransactionId.testing,
      "name",
      images(0),
      unuseduserProvidedImage = true,
      ByteSize(256, SizeUnits.MB),
      1)

    val container2Future = factory.createContainer(
      TransactionId.testing,
      "name",
      images(1),
      unuseduserProvidedImage = true,
      ByteSize(256, SizeUnits.MB),
      1)

    val container3Future = factory.createContainer(
      TransactionId.testing,
      "name",
      images(0),
      unuseduserProvidedImage = true,
      ByteSize(256, SizeUnits.MB),
      1)

    Await.result(container1Future, 30.seconds)
    val container2 = Await.result(container2Future, 30.seconds)
    val container3 = Await.result(container3Future, 30.seconds)

    val destroyFuture1 = container2.destroy()(TransactionId.testing)
    Await.result(destroyFuture1, 30.seconds)

    val destroyFuture2 = container3.destroy()(TransactionId.testing)
    Await.result(destroyFuture2, 30.seconds)

    //Containers of the correct type was deleted
    assert(rm.services.contains(serviceName0))
    assert(rm.services.get(serviceName0).orNull.components.find(c => c.name.equals(images(1).name)).orNull != null)
    assert(
      rm.services
        .get(serviceName0)
        .orNull
        .components
        .find(c => c.name.equals(images(1).name))
        .orNull
        .number_of_containers
        .get == 0)

    assert(rm.services.get(serviceName0).orNull.components.find(c => c.name.equals(images(0).name)).orNull != null)
    assert(
      rm.services
        .get(serviceName0)
        .orNull
        .components
        .find(c => c.name.equals(images(0).name))
        .orNull
        .number_of_containers
        .get == 1)

    //Factory waited for container to be stable
    assert(
      rm.flexCompletionTimes
        .getOrElse(serviceName0, Map[String, DateTime]())
        .getOrElse(images(0).name, DateTime.MaxValue) < DateTime.now)

    rm.stop()
  }
  it should "cleanup" in {
    val rm = new MockYARNRM(8088, 1000)
    rm.start()
    val factory =
      new YARNContainerFactory(
        ActorSystem(),
        logging,
        whiskConfig,
        instance0,
        properties,
        containerArgsConfig,
        yarnConfig)
    factory.init()
    factory.cleanup()

    //Service was destroyed
    assert(!rm.services.contains(serviceName0))
    assert(!rm.initCompletionTimes.contains(serviceName0))
    assert(!rm.flexCompletionTimes.contains(serviceName0))

    rm.stop()
  }

  it should "support HA" in {
    val rm = new MockYARNRM(8088, 1000)
    rm.start()
    val factory0 =
      new YARNContainerFactory(
        ActorSystem(),
        logging,
        whiskConfig,
        instance0,
        properties,
        containerArgsConfig,
        yarnConfig)
    factory0.init()
    val factory1 =
      new YARNContainerFactory(
        ActorSystem(),
        logging,
        whiskConfig,
        instance1,
        properties,
        containerArgsConfig,
        yarnConfig)
    factory1.init()

    val imageToCreate = images(0)
    val containerFuture0 = factory0.createContainer(
      TransactionId.testing,
      "name",
      imageToCreate,
      unuseduserProvidedImage = true,
      ByteSize(256, SizeUnits.MB),
      1)
    val containerFuture1 = factory1.createContainer(
      TransactionId.testing,
      "name",
      imageToCreate,
      unuseduserProvidedImage = true,
      ByteSize(256, SizeUnits.MB),
      1)

    Await.result(containerFuture0, 60.seconds)
    Await.result(containerFuture1, 60.seconds)

    //Container of the correct type was created for each invoker instance
    assert(rm.services.contains(serviceName0))
    assert(
      rm.services
        .get(serviceName0)
        .orNull
        .components
        .find(c => c.name.equals(imageToCreate.name))
        .orNull != null)
    assert(
      rm.services
        .get(serviceName0)
        .orNull
        .components
        .find(c => c.name.equals(imageToCreate.name))
        .orNull
        .number_of_containers
        .get == 1)

    assert(rm.services.contains(serviceName1))
    assert(
      rm.services
        .get(serviceName1)
        .orNull
        .components
        .find(c => c.name.equals(imageToCreate.name))
        .orNull != null)
    assert(
      rm.services
        .get(serviceName1)
        .orNull
        .components
        .find(c => c.name.equals(imageToCreate.name))
        .orNull
        .number_of_containers
        .get == 1)

    //Both factories waited for container to be stable
    assert(
      rm.flexCompletionTimes
        .getOrElse(serviceName0, Map[String, DateTime]())
        .getOrElse(imageToCreate.name, DateTime.MaxValue) < DateTime.now)

    assert(
      rm.flexCompletionTimes
        .getOrElse(serviceName1, Map[String, DateTime]())
        .getOrElse(imageToCreate.name, DateTime.MaxValue) < DateTime.now)

    rm.stop()
  }
}
