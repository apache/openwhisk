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

package org.apache.openwhisk.core.connector

import akka.actor.ActorSystem

import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import org.apache.openwhisk.common.Logging
import org.apache.openwhisk.core.WhiskConfig
import org.apache.openwhisk.core.entity.ByteSize
import org.apache.openwhisk.spi.Spi

import scala.util.Try

/**
 * An Spi for providing Messaging implementations.
 */
trait MessagingProvider extends Spi {
  def getConsumer(
    config: WhiskConfig,
    groupId: String,
    topic: String,
    maxPeek: Int = Int.MaxValue,
    maxPollInterval: FiniteDuration = 5.minutes)(implicit logging: Logging, actorSystem: ActorSystem): MessageConsumer
  def getProducer(config: WhiskConfig, maxRequestSize: Option[ByteSize] = None)(
    implicit logging: Logging,
    actorSystem: ActorSystem): MessageProducer
  def ensureTopic(config: WhiskConfig, topic: String, topicConfig: String, maxMessageBytes: Option[ByteSize] = None)(
    implicit logging: Logging): Try[Unit]
}
