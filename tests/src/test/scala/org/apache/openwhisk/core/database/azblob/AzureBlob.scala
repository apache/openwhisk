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

package org.apache.openwhisk.core.database.azblob

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import org.apache.openwhisk.common.Logging
import org.apache.openwhisk.core.database.{AttachmentStore, DocumentSerializer}
import org.scalatest.FlatSpec

import scala.reflect.ClassTag

trait AzureBlob extends FlatSpec {
  def azureCdnConfig: String = ""

  def makeAzureStore[D <: DocumentSerializer: ClassTag]()(implicit actorSystem: ActorSystem,
                                                          logging: Logging): AttachmentStore = {
    val config = ConfigFactory.parseString(s"""
        |whisk {
        |  azure-blob {
        |    endpoint = "$endpoint"
        |    account-name = "$accountName"
        |    container-name = "$containerName"
        |    account-key = "$accountKey"
        |    prefix = $prefix
        |    $azureCdnConfig
        |  }
        |}""".stripMargin).withFallback(ConfigFactory.load()).resolve()
    AzureBlobAttachmentStoreProvider.makeStore[D](config)
  }

  override protected def withFixture(test: NoArgTest) = {
    assume(
      accountKey != null,
      "'AZ_ACCOUNT_KEY' env not configured. Configure following " +
        "env variables for test to run. 'AZ_ENDPOINT', 'AZ_ACCOUNT_NAME', 'AZ_CONTAINER_NAME'")
    super.withFixture(test)
  }

  val endpoint = System.getenv("AZ_ENDPOINT")
  val accountName = System.getenv("AZ_ACCOUNT_NAME")
  val containerName = sys.env.getOrElse("AZ_CONTAINER_NAME", "test-ow-travis")
  val accountKey = System.getenv("AZ_ACCOUNT_KEY")

  def prefix: String
}
