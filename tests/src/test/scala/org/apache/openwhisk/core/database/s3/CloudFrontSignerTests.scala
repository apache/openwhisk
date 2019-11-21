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
import akka.http.scaladsl.model.Uri.Path
import com.typesafe.config.ConfigFactory
import java.time.Instant
import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.core.database.s3.S3AttachmentStoreProvider.S3Config
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, Matchers, OptionValues}
import pureconfig._
import pureconfig.generic.auto._

@RunWith(classOf[JUnitRunner])
class CloudFrontSignerTests extends FlatSpec with Matchers with OptionValues {

  val qt = "\"\"\""
  val privateKey =
    """-----BEGIN RSA PRIVATE KEY-----
      |MIIBPAIBAAJBAOY+Q7vyH1SnCUoFIpzqmZe1TNCxiE6zuiMRmjuJqiAzQWdb5hEA
      |ZaC+f7Lcu53IvczZR0KsP4JndzG23rVg/y0CAwEAAQJBAMK+F3x4ppdrUSgSf9xJ
      |cfAnoPlDsA8hZWcUFGgXYJYqKYw3NqoYG5fwyZ7xrwdMhpbdgD++nsBC/JMwUhEB
      |h+ECIQDzj5Tbd7WvfaKGjozwQgHA9u3f53kxCWovpFEngU6VNwIhAPIAkAPnzuDr
      |q3cEyAbM49ozjyc6/NOV6QK65HQj1gC7AiBrax/Ty3At/dL4VVaDgBkV6dHvtj8V
      |CXnzmRzRt43Y8QIhAIzrvPE5RGP/eEqHUz96glhm276Zf+5qBlTbpfrnf0/PAiEA
      |r1vFsvC8+KSHv7XGU1xfeiHHpHxEfDvJlX7/CxeWumQ=
      |-----END RSA PRIVATE KEY-----
      |""".stripMargin

  val keyPairId = "OPENWHISKISFUNTOUSE"
  val configString =
    s"""whisk {
       |  s3 {
       |    bucket = "openwhisk-test"
       |    prefix = "dev"
       |    cloud-front-config {
       |      domain-name = "foo.com"
       |      key-pair-id = "$keyPairId"
       |      private-key = $qt$privateKey$qt
       |      timeout = 10 m
       |    }
       |  }
       |}""".stripMargin

  behavior of "CloudFront config"

  it should "generate a signed url" in {
    val config = ConfigFactory.parseString(configString).withFallback(ConfigFactory.load())
    val s3Config = loadConfigOrThrow[S3Config](config, ConfigKeys.s3)
    val signer = CloudFrontSigner(s3Config.cloudFrontConfig.get)
    val expiration = Instant.now().plusSeconds(s3Config.cloudFrontConfig.get.timeout.toSeconds)
    val uri = signer.getSignedURL("bar")
    val query = uri.query()

    //A signed url is of format
    //https://<domain-name>/<object key>?Expires=xxx&Signature=xxx&Key-Pair-Id=xxx
    uri.scheme shouldBe "https"
    uri.path.tail shouldBe Path("bar")
    query.get("Expires") shouldBe Some(expiration.getEpochSecond.toString)
    query.get("Signature") shouldBe defined
    query.get("Key-Pair-Id").value shouldBe keyPairId
  }
}
