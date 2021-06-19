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

package org.apache.openwhisk.core.database.s3

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import org.scalatest.FlatSpec
import org.apache.openwhisk.common.Logging
import org.apache.openwhisk.core.database.{AttachmentStore, DocumentSerializer}

import scala.reflect.ClassTag

trait S3Aws extends FlatSpec {

  def cloudFrontConfig: String = ""

  def makeS3Store[D <: DocumentSerializer: ClassTag]()(implicit actorSystem: ActorSystem,
                                                       logging: Logging): AttachmentStore = {
    val config = ConfigFactory.parseString(s"""
       |whisk {
       |   s3 {
       |      alpakka {
       |         aws {
       |           credentials {
       |             provider = static
       |             access-key-id = "$accessKeyId"
       |             secret-access-key = "$secretAccessKey"
       |           }
       |           region {
       |             provider = static
       |             default-region = "$region"
       |           }
       |         }
       |      }
       |      bucket = "$bucket"
       |      $cloudFrontConfig
       |    }
       |}
      """.stripMargin).withFallback(ConfigFactory.load()).resolve()
    S3AttachmentStoreProvider.makeStore[D](config)
  }

  override protected def withFixture(test: NoArgTest) = {
    assume(
      secretAccessKey != null,
      "'AWS_SECRET_ACCESS_KEY' env not configured. Configure following " +
        "env variables for test to run. 'AWS_ACCESS_KEY_ID', 'AWS_SECRET_ACCESS_KEY', 'AWS_REGION'")

    require(accessKeyId != null, "'AWS_ACCESS_KEY_ID' env variable not set")
    require(region != null, "'AWS_REGION' env variable not set")

    super.withFixture(test)
  }

  val bucket = Option(System.getenv("AWS_BUCKET")).getOrElse("test-ow-travis")

  val accessKeyId = System.getenv("AWS_ACCESS_KEY_ID")
  val secretAccessKey = System.getenv("AWS_SECRET_ACCESS_KEY")
  val region = System.getenv("AWS_REGION")
}
