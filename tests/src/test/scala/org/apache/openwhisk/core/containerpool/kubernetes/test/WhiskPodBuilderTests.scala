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

package org.apache.openwhisk.core.containerpool.kubernetes.test

import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.client.utils.Serialization
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.containerpool.kubernetes.{KubernetesInvokerNodeAffinity, WhiskPodBuilder}
import org.apache.openwhisk.core.entity.size._
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, Matchers}

import scala.collection.JavaConverters._

@RunWith(classOf[JUnitRunner])
class WhiskPodBuilderTests extends FlatSpec with Matchers with KubeClientSupport {
  implicit val tid: TransactionId = TransactionId.testing
  private val testImage = "busybox"
  private val memLimit = 10.MB
  private val name = "whisk"

  behavior of "WhiskPodBuilder"

  it should "build a pod spec" in {
    val affinity = KubernetesInvokerNodeAffinity(enabled = true, "openwhisk-role", "invoker")
    val builder = new WhiskPodBuilder(kubeClient, affinity)

    val pod = builder.buildPodSpec(name, testImage, memLimit, Map("foo" -> "bar"), Map("fooL" -> "barV"))
    assertPodSettings(pod)
  }

  private def assertPodSettings(pod: Pod): Unit = {
    withClue(Serialization.asYaml(pod)) {
      val c = pod.getSpec.getContainers.asScala.find(_.getName == "user-action").get
      c.getEnv.asScala.exists(_.getName == "foo") shouldBe true

      c.getResources.getLimits.asScala.get("memory").map(_.getAmount) shouldBe Some("10Mi")
      c.getSecurityContext.getCapabilities.getDrop.asScala should contain allOf ("NET_RAW", "NET_ADMIN")
      c.getPorts.asScala.find(_.getName == "action").map(_.getContainerPort) shouldBe Some(8080)
      c.getImage shouldBe testImage

      pod.getMetadata.getLabels.asScala.get("name") shouldBe Some(name)
      pod.getMetadata.getLabels.asScala.get("fooL") shouldBe Some("barV")
      pod.getMetadata.getName shouldBe name
    }
  }
}
