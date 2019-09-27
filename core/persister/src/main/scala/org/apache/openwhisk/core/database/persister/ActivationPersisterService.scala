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

package org.apache.openwhisk.core.database.persister

import akka.actor.{ActorSystem, CoordinatedShutdown}
import akka.stream.ActorMaterializer
import org.apache.openwhisk.common.Logging
import org.apache.openwhisk.core.database.ActivationStore

import scala.concurrent.ExecutionContext

//We log all the config. So do not capture any secret here
case class PersisterConfig(port: Int,
                           clientId: String,
                           kafkaHosts: String,
                           parallelism: Int,
                           groupId: String,
                           topic: String,
                           topicIsPattern: Boolean,
                           retry: RetryConfig)

object ActivationPersisterService {
  val configRoot = "whisk.persister"

  def start(config: PersisterConfig, store: ActivationStore)(implicit system: ActorSystem,
                                                             materializer: ActorMaterializer,
                                                             logging: Logging): ActivationConsumer = {
    implicit val ec: ExecutionContext = system.dispatcher
    val persisterStore = new ActivationStorePersister(store)
    val consumer = new ActivationConsumer(config, persisterStore)

    CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseBeforeServiceUnbind, "shutdownConsumer") { () =>
      consumer.shutdown()
    }
    consumer
  }
}
