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

package org.apache.openwhisk.core.database.cosmosdb
import com.microsoft.azure.cosmosdb.rx.AsyncDocumentClient
import com.microsoft.azure.cosmosdb.{
  ConnectionMode,
  ConsistencyLevel,
  ConnectionPolicy => JConnectionPolicy,
  RetryOptions => JRetryOptions
}
import com.typesafe.config.Config
import com.typesafe.config.ConfigUtil.joinPath
import org.apache.openwhisk.core.ConfigKeys
import pureconfig._
import pureconfig.generic.auto._

import scala.collection.JavaConverters._
import scala.concurrent.duration._

case class CosmosDBConfig(endpoint: String,
                          key: String,
                          db: String,
                          throughput: Int,
                          consistencyLevel: ConsistencyLevel,
                          connectionPolicy: ConnectionPolicy,
                          timeToLive: Option[Duration],
                          clusterId: Option[String],
                          softDeleteTTL: Option[FiniteDuration],
                          recordUsageFrequency: Option[FiniteDuration]) {

  def createClient(): AsyncDocumentClient = {
    new AsyncDocumentClient.Builder()
      .withServiceEndpoint(endpoint)
      .withMasterKeyOrResourceToken(key)
      .withConsistencyLevel(consistencyLevel)
      .withConnectionPolicy(connectionPolicy.asJava)
      .build()
  }
}

case class ConnectionPolicy(maxPoolSize: Int,
                            preferredLocations: Seq[String],
                            usingMultipleWriteLocations: Boolean,
                            retryOptions: RetryOptions,
                            connectionMode: ConnectionMode) {
  def asJava: JConnectionPolicy = {
    val p = new JConnectionPolicy
    p.setMaxPoolSize(maxPoolSize)
    p.setUsingMultipleWriteLocations(usingMultipleWriteLocations)
    p.setPreferredLocations(preferredLocations.asJava)
    p.setRetryOptions(retryOptions.asJava)
    p.setConnectionMode(connectionMode)
    p
  }
}

case class RetryOptions(maxRetryAttemptsOnThrottledRequests: Int, maxRetryWaitTime: Duration) {
  def asJava: JRetryOptions = {
    val o = new JRetryOptions
    o.setMaxRetryAttemptsOnThrottledRequests(maxRetryAttemptsOnThrottledRequests)
    o.setMaxRetryWaitTimeInSeconds(maxRetryWaitTime.toSeconds.toInt)
    o
  }
}

object CosmosDBConfig {
  val collections = "collections"

  def apply(globalConfig: Config, entityTypeName: String): CosmosDBConfig = {
    val config = globalConfig.getConfig(ConfigKeys.cosmosdb)
    val specificConfigPath = joinPath(collections, entityTypeName)

    //Merge config specific to entity with common config
    val entityConfig = if (config.hasPath(specificConfigPath)) {
      config.getConfig(specificConfigPath).withFallback(config)
    } else {
      config
    }
    loadConfigOrThrow[CosmosDBConfig](entityConfig)
  }
}
