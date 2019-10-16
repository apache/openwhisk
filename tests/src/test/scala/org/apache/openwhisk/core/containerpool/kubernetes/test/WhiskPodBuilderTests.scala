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
import org.apache.openwhisk.common.{ConfigMapValue, TransactionId}
import org.apache.openwhisk.core.containerpool.kubernetes.{KubernetesInvokerNodeAffinity, WhiskPodBuilder}
import org.apache.openwhisk.core.entity.size._
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, Matchers}

import scala.collection.JavaConverters._

@RunWith(classOf[JUnitRunner])
class WhiskPodBuilderTests extends FlatSpec with Matchers with KubeClientSupport {
  implicit val tid: TransactionId = TransactionId.testing
  private val testImage = "nodejs"
  private val memLimit = 10.MB
  private val name = "whisk"
  private val affinity = KubernetesInvokerNodeAffinity(enabled = true, "openwhisk-role", "invoker")

  behavior of "WhiskPodBuilder"

  it should "build a new pod" in {
    val builder = new WhiskPodBuilder(kubeClient, affinity)
    assertPodSettings(builder)
  }

  it should "extend existing pod template" in {
    val template = """
       |---
       |apiVersion: "v1"
       |kind: "Pod"
       |metadata:
       |  annotations:
       |    my-foo : my-bar
       |  labels:
       |    my-fool : my-barv
       |  name: "testpod"
       |  namespace: whiskns
       |spec:
       |  containers:
       |    - name: "user-action"
       |      securityContext:
       |        capabilities:
       |          drop:
       |          - "TEST_CAP"
       |    - name: "sidecar"
       |      image : "busybox"
       |""".stripMargin

    val builder = new WhiskPodBuilder(kubeClient, affinity.copy(enabled = false), Some(ConfigMapValue(template)))
    val pod = assertPodSettings(builder)

    val ac = getActionContainer(pod)
    ac.getSecurityContext.getCapabilities.getDrop.asScala should contain("TEST_CAP")

    val sc = pod.getSpec.getContainers.asScala.find(_.getName == "sidecar").get
    sc.getImage shouldBe "busybox"

    pod.getMetadata.getLabels.asScala.get("my-fool") shouldBe Some("my-barv")
    pod.getMetadata.getAnnotations.asScala.get("my-foo") shouldBe Some("my-bar")
    pod.getMetadata.getNamespace shouldBe "whiskns"
  }

  it should "extend existing pod template with affinity" in {
    val template = """
       |apiVersion: "v1"
       |kind: "Pod"
       |spec:
       |  affinity:
       |    nodeAffinity:
       |      requiredDuringSchedulingIgnoredDuringExecution:
       |        nodeSelectorTerms:
       |        - matchExpressions:
       |          - key: "nodelabel"
       |            operator: "In"
       |            values:
       |            - "test"""".stripMargin

    val builder = new WhiskPodBuilder(kubeClient, affinity.copy(enabled = true), Some(ConfigMapValue(template)))
    val pod = assertPodSettings(builder)

    val terms =
      pod.getSpec.getAffinity.getNodeAffinity.getRequiredDuringSchedulingIgnoredDuringExecution.getNodeSelectorTerms.asScala
    terms.exists(_.getMatchExpressions.asScala.exists(_.getKey == "nodelabel")) shouldBe true
  }

  private def assertPodSettings(builder: WhiskPodBuilder): Pod = {
    val pod = builder.buildPodSpec(name, testImage, memLimit, Map("foo" -> "bar"), Map("fooL" -> "barV"))
    withClue(Serialization.asYaml(pod)) {
      val c = getActionContainer(pod)
      c.getEnv.asScala.exists(_.getName == "foo") shouldBe true

      c.getResources.getLimits.asScala.get("memory").map(_.getAmount) shouldBe Some("10Mi")
      c.getSecurityContext.getCapabilities.getDrop.asScala should contain allOf ("NET_RAW", "NET_ADMIN")
      c.getPorts.asScala.find(_.getName == "action").map(_.getContainerPort) shouldBe Some(8080)
      c.getImage shouldBe testImage

      pod.getMetadata.getLabels.asScala.get("name") shouldBe Some(name)
      pod.getMetadata.getLabels.asScala.get("fooL") shouldBe Some("barV")
      pod.getMetadata.getName shouldBe name
      pod.getSpec.getRestartPolicy shouldBe "Always"

      if (builder.affinityEnabled) {
        val terms =
          pod.getSpec.getAffinity.getNodeAffinity.getRequiredDuringSchedulingIgnoredDuringExecution.getNodeSelectorTerms.asScala
        terms.exists(_.getMatchExpressions.asScala.exists(_.getKey == affinity.key)) shouldBe true
      }
    }
    pod
  }

  private def getActionContainer(pod: Pod) = {
    pod.getSpec.getContainers.asScala.find(_.getName == "user-action").get
  }
}
