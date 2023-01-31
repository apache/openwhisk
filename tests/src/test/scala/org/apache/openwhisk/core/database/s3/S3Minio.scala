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

import java.net.ServerSocket

import actionContainers.ActionContainer
import akka.actor.ActorSystem
import com.amazonaws.auth.{AWSStaticCredentialsProvider, BasicAWSCredentials}
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.typesafe.config.ConfigFactory
import common.{SimpleExec, StreamLogging}
import org.scalatest.{BeforeAndAfterAll, FlatSpec}
import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.core.database.{AttachmentStore, DocumentSerializer}

import scala.concurrent.duration._
import scala.reflect.ClassTag

trait S3Minio extends FlatSpec with BeforeAndAfterAll with StreamLogging {
  def makeS3Store[D <: DocumentSerializer: ClassTag]()(implicit actorSystem: ActorSystem,
                                                       logging: Logging): AttachmentStore = {
    val config = ConfigFactory.parseString(s"""
      |whisk {
      |     s3 {
      |      alpakka {
      |         aws {
      |           credentials {
      |             provider = static
      |             access-key-id = "$accessKey"
      |             secret-access-key = "$secretAccessKey"
      |           }
      |           region {
      |             provider = static
      |             default-region = us-west-2
      |           }
      |         }
      |         endpoint-url = "http://localhost:$port"
      |      }
      |      bucket = "$bucket"
      |      $prefixConfig
      |     }
      |}
      """.stripMargin).withFallback(ConfigFactory.load())
    S3AttachmentStoreProvider.makeStore[D](config)
  }

  private val accessKey = "TESTKEY"
  private val secretAccessKey = "TESTSECRET"
  private val port = freePort()
  private val bucket = "test-ow-travis"

  private def prefixConfig = {
    if (bucketPrefix.nonEmpty) s"prefix = $bucketPrefix" else ""
  }

  protected def bucketPrefix: String = ""

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    dockerExec(
      s"run -d -e MINIO_ACCESS_KEY=$accessKey -e MINIO_SECRET_KEY=$secretAccessKey -p $port:9000 minio/minio server /data")
    println(s"Started minio on $port")
    createTestBucket()
  }

  override def afterAll(): Unit = {
    super.afterAll()
    val containerId = dockerExec("ps -q --filter ancestor=minio/minio")
    containerId.split("\n").map(_.trim).foreach(id => dockerExec(s"stop $id"))
    println(s"Stopped minio container")
  }

  def createTestBucket(): Unit = {
    val endpoint = new EndpointConfiguration(s"http://localhost:$port", "us-west-2")
    val client = AmazonS3ClientBuilder.standard
      .withPathStyleAccessEnabled(true)
      .withEndpointConfiguration(endpoint)
      .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials(accessKey, secretAccessKey)))
      .build

    org.apache.openwhisk.utils.retry(client.createBucket(bucket), 6, Some(1.minute))
    println(s"Created bucket $bucket")
  }

  private def dockerExec(cmd: String): String = {
    implicit val tid: TransactionId = TransactionId.testing
    val command = s"${ActionContainer.dockerCmd} $cmd"
    val cmdSeq = command.split(" ").map(_.trim).filter(_.nonEmpty)
    val (out, err, code) = SimpleExec.syncRunCmd(cmdSeq)
    assert(code == 0, s"Error occurred for command '$command'. Exit code: $code, Error: $err")
    out
  }

  private def freePort(): Int = {
    val socket = new ServerSocket(0)
    try socket.getLocalPort
    finally if (socket != null) socket.close()
  }
}
