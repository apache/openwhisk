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

package org.apache.openwhisk.core.containerpool.kubernetes

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets.UTF_8

import io.fabric8.kubernetes.api.builder.Predicate
import io.fabric8.kubernetes.api.model.policy.{PodDisruptionBudget, PodDisruptionBudgetBuilder}
import io.fabric8.kubernetes.api.model.{
  ContainerBuilder,
  EnvVarBuilder,
  EnvVarSourceBuilder,
  IntOrString,
  LabelSelectorBuilder,
  Pod,
  PodBuilder,
  Quantity
}
import io.fabric8.kubernetes.client.NamespacedKubernetesClient
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.entity.ByteSize

import scala.collection.JavaConverters._

class WhiskPodBuilder(client: NamespacedKubernetesClient, config: KubernetesClientConfig) {
  private val template = config.podTemplate.map(_.value.getBytes(UTF_8))
  private val actionContainerName = KubernetesRestLogSourceStage.actionContainerName
  private val actionContainerPredicate: Predicate[ContainerBuilder] = (cb) => cb.getName == actionContainerName

  def affinityEnabled: Boolean = config.userPodNodeAffinity.enabled

  def buildPodSpec(
    name: String,
    image: String,
    memory: ByteSize,
    environment: Map[String, String],
    labels: Map[String, String],
    config: KubernetesClientConfig)(implicit transid: TransactionId): (Pod, Option[PodDisruptionBudget]) = {
    val envVars = environment.map {
      case (key, value) => new EnvVarBuilder().withName(key).withValue(value).build()
    }.toSeq ++ config.fieldRefEnvironment
      .map(_.map({
        case (key, value) =>
          new EnvVarBuilder()
            .withName(key)
            .withValueFrom(new EnvVarSourceBuilder().withNewFieldRef().withFieldPath(value).endFieldRef().build())
            .build()
      }).toSeq)
      .getOrElse(Seq.empty)

    val baseBuilder = template match {
      case Some(bytes) =>
        new PodBuilder(loadPodSpec(bytes))
      case None => new PodBuilder()
    }

    val pb1 = baseBuilder
      .editOrNewMetadata()
      .withName(name)
      .addToLabels("name", name)
      .addToLabels("user-action-pod", "true")
      .addToLabels(labels.asJava)
      .endMetadata()

    val specBuilder = pb1.editOrNewSpec().withRestartPolicy("Always")

    if (config.userPodNodeAffinity.enabled) {
      val affinity = specBuilder
        .editOrNewAffinity()
        .editOrNewNodeAffinity()
        .editOrNewRequiredDuringSchedulingIgnoredDuringExecution()
      affinity
        .addNewNodeSelectorTerm()
        .addNewMatchExpression()
        .withKey(config.userPodNodeAffinity.key)
        .withOperator("In")
        .withValues(config.userPodNodeAffinity.value)
        .endMatchExpression()
        .endNodeSelectorTerm()
        .endRequiredDuringSchedulingIgnoredDuringExecution()
        .endNodeAffinity()
        .endAffinity()
    }

    val containerBuilder = if (specBuilder.hasMatchingContainer(actionContainerPredicate)) {
      specBuilder.editMatchingContainer(actionContainerPredicate)
    } else specBuilder.addNewContainer()

    //if cpu scaling is enabled, calculate cpu from memory, 100m per 256Mi, min is 100m(.1cpu), max is 10000 (10cpu)
    val cpu = config.cpuScaling
      .map(cpuConfig => Map("cpu" -> new Quantity(calculateCpu(cpuConfig, memory) + "m")))
      .getOrElse(Map.empty)

    val diskLimit = config.ephemeralStorage
      .map(diskConfig => Map("ephemeral-storage" -> new Quantity(diskConfig.limit.toMB + "Mi")))
      .getOrElse(Map.empty)

    //In container its assumed that env, port, resource limits are set explicitly
    //Here if any value exist in template then that would be overridden
    containerBuilder
      .withNewResources()
      //explicitly set requests and limits to same values
      .withLimits((Map("memory" -> new Quantity(memory.toMB + "Mi")) ++ cpu ++ diskLimit).asJava)
      .withRequests((Map("memory" -> new Quantity(memory.toMB + "Mi")) ++ cpu ++ diskLimit).asJava)
      .endResources()
      .withName(actionContainerName)
      .withImage(image)
      .withEnv(envVars.asJava)
      .addNewPort()
      .withContainerPort(8080)
      .withName("action")
      .endPort()

    //If any existing context entry is present then "update" it else add new
    containerBuilder
      .editOrNewSecurityContext()
      .editOrNewCapabilities()
      .addToDrop("NET_RAW", "NET_ADMIN")
      .endCapabilities()
      .endSecurityContext()

    val pod = containerBuilder
      .endContainer()
      .endSpec()
      .build()
    val pdb = if (config.pdbEnabled) {
      Some(
        new PodDisruptionBudgetBuilder().withNewMetadata
          .withName(name)
          .addToLabels(labels.asJava)
          .endMetadata()
          .withNewSpec()
          .withMinAvailable(new IntOrString(1))
          .withSelector(new LabelSelectorBuilder().withMatchLabels(Map("name" -> name).asJava).build())
          .and
          .build)
    } else {
      None
    }
    (pod, pdb)
  }

  def calculateCpu(c: KubernetesCpuScalingConfig, memory: ByteSize): Int = {
    val cpuPerMemorySegment = c.millicpus
    val cpuMin = c.millicpus
    val cpuMax = c.maxMillicpus
    math.min(math.max((memory.toMB / c.memory.toMB) * cpuPerMemorySegment, cpuMin), cpuMax).toInt
  }

  private def loadPodSpec(bytes: Array[Byte]): Pod = {
    val resources = client.load(new ByteArrayInputStream(bytes))
    resources.get().get(0).asInstanceOf[Pod]
  }
}
