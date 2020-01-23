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

package org.apache.openwhisk.core.database.cosmosdb.cache

import com.azure.data.cosmos.{ConnectionMode, ConsistencyLevel}
import com.typesafe.config.Config
import com.typesafe.config.ConfigUtil.joinPath
import pureconfig._
import pureconfig.generic.auto._

case class ConnectionInfo(endpoint: String,
                          key: String,
                          db: String,
                          throughput: Int,
                          connectionMode: ConnectionMode,
                          consistencyLevel: ConsistencyLevel)

case class FeedConfig(hostname: String, leaseCollection: String, startFromBeginning: Boolean)

case class EventProducerConfig(bufferSize: Int)

case class InvalidatorConfig(port: Int, clusterId: Option[String])

case class CacheInvalidatorConfig(globalConfig: Config) {
  val configRoot = "whisk.cache-invalidator"
  val cosmosConfigRoot = s"$configRoot.cosmosdb"
  val eventConfigRoot = s"$configRoot.event-producer"
  val connections = "collections"
  val feedConfig: FeedConfig = loadConfigOrThrow[FeedConfig](globalConfig.getConfig(cosmosConfigRoot))
  val eventProducerConfig: EventProducerConfig =
    loadConfigOrThrow[EventProducerConfig](globalConfig.getConfig(eventConfigRoot))
  val invalidatorConfig: InvalidatorConfig = loadConfigOrThrow[InvalidatorConfig](globalConfig.getConfig(configRoot))

  def getCollectionInfo(name: String): ConnectionInfo = {
    val config = globalConfig.getConfig(cosmosConfigRoot)
    val specificConfigPath = joinPath(connections, name)

    //Merge config specific to entity with common config
    val entityConfig = if (config.hasPath(specificConfigPath)) {
      config.getConfig(specificConfigPath).withFallback(config)
    } else {
      config
    }

    loadConfigOrThrow[ConnectionInfo](entityConfig)
  }
}
