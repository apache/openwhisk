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
import com.typesafe.config.ConfigFactory
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, Matchers}
import com.microsoft.azure.cosmosdb.{ConnectionMode, ConnectionPolicy => JConnectionPolicy}

import scala.collection.JavaConverters._

@RunWith(classOf[JUnitRunner])
class CosmosDBConfigTests extends FlatSpec with Matchers {
  val globalConfig = ConfigFactory.defaultApplication()
  behavior of "CosmosDB Config"

  it should "match SDK defaults" in {
    val config = ConfigFactory.parseString(s"""
      | whisk.cosmosdb {
      |  endpoint = "http://localhost"
      |  key = foo
      |  db  = openwhisk
      | }
         """.stripMargin).withFallback(globalConfig)
    val cosmos = CosmosDBConfig(config, "WhiskAuth")

    //Cosmos SDK does not have equals defined so match them explicitly
    val policy = cosmos.connectionPolicy.asJava
    val defaultPolicy = JConnectionPolicy.GetDefault()
    policy.getConnectionMode shouldBe defaultPolicy.getConnectionMode
    policy.getEnableEndpointDiscovery shouldBe defaultPolicy.getEnableEndpointDiscovery
    policy.getIdleConnectionTimeoutInMillis shouldBe defaultPolicy.getIdleConnectionTimeoutInMillis
    policy.getMaxPoolSize shouldBe defaultPolicy.getMaxPoolSize
    policy.getPreferredLocations shouldBe defaultPolicy.getPreferredLocations
    policy.getRequestTimeoutInMillis shouldBe defaultPolicy.getRequestTimeoutInMillis
    policy.isUsingMultipleWriteLocations shouldBe defaultPolicy.isUsingMultipleWriteLocations

    val retryOpts = policy.getRetryOptions
    val defaultOpts = defaultPolicy.getRetryOptions

    retryOpts.getMaxRetryAttemptsOnThrottledRequests shouldBe defaultOpts.getMaxRetryAttemptsOnThrottledRequests
    retryOpts.getMaxRetryWaitTimeInSeconds shouldBe defaultOpts.getMaxRetryWaitTimeInSeconds
  }

  it should "work with generic config" in {
    val config = ConfigFactory.parseString(s"""
      | whisk.cosmosdb {
      |  endpoint = "http://localhost"
      |  key = foo
      |  db  = openwhisk
      | }
         """.stripMargin).withFallback(globalConfig)
    val cosmos = CosmosDBConfig(config, "WhiskAuth")
    cosmos.endpoint shouldBe "http://localhost"
    cosmos.key shouldBe "foo"
    cosmos.db shouldBe "openwhisk"
  }

  it should "work with extended config" in {
    val config = ConfigFactory.parseString(s"""
      | whisk.cosmosdb {
      |  endpoint = "http://localhost"
      |  key = foo
      |  db  = openwhisk
      |  connection-policy {
      |     max-pool-size = 42
      |  }
      | }
         """.stripMargin).withFallback(globalConfig)
    val cosmos = CosmosDBConfig(config, "WhiskAuth")
    cosmos.endpoint shouldBe "http://localhost"
    cosmos.key shouldBe "foo"
    cosmos.db shouldBe "openwhisk"

    cosmos.connectionPolicy.maxPoolSize shouldBe 42
    val policy = cosmos.connectionPolicy.asJava
    val defaultPolicy = JConnectionPolicy.GetDefault()
    policy.getConnectionMode shouldBe defaultPolicy.getConnectionMode
    policy.getRetryOptions.getMaxRetryAttemptsOnThrottledRequests shouldBe defaultPolicy.getRetryOptions.getMaxRetryAttemptsOnThrottledRequests
    policy.getRetryOptions.getMaxRetryWaitTimeInSeconds shouldBe defaultPolicy.getRetryOptions.getMaxRetryWaitTimeInSeconds
  }

  it should "work with specific extended config" in {
    val config = ConfigFactory.parseString(s"""
      | whisk.cosmosdb {
      |  endpoint = "http://localhost"
      |  key = foo
      |  db  = openwhisk
      |  connection-policy {
      |     max-pool-size = 42
      |     retry-options {
      |        max-retry-wait-time = 2 m
      |     }
      |  }
      |  collections {
      |     WhiskAuth = {
      |        connection-policy {
      |           using-multiple-write-locations = true
      |           preferred-locations = [a, b]
      |           connection-mode = Direct
      |        }
      |     }
      |  }
      | }
         """.stripMargin).withFallback(globalConfig)
    val cosmos = CosmosDBConfig(config, "WhiskAuth")
    cosmos.endpoint shouldBe "http://localhost"
    cosmos.key shouldBe "foo"
    cosmos.db shouldBe "openwhisk"

    val policy = cosmos.connectionPolicy.asJava
    policy.isUsingMultipleWriteLocations shouldBe true
    policy.getMaxPoolSize shouldBe 42
    policy.getConnectionMode shouldBe ConnectionMode.Direct
    policy.getPreferredLocations.asScala.toSeq should contain only ("a", "b")
    policy.getRetryOptions.getMaxRetryWaitTimeInSeconds shouldBe 120
  }
}
