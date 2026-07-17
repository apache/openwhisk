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
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.security.PrivateKey
import java.time.Instant
import org.apache.pekko.http.scaladsl.model.Uri
import software.amazon.awssdk.services.cloudfront.CloudFrontUtilities
import software.amazon.awssdk.services.cloudfront.internal.auth.Pem
import software.amazon.awssdk.services.cloudfront.model.CannedSignerRequest

import scala.concurrent.duration._

case class CloudFrontConfig(domainName: String,
                            keyPairId: String,
                            privateKey: String,
                            timeout: FiniteDuration = 10.minutes)

case class CloudFrontSigner(config: CloudFrontConfig) extends UrlSigner {
  private val privateKey = createPrivateKey(config.privateKey)
  private val cloudFrontUtils = CloudFrontUtilities.create();

  override def getSignedURL(s3ObjectKey: String): Uri = {
    val resourceUrl = s"https://${config.domainName}/$s3ObjectKey"
    val date = Instant.now().plusSeconds(config.timeout.toSeconds)
    val cannedRequest = CannedSignerRequest
      .builder()
      .resourceUrl(resourceUrl)
      .privateKey(privateKey)
      .keyPairId(config.keyPairId)
      .expirationDate(date)
      .build()
    Uri(cloudFrontUtils.getSignedUrlWithCannedPolicy(cannedRequest).url())
  }

  override def toString: String = s"CloudFront Signer - ${config.domainName}"

  private def createPrivateKey(keyContent: String): PrivateKey = {
    val is = new ByteArrayInputStream(keyContent.getBytes(UTF_8))
    Pem.readPrivateKey(is)
  }
}
