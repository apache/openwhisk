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

package org.apache.openwhisk.common

import java.io.{FileInputStream, InputStream}
import java.security.{KeyStore, SecureRandom}
import javax.net.ssl.{KeyManagerFactory, SSLContext, TrustManagerFactory}
import org.apache.pekko.http.scaladsl.{ConnectionContext, HttpsConnectionContext}
import org.apache.pekko.stream.TLSClientAuth

object Https {
  case class HttpsConfig(keystorePassword: String, keystoreFlavor: String, keystorePath: String, clientAuth: String)

  def getCertStore(password: Array[Char], flavor: String, path: String): KeyStore = {
    val cs: KeyStore = KeyStore.getInstance(flavor)
    val certStore: InputStream = new FileInputStream(path)
    cs.load(certStore, password)
    cs
  }

  def httpsInsecureClient(context: SSLContext): HttpsConnectionContext =
    ConnectionContext.httpsClient((host, port) => {
      val engine = context.createSSLEngine(host, port)
      engine.setUseClientMode(true)
      // WARNING: this creates an SSL Engine without enabling endpoint identification/verification procedures
      // Disabling host name verification is a very bad idea, please don't unless you have a very good reason to.
      engine
    })

  def applyHttpsConfig(httpsConfig: HttpsConfig, withDisableHostnameVerification: Boolean = false): SSLContext = {
    val keyFactoryType = "SunX509"
    val clientAuth = {
      if (httpsConfig.clientAuth.toBoolean)
        Some(TLSClientAuth.need)
      else
        Some(TLSClientAuth.none)
    }

    val keystorePassword = httpsConfig.keystorePassword.toCharArray

    val keyStore: KeyStore = KeyStore.getInstance(httpsConfig.keystoreFlavor)
    val keyStoreStream: InputStream = new FileInputStream(httpsConfig.keystorePath)
    keyStore.load(keyStoreStream, keystorePassword)

    val keyManagerFactory: KeyManagerFactory = KeyManagerFactory.getInstance(keyFactoryType)
    keyManagerFactory.init(keyStore, keystorePassword)

    // Currently, we are using the keystore as truststore as well, because the clients use the same keys as the
    // server for client authentication (if enabled).
    // So this code is guided by https://doc.akka.io/docs/akka-http/10.0.9/scala/http/server-side-https-support.html
    // This needs to be reworked, when we fix the keys and certificates.
    val trustManagerFactory: TrustManagerFactory = TrustManagerFactory.getInstance(keyFactoryType)
    trustManagerFactory.init(keyStore)

    val sslContext: SSLContext = SSLContext.getInstance("TLS")
    sslContext.init(keyManagerFactory.getKeyManagers, trustManagerFactory.getTrustManagers, new SecureRandom)
    sslContext
  }

  def connectionContextClient(httpsConfig: HttpsConfig,
                              withDisableHostnameVerification: Boolean = false): HttpsConnectionContext = {
    val sslContext = applyHttpsConfig(httpsConfig, withDisableHostnameVerification)
    connectionContextClient(sslContext, withDisableHostnameVerification)
  }

  def connectionContextClient(sslContext: SSLContext,
                              withDisableHostnameVerification: Boolean): HttpsConnectionContext = {
    if (withDisableHostnameVerification) {
      httpsInsecureClient(sslContext)
    } else {
      ConnectionContext.httpsClient(sslContext)
    }
  }

  def connectionContextServer(httpsConfig: HttpsConfig,
                              withDisableHostnameVerification: Boolean = false): HttpsConnectionContext = {
    val sslContext: SSLContext = applyHttpsConfig(httpsConfig, withDisableHostnameVerification)
    ConnectionContext.httpsServer(sslContext)
  }
}
