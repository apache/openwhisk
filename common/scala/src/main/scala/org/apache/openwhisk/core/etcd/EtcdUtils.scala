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

package org.apache.openwhisk.core.etcd

import java.nio.charset.StandardCharsets

import com.google.protobuf.ByteString
import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.core.containerpool.ContainerId
import org.apache.openwhisk.core.entity.SizeUnits.MB
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.entity.size._
import pureconfig.loadConfigOrThrow

import scala.language.implicitConversions
import scala.util.Try

case class EtcdConfig(hosts: String)

case class EtcdException(msg: String) extends Exception(msg)

/**
 * If you import the line below, it implicitly converts ByteString type to Scala Type.
 *
 * import org.apache.openwhisk.core.etcd.EtcdType._
 */
object EtcdType {

  implicit def stringToByteString(str: String): ByteString = ByteString.copyFromUtf8(str)

  implicit def ByteStringToString(byteString: ByteString): String = byteString.toString(StandardCharsets.UTF_8)

  implicit def ByteStringToInt(byteString: ByteString): Int = byteString.toString(StandardCharsets.UTF_8).toInt

  implicit def IntToByteString(int: Int): ByteString = ByteString.copyFromUtf8(int.toString)

  implicit def ByteStringToLong(byteString: ByteString): Long = byteString.toString(StandardCharsets.UTF_8).toLong

  implicit def LongToByteString(long: Long): ByteString = ByteString.copyFromUtf8(long.toString)

  implicit def ByteStringToBoolean(byteString: ByteString): Boolean =
    byteString.toString(StandardCharsets.UTF_8).toBoolean

  implicit def BooleanToByteString(bool: Boolean): ByteString = ByteString.copyFromUtf8(bool.toString)

  implicit def ByteStringToByteSize(byteString: ByteString): ByteSize =
    ByteSize(byteString.toString(StandardCharsets.UTF_8).toLong, MB)

  implicit def ByteSizeToByteString(byteSize: ByteSize): ByteString = ByteString.copyFromUtf8(byteSize.toMB.toString)

}

object EtcdKV {

  val TOP = "\ufff0"

  val clusterName = loadConfigOrThrow[String](ConfigKeys.whiskClusterName)

  object SchedulerKeys {
    val prefix = s"$clusterName/scheduler"

    val scheduler = s"$prefix"

    /**
     *  The keys for states of schedulers
     */
    def scheduler(instanceId: SchedulerInstanceId) = s"$prefix/${instanceId.asString}"

  }

  object QueueKeys {

    val inProgressPrefix = s"$clusterName/in-progress"
    val queuePrefix = s"$clusterName/queue"

    /**
     * The keys for in-progress queue
     */
    def inProgressQueue(invocationNamespace: String, fqn: FullyQualifiedEntityName) =
      s"$inProgressPrefix/queue/$invocationNamespace/${fqn.copy(version = None)}"

    /**
     * The prefix key for state in the queue
     */
    def queuePrefix(invocationNamespace: String, fqn: FullyQualifiedEntityName): String =
      s"$queuePrefix/$invocationNamespace/${fqn.copy(version = None)}"

    /**
     * The keys for state in the queue
     *
     * Example
     *  - queue/invocationNs/ns/pkg/act/leader
     *  - queue/invocationNs/ns/pkg/act/follower/scheduler1
     *  - queue/invocationNs/ns/pkg/act/follower/scheduler2
     *
     */
    def queue(invocationNamespace: String,
              fqn: FullyQualifiedEntityName,
              leader: Boolean,
              schedulerInstanceId: Option[SchedulerInstanceId] = None): String = {
      require(leader || (!leader && schedulerInstanceId.isDefined))
      val prefix = s"$queuePrefix/$invocationNamespace/${fqn.copy(version = None)}"
      if (leader)
        s"$prefix/leader"
      else
        s"$prefix/follower/${schedulerInstanceId.get.asString}"
    }
  }

  object ThrottlingKeys {
    val prefix = s"$clusterName/throttling"

    /**
     *  The keys for namespace throttling
     */
    def namespace(namespace: EntityName) = s"$prefix/$namespace"

    /**
     *  The keys for action throttling
     */
    def action(invocationNamespace: String, fqn: FullyQualifiedEntityName) =
      s"$prefix/$invocationNamespace/${fqn.copy(version = None)}"

    /**
     *  The keys for action throttling
     */
    def action(invocationNamespace: String, fqn: String) = s"$prefix/$invocationNamespace/$fqn"

  }

  object ContainerKeys {
    val namespacePrefix = s"$clusterName/namespace"
    val inProgressPrefix = s"$clusterName/in-progress"
    val warmedPrefix = s"$clusterName/warmed"

    /**
     * The keys for the number of container
     */
    def containerPrefix(containerType: String,
                        invocationNamespace: String,
                        fqn: FullyQualifiedEntityName,
                        revision: Option[DocRevision] = None): String =
      s"$containerType/$invocationNamespace/${fqn.copy(version = None)}/${revision.map(r => s"$r/").getOrElse("")}"

    /**
     * The keys for in-progress container
     *
     * For count queries, fqn must be at the front.
     */
    def inProgressContainer(invocationNamespace: String,
                            fqn: FullyQualifiedEntityName,
                            revision: DocRevision,
                            sid: SchedulerInstanceId,
                            cid: CreationId): String =
      s"${containerPrefix(inProgressPrefix, invocationNamespace, fqn, Some(revision))}scheduler/${sid.asString}/creationId/$cid"

    /**
     * The keys for the number of warmed container
     */
    def warmedContainers(invocationNamespace: String,
                         fqn: FullyQualifiedEntityName,
                         revision: DocRevision,
                         invokerInstanceId: InvokerInstanceId,
                         containerId: ContainerId): String =
      s"${containerPrefix(warmedPrefix, invocationNamespace, fqn, Some(revision))}invoker/${invokerInstanceId.instance}/container/${containerId.asString}"

    /**
     * The keys for the number of existing container
     */
    def existingContainers(invocationNamespace: String,
                           fqn: FullyQualifiedEntityName,
                           revision: DocRevision,
                           invoker: Option[InvokerInstanceId] = None,
                           containerId: Option[ContainerId] = None): String =
      containerPrefix(namespacePrefix, invocationNamespace, fqn, Some(revision)) + invoker
        .map(id => s"invoker${id.toInt}/")
        .getOrElse("") + containerId
        .map(id => s"container/${id.asString}")
        .getOrElse("")

    /**
     * The keys for the number of in-progress container by namespace
     */
    def inProgressContainerPrefixByNamespace(invocationNamespace: String): String =
      s"$inProgressPrefix/$invocationNamespace/"

    /**
     * The keys for the number of existing container by namespace
     */
    def existingContainersPrefixByNamespace(invocationNamespace: String): String =
      s"$namespacePrefix/$invocationNamespace/"

  }

  object InvokerKeys {
    val prefix = s"$clusterName/invokers"

    /**
     * If displayName only exists in the etcd key, we cannot differentiate it with the uniqueName
     */
    def health(invokerInstanceId: InvokerInstanceId) = {
      (invokerInstanceId.uniqueName, invokerInstanceId.displayedName) match {
        case (Some(uniqueName), Some(displayName)) => s"$prefix/${invokerInstanceId.toInt}/$uniqueName/$displayName"
        case (Some(uniqueName), None)              => s"$prefix/${invokerInstanceId.toInt}/$uniqueName"
        case _                                     => s"$prefix/${invokerInstanceId.toInt}"
      }
    }

    // id is not supposed to be -1
    private def getId(id: String): Int = {
      Try { id.toInt } getOrElse (-1)
    }

    def getInstanceId(invokerKey: String): InvokerInstanceId = {
      val constructs = invokerKey.split("\\b/+")
      constructs match {
        case Array(_, _, id, uniqueName, displayName) =>
          InvokerInstanceId(getId(id), Some(uniqueName), Some(displayName), 0.B)
        case Array(_, _, id, uniqueName) =>
          InvokerInstanceId(getId(id), Some(uniqueName), userMemory = 0.B)
        case Array(_, _, id) =>
          InvokerInstanceId(getId(id), userMemory = 0.B)
      }
    }
  }

  object InstanceKeys {

    val instancePrefix = s"$clusterName/instance"

    def instanceLease(instanceId: InstanceId): String =
      s"$instancePrefix/$instanceId/lease"
  }

}
