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

package org.apache.openwhisk.core

import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.connector.{ActivationMessage, ContainerCreationMessage}
import org.apache.openwhisk.core.entity.ActivationId.ActivationIdGenerator
import org.apache.openwhisk.core.entity._

object WarmUp {
  val warmUpActionIdentity = {
    val whiskSystem = "whisk.system"
    val uuid = UUID()
    Identity(Subject(whiskSystem), Namespace(EntityName(whiskSystem), uuid), BasicAuthenticationAuthKey(uuid, Secret()))
  }

  private val actionName = "warmUp"

  // this action doesn't need to be in database
  val warmUpAction = FullyQualifiedEntityName(warmUpActionIdentity.namespace.name.toPath, EntityName(actionName))

  def warmUpActivation(controller: ControllerInstanceId) = {
    ActivationMessage(
      transid = TransactionId.warmUp,
      action = warmUpAction,
      revision = DocRevision.empty,
      user = warmUpActionIdentity,
      activationId = new ActivationIdGenerator {}.make(),
      rootControllerIndex = controller,
      blocking = false,
      content = None,
      initArgs = Set.empty)
  }

  def warmUpContainerCreationMessage(scheduler: SchedulerInstanceId) =
    ExecManifest.runtimesManifest
      .resolveDefaultRuntime("nodejs:default")
      .map { manifest =>
        val metadata = WhiskActionMetaData(
          warmUpAction.path,
          warmUpAction.name,
          CodeExecMetaDataAsString(manifest, false, entryPoint = None))
        ContainerCreationMessage(
          TransactionId.warmUp,
          warmUpActionIdentity.namespace.name.toString,
          warmUpAction,
          DocRevision.empty,
          metadata,
          scheduler,
          "",
          0)
      }

  def isWarmUpAction(fqn: FullyQualifiedEntityName): Boolean = {
    fqn == warmUpAction
  }
}
