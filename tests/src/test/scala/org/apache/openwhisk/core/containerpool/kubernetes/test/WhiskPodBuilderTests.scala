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

import io.fabric8.kubernetes.api.model.policy.PodDisruptionBudgetBuilder
import io.fabric8.kubernetes.api.model.{
  EnvVar,
  EnvVarSource,
  IntOrString,
  LabelSelectorBuilder,
  ObjectFieldSelector,
  Pod
}
import io.fabric8.kubernetes.client.utils.Serialization
import org.apache.openwhisk.common.{ConfigMapValue, TransactionId}
import org.apache.openwhisk.core.containerpool.kubernetes.{
  KubernetesClientConfig,
  KubernetesClientTimeoutConfig,
  KubernetesCpuScalingConfig,
  KubernetesEphemeralStorageConfig,
  KubernetesInvokerNodeAffinity,
  WhiskPodBuilder
}
import org.apache.openwhisk.core.entity.size._
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, Matchers}

import scala.collection.JavaConverters._
import scala.concurrent.duration._

@RunWith(classOf[JUnitRunner])
class WhiskPodBuilderTests extends FlatSpec with Matchers with KubeClientSupport {
  implicit val tid: TransactionId = TransactionId.testing
  private val testImage = "nodejs"
  private val memLimit = 10.MB
  private val name = "whisk"
  private val affinity = KubernetesInvokerNodeAffinity(enabled = true, "openwhisk-role", "invoker")

  behavior of "WhiskPodBuilder"

  def config(configMap: Option[ConfigMapValue] = None, affinity: Option[KubernetesInvokerNodeAffinity] = None) =
    KubernetesClientConfig(
      KubernetesClientTimeoutConfig(1.seconds, 2.seconds),
      affinity.getOrElse(KubernetesInvokerNodeAffinity(false, "k", "v")),
      false,
      None,
      configMap,
      Some(KubernetesCpuScalingConfig(300, 3.MB, 1000)),
      false,
      Some(Map("POD_UID" -> "metadata.uid")),
      None)

  it should "build a new pod" in {
    val c = config()
    val builder = new WhiskPodBuilder(kubeClient, c)
    assertPodSettings(builder, c)
  }
  it should "build set cpu scaled based on memory, if enabled in configuration" in {
    val config = KubernetesClientConfig(
      KubernetesClientTimeoutConfig(1.second, 1.second),
      KubernetesInvokerNodeAffinity(false, "k", "v"),
      true,
      None,
      None,
      Some(KubernetesCpuScalingConfig(300, 3.MB, 1000)),
      false,
      None,
      None)
    val builder = new WhiskPodBuilder(kubeClient, config)

    val (pod, _) = builder.buildPodSpec(name, testImage, 2.MB, Map("foo" -> "bar"), Map("fooL" -> "barV"), config)
    withClue(Serialization.asYaml(pod)) {
      val c = getActionContainer(pod)
      //min cpu is: config.millicpus
      c.getResources.getLimits.asScala.get("cpu").map(_.getAmount) shouldBe Some("300m")
    }

    val (pod2, _) = builder.buildPodSpec(name, testImage, 15.MB, Map("foo" -> "bar"), Map("fooL" -> "barV"), config)
    withClue(Serialization.asYaml(pod2)) {
      val c = getActionContainer(pod2)
      //max cpu is: config.maxMillicpus
      c.getResources.getLimits.asScala.get("cpu").map(_.getAmount) shouldBe Some("1000m")
    }

    val (pod3, _) = builder.buildPodSpec(name, testImage, 7.MB, Map("foo" -> "bar"), Map("fooL" -> "barV"), config)
    withClue(Serialization.asYaml(pod3)) {
      val c = getActionContainer(pod3)
      //scaled cpu is: action mem/config.mem x config.maxMillicpus
      c.getResources.getLimits.asScala.get("cpu").map(_.getAmount) shouldBe Some("600m")
    }

    val config2 = KubernetesClientConfig(
      KubernetesClientTimeoutConfig(1.second, 1.second),
      KubernetesInvokerNodeAffinity(false, "k", "v"),
      true,
      None,
      None,
      None,
      false,
      None,
      None)
    val (pod4, _) = builder.buildPodSpec(name, testImage, 7.MB, Map("foo" -> "bar"), Map("fooL" -> "barV"), config2)
    withClue(Serialization.asYaml(pod4)) {
      val c = getActionContainer(pod4)
      //if scaling config is not provided, no cpu resources are specified
      c.getResources.getLimits.asScala.get("cpu").map(_.getAmount) shouldBe None
    }

  }
  it should "set ephemeral storage when configured" in {
    val config = KubernetesClientConfig(
      KubernetesClientTimeoutConfig(1.second, 1.second),
      KubernetesInvokerNodeAffinity(false, "k", "v"),
      true,
      None,
      None,
      Some(KubernetesCpuScalingConfig(300, 3.MB, 1000)),
      false,
      None,
      Some(KubernetesEphemeralStorageConfig(1.GB)))
    val builder = new WhiskPodBuilder(kubeClient, config)

    val (pod, _) = builder.buildPodSpec(name, testImage, 2.MB, Map("foo" -> "bar"), Map("fooL" -> "barV"), config)
    withClue(Serialization.asYaml(pod)) {
      val c = getActionContainer(pod)
      c.getResources.getLimits.asScala.get("ephemeral-storage").map(_.getAmount) shouldBe Some("1024Mi")
      c.getResources.getRequests.asScala.get("ephemeral-storage").map(_.getAmount) shouldBe Some("1024Mi")
    }
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

    val c = config(Some(ConfigMapValue(template)))
    val builder = new WhiskPodBuilder(kubeClient, c)
    val pod = assertPodSettings(builder, c)

    val ac = getActionContainer(pod)
    ac.getSecurityContext.getCapabilities.getDrop.asScala should contain("TEST_CAP")

    val sc = pod.getSpec.getContainers.asScala.find(_.getName == "sidecar").get
    sc.getImage shouldBe "busybox"

    pod.getMetadata.getLabels.asScala.get("my-fool") shouldBe Some("my-barv")
    pod.getMetadata.getAnnotations.asScala.get("my-foo") shouldBe Some("my-bar")
    pod.getMetadata.getNamespace shouldBe "whiskns"
  }

  it should "build a pod disruption budget for the pod, if enabled" in {
    val c = config()
    val builder = new WhiskPodBuilder(kubeClient, c)
    assertPodSettings(builder, c)
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

    val c = config(Some(ConfigMapValue(template)), Some(affinity.copy(enabled = true)))
    val builder =
      new WhiskPodBuilder(kubeClient, c)
    val pod = assertPodSettings(builder, c)

    val terms =
      pod.getSpec.getAffinity.getNodeAffinity.getRequiredDuringSchedulingIgnoredDuringExecution.getNodeSelectorTerms.asScala
    terms.exists(_.getMatchExpressions.asScala.exists(_.getKey == "nodelabel")) shouldBe true
  }

  private def assertPodSettings(builder: WhiskPodBuilder, config: KubernetesClientConfig): Pod = {
    val labels = Map("fooL" -> "barV")
    val (pod, pdb) = builder.buildPodSpec(name, testImage, memLimit, Map("foo" -> "bar"), labels, config)
    withClue(Serialization.asYaml(pod)) {
      val c = getActionContainer(pod)
      c.getEnv.asScala.shouldBe(Seq(
        new EnvVar("foo", "bar", null),
        new EnvVar("POD_UID", null, new EnvVarSource(null, new ObjectFieldSelector(null, "metadata.uid"), null, null))))

      c.getResources.getLimits.asScala.get("memory").map(_.getAmount) shouldBe Some("10Mi")
      c.getResources.getLimits.asScala.get("cpu").map(_.getAmount) shouldBe Some("900m")
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
    if (config.pdbEnabled) {
      println("matching pdb...")
      pdb shouldBe Some(
        new PodDisruptionBudgetBuilder().withNewMetadata
          .withName(name)
          .addToLabels(labels.asJava)
          .endMetadata()
          .withNewSpec()
          .withMinAvailable(new IntOrString(1))
          .withSelector(new LabelSelectorBuilder().withMatchLabels(Map("name" -> name).asJava).build())
          .and
          .build)
    }
    pod
  }

  private def getActionContainer(pod: Pod) = {
    pod.getSpec.getContainers.asScala.find(_.getName == "user-action").get
  }
}
