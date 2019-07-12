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

package org.apache.openwhisk.core.containerpool.ignite

import common.{StreamLogging, WskActorSystem}
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.WhiskConfig
import org.apache.openwhisk.core.entity.ExecManifest.ImageName
import org.apache.openwhisk.core.entity.InvokerInstanceId
import org.apache.openwhisk.core.entity.size._
import org.junit.runner.RunWith
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}

import scala.concurrent.duration._
@RunWith(classOf[JUnitRunner])
class IgniteContainerFactoryTests
    extends FlatSpec
    with Matchers
    with WskActorSystem
    with BeforeAndAfterAll
    with StreamLogging
    with ScalaFutures {

  implicit override val patienceConfig: PatienceConfig = PatienceConfig(timeout = 5.minutes)

  ignore should "launch the ignite vm" in {
    implicit val wskConfig: WhiskConfig = new WhiskConfig(Map.empty)
    implicit val tid: TransactionId = TransactionId.testing
    val instanceId = InvokerInstanceId(1, userMemory = 100.MB)
    val factory = IgniteContainerFactoryProvider.instance(actorSystem, logging, wskConfig, instanceId, Map.empty)
    val image = ImageName("whisk/ignite-nodejs-v12:latest")
    val container = factory.createContainer(tid, "footest", image, true, 256.MB, 1).futureValue

    println(container)

    container.destroy().futureValue
  }

  override def afterAll(): Unit = {
    println(logLines.mkString("\n"))
  }
}
