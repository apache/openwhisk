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

package org.apache.openwhisk.common.etcd

import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.core.entity.InvokerInstanceId
import org.apache.openwhisk.core.entity.size._
import org.apache.openwhisk.core.etcd.EtcdKV.InvokerKeys
import org.junit.runner.RunWith
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, Matchers}
import pureconfig.loadConfigOrThrow

@RunWith(classOf[JUnitRunner])
class EtcdKvTests extends FlatSpec with ScalaFutures with Matchers {

  behavior of "InvokerKeys"

  val clusterName = loadConfigOrThrow[String](ConfigKeys.whiskClusterName)
  val uniqueName = "myUniqueName"
  val displayedName = "myDisplayedName"

  it should "serialize a InvokerInstanceId to a health-key if there is only id" in {
    val instanceId = InvokerInstanceId(0, userMemory = 0.MB)
    InvokerKeys.health(instanceId) shouldBe s"$clusterName/invokers/0"
  }

  it should "serialize a InvokerInstanceId to a health-key if there are id and unique name" in {
    val instanceId = InvokerInstanceId(0, Some(uniqueName), userMemory = 0.MB)
    InvokerKeys.health(instanceId) shouldBe s"$clusterName/invokers/0/$uniqueName"
  }

  it should "serialize a InvokerInstanceId to a health-key if there are id, unique name and displayed name" in {
    val instanceId = InvokerInstanceId(0, Some(uniqueName), Some(displayedName), userMemory = 0.MB)
    InvokerKeys.health(instanceId) shouldBe s"$clusterName/invokers/0/$uniqueName/$displayedName"
  }

  it should "deserialize InvokerInstanceId from ETCD key if there is only id" in {
    val testKey = "$clusterName/invokers/0"
    val instanceId = InvokerKeys.getInstanceId(testKey)

    instanceId shouldBe InvokerInstanceId(0, userMemory = 0.MB)
  }

  it should "deserialize InvokerInstanceId from ETCD key with id and a unique name" in {
    val testKey = s"$clusterName/invokers/0/$uniqueName"
    val instanceId = InvokerKeys.getInstanceId(testKey)

    instanceId shouldBe InvokerInstanceId(0, Some(uniqueName), userMemory = 0.MB)
  }

  it should "deserialize InvokerInstanceId from ETCD key with id, a unique name, and a displayed name" in {
    val testKey = s"$clusterName/invokers/0/$uniqueName/$displayedName"
    val instanceId = InvokerKeys.getInstanceId(testKey)

    instanceId shouldBe InvokerInstanceId(0, Some(uniqueName), Some(displayedName), userMemory = 0.MB)
  }
}
