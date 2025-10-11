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
import org.apache.openwhisk.core.entity.WhiskEntity
import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class S3AttachmentStoreCloudFrontTests extends S3AttachmentStoreBehaviorBase with S3Aws {
  override lazy val store = makeS3Store[WhiskEntity]

  override def storeType: String = "S3_CloudFront"
  override def cloudFrontConfig: String =
    """
      |cloud-front-config {
      |  domain-name = ${CLOUDFRONT_DOMAIN_NAME}
      |  key-pair-id = ${CLOUDFRONT_KEY_PAIR_ID}
      |  private-key = ${CLOUDFRONT_PRIVATE_KEY}
      |}
    """.stripMargin

  override protected def withFixture(test: NoArgTest) = {
    assume(
      System.getenv("CLOUDFRONT_PRIVATE_KEY") != null,
      "Configure following env variables for test " +
        "to run 'CLOUDFRONT_DOMAIN_NAME', 'CLOUDFRONT_KEY_PAIR_ID', 'CLOUDFRONT_PRIVATE_KEY'")
    super.withFixture(test)
  }

  //With CloudFront deletes are not immediate and instead the objects may live in CDN cache until TTL
  override protected val lazyDeletes = true
}
