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

package whisk.common

import java.io.{FileInputStream, InputStream}
import java.security.{KeyStore, SecureRandom}
import javax.net.ssl.{KeyManagerFactory, SSLContext, TrustManagerFactory}

import akka.http.scaladsl.ConnectionContext
import akka.stream.TLSClientAuth
import com.typesafe.sslconfig.akka.AkkaSSLConfig
import whisk.core.WhiskConfig
import pureconfig._

object Https {
  case class HttpsConfig(keystorePassword: String,
                         keystoreFlavor: String,
                         keystorePath: String,
                         truststorePath: String,
                         truststorePassword: String,
                         truststoreFlavor: String,
                         clientAuth: String)
  private val httpsConfig = loadConfigOrThrow[HttpsConfig]("whisk.controller.https")

  def getCertStore(password: Array[Char], flavor: String, path: String): KeyStore = {
    val certStorePassword: Array[Char] = password
    val cs: KeyStore = KeyStore.getInstance(flavor)
    val certStore: InputStream = new FileInputStream(path)
    cs.load(certStore, certStorePassword)
    cs
  }

  def connectionContext(config: WhiskConfig, sslConfig: Option[AkkaSSLConfig] = None) = {

    val keyFactoryType = "SunX509"
    val clientAuth = {
      if (httpsConfig.clientAuth.toBoolean)
        Some(TLSClientAuth.need)
      else
        Some(TLSClientAuth.none)
    }

// configure keystore
    val keystorePassword = httpsConfig.keystorePassword.toCharArray
    val ks: KeyStore = getCertStore(keystorePassword, httpsConfig.keystoreFlavor, httpsConfig.keystorePath)
    val keyManagerFactory: KeyManagerFactory = KeyManagerFactory.getInstance(keyFactoryType)
    keyManagerFactory.init(ks, keystorePassword)

// configure truststore
    val truststorePassword = httpsConfig.truststorePassword.toCharArray
    val ts: KeyStore = getCertStore(truststorePassword, httpsConfig.truststoreFlavor, httpsConfig.keystorePath)
    val trustManagerFactory: TrustManagerFactory = TrustManagerFactory.getInstance(keyFactoryType)
    trustManagerFactory.init(ts)

    val sslContext: SSLContext = SSLContext.getInstance("TLS")
    sslContext.init(keyManagerFactory.getKeyManagers, trustManagerFactory.getTrustManagers, new SecureRandom)

    ConnectionContext.https(sslContext, sslConfig, clientAuth = clientAuth)
  }
}
