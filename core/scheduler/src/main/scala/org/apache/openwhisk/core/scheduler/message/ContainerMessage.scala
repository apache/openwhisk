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

package org.apache.openwhisk.core.scheduler.message

import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.connector.{
  ContainerCreationAckMessage,
  ContainerCreationError,
  ContainerCreationMessage
}
import org.apache.openwhisk.core.entity.{
  ByteSize,
  CreationId,
  DocRevision,
  FullyQualifiedEntityName,
  SchedulerInstanceId,
  WhiskActionMetaData
}

case class ContainerKeyMeta(revision: DocRevision, invokerId: Int, containerId: String)

case class ContainerCreation(msgs: List[ContainerCreationMessage], memory: ByteSize, invocationNamespace: String)
case class ContainerDeletion(invocationNamespace: String,
                             action: FullyQualifiedEntityName,
                             revision: DocRevision,
                             whiskActionMetaData: WhiskActionMetaData)

sealed trait CreationJob
case class RegisterCreationJob(msg: ContainerCreationMessage) extends CreationJob
case class FinishCreationJob(ack: ContainerCreationAckMessage) extends CreationJob
case class ReschedulingCreationJob(tid: TransactionId,
                                   creationId: CreationId,
                                   invocationNamespace: String,
                                   action: FullyQualifiedEntityName,
                                   revision: DocRevision,
                                   actionMetaData: WhiskActionMetaData,
                                   schedulerHost: String,
                                   rpcPort: Int,
                                   retry: Int)
    extends CreationJob {

  def toCreationMessage(sid: SchedulerInstanceId, retryCount: Int): ContainerCreationMessage =
    ContainerCreationMessage(
      tid,
      invocationNamespace,
      action,
      revision,
      actionMetaData,
      sid,
      schedulerHost,
      rpcPort,
      retryCount,
      creationId)
}

abstract class CreationJobState(val creationId: CreationId,
                                val invocationNamespace: String,
                                val action: FullyQualifiedEntityName,
                                val revision: DocRevision)
case class FailedCreationJob(override val creationId: CreationId,
                             override val invocationNamespace: String,
                             override val action: FullyQualifiedEntityName,
                             override val revision: DocRevision,
                             error: ContainerCreationError,
                             message: String)
    extends CreationJobState(creationId, invocationNamespace, action, revision)
case class SuccessfulCreationJob(override val creationId: CreationId,
                                 override val invocationNamespace: String,
                                 override val action: FullyQualifiedEntityName,
                                 override val revision: DocRevision)
    extends CreationJobState(creationId, invocationNamespace, action, revision)
