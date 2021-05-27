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

package org.apache.openwhisk.core.scheduler.queue

import org.apache.openwhisk.core.connector.ActivationMessage
import org.apache.openwhisk.core.entity.{DocInfo, FullyQualifiedEntityName}

import scala.concurrent.Promise

// Events sent by the actor
case class QueueRemoved(invocationNamespace: String, action: DocInfo, leaderKey: Option[String])
case class QueueReactivated(invocationNamespace: String, action: FullyQualifiedEntityName, docInfo: DocInfo)
case class CancelPoll(promise: Promise[Either[MemoryQueueError, ActivationMessage]])
case object QueueRemovedCompleted
case object FlushPulse

// Events received by the actor
case object Start
case object VersionUpdated
case object StopSchedulingAsOutdated
