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

package org.apache.openwhisk.core.entity

import java.util.Base64
import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.core.entity.Attachments.Inline
import org.apache.openwhisk.core.entity.size._
import pureconfig._
import pureconfig.generic.auto._

/**
 * Configuration for the system action used by the load balancer to probe invoker health.
 *
 * @param name When set to [[InvokerHealthTestActionConfig.WasmEntityName]], the WASM health action is used
 *             (single global entity name). Otherwise treated as the Node.js action name prefix; the full
 *             name is `${name}${controllerOrInvokerIndex}`.
 */
protected[core] case class InvokerHealthTestActionConfig(name: String)

protected[core] object InvokerHealthTestActionConfig {
  val DefaultNodejsPrefix: String = "invokerHealthTestAction"
  /** Global entity name when using the WASM-based health check (no per-invoker suffix). */
  val WasmEntityName: String = "invokerHealthTestActionWASM"

  def load(): InvokerHealthTestActionConfig =
    loadConfigOrThrow[InvokerHealthTestActionConfig](ConfigKeys.loadbalancerInvokerHealthTestAction)

  def isWasmMode(cfg: InvokerHealthTestActionConfig): Boolean = cfg.name == WasmEntityName
}

/**
 * Builds the whisk.system action used for invoker health probes (controller and FPC invoker).
 */
protected[core] object InvokerHealthTestActionBuilder {

  private val healthWasmResource = "/invoker-health.wasm"

  private def wasmAttachmentBase64: String = {
    val stream = Option(getClass.getResourceAsStream(healthWasmResource)).getOrElse {
      throw new IllegalStateException(s"resource $healthWasmResource not found on classpath")
    }
    try {
      val baos = new java.io.ByteArrayOutputStream()
      val buf = new Array[Byte](8192)
      var n = stream.read(buf)
      while (n != -1) {
        baos.write(buf, 0, n)
        n = stream.read(buf)
      }
      Base64.getEncoder.encodeToString(baos.toByteArray)
    } finally stream.close()
  }

  private def wasmHealthAction(identity: Identity,
                               entityName: EntityName,
                               limits: ActionLimits): Option[WhiskAction] = {
    ExecManifest.runtimesManifest.resolveDefaultRuntime("wasm:default").map { manifest =>
      val exec = CodeExecAsAttachment(
        manifest,
        Inline(wasmAttachmentBase64),
        entryPoint = Some("main"),
        binary = true)
      new WhiskAction(
        namespace = identity.namespace.name.toPath,
        name = entityName,
        exec = exec,
        parameters = Parameters("greeting", "Hello, World!"),
        limits = limits)
    }
  }

  private def nodejsHealthAction(identity: Identity,
                                 entityName: EntityName,
                                 limits: ActionLimits): Option[WhiskAction] = {
    ExecManifest.runtimesManifest.resolveDefaultRuntime("nodejs:default").map { manifest =>
      new WhiskAction(
        namespace = identity.namespace.name.toPath,
        name = entityName,
        exec = CodeExecAsString(manifest, """function main(params) { return params; }""", None),
        limits = limits)
    }
  }

  /**
   * Health action document name for the controller-driven health protocol (ShardingContainerPoolBalancer / InvokerPool).
   */
  def entityNameForController(controllerInstance: ControllerInstanceId, cfg: InvokerHealthTestActionConfig): EntityName =
    if (InvokerHealthTestActionConfig.isWasmMode(cfg)) EntityName(InvokerHealthTestActionConfig.WasmEntityName)
    else EntityName(s"${cfg.name}${controllerInstance.asString}")

  /**
   * Health action document name for FPC [[org.apache.openwhisk.core.containerpool.v2.InvokerHealthManager]].
   */
  def entityNameForInvoker(invokerInstance: InvokerInstanceId, cfg: InvokerHealthTestActionConfig): EntityName =
    if (InvokerHealthTestActionConfig.isWasmMode(cfg)) EntityName(InvokerHealthTestActionConfig.WasmEntityName)
    else EntityName(s"${cfg.name}${invokerInstance.toInt}")

  def forController(identity: Identity, controllerInstance: ControllerInstanceId): Option[WhiskAction] = {
    val cfg = InvokerHealthTestActionConfig.load()
    val limits = ActionLimits(memory = MemoryLimit(MemoryLimit.MIN_MEMORY))
    val name = entityNameForController(controllerInstance, cfg)
    if (InvokerHealthTestActionConfig.isWasmMode(cfg)) wasmHealthAction(identity, name, limits)
    else nodejsHealthAction(identity, name, limits)
  }

  def forInvoker(identity: Identity, invokerInstance: InvokerInstanceId): Option[WhiskAction] = {
    val cfg = InvokerHealthTestActionConfig.load()
    val limits =
      ActionLimits(memory = MemoryLimit(MemoryLimit.MIN_MEMORY), logs = LogLimit(0.B))
    val name = entityNameForInvoker(invokerInstance, cfg)
    if (InvokerHealthTestActionConfig.isWasmMode(cfg)) wasmHealthAction(identity, name, limits)
    else nodejsHealthAction(identity, name, limits)
  }
}
