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

package org.apache.openwhisk.extension.whisk

import java.net.URL

import com.softwaremill.quicklens._
import io.gatling.core.config.GatlingConfiguration
import io.gatling.http.Predef._
import io.gatling.core.Predef._
import io.gatling.http.protocol.HttpProtocol

import scala.language.implicitConversions

/**
 * This is the OpenWhiskProtocol.
 *
 * @param apiHost url or address to connect to
 * @param protocol protocol to use. e.g. https
 * @param port port to use. e.g. 443
 */
case class OpenWhiskProtocol(apiHost: String, protocol: String = "https", port: Int = 443)

object OpenWhiskProtocol {

  def apply(url: String): OpenWhiskProtocol = {
    if (url.startsWith("http://") || url.startsWith("https://")) {
      val u = new URL(url)
      val port = if (u.getPort > 0) u.getPort else u.getDefaultPort
      OpenWhiskProtocol(u.getHost, u.toURI.getScheme, port)
    } else new OpenWhiskProtocol(url)
  }
}

case object OpenWhiskProtocolBuilderBase {
  def apiHost(url: String): OpenWhiskProtocolBuilder = OpenWhiskProtocolBuilder(OpenWhiskProtocol(url))
}

object OpenWhiskProtocolBuilder {

  /** convert the OpenWhiskProtocolBuilder to an HttpProtocol. */
  implicit def toHttpProtocol(builder: OpenWhiskProtocolBuilder)(
    implicit configuration: GatlingConfiguration): HttpProtocol = builder.build
}

case class OpenWhiskProtocolBuilder(private val protocol: OpenWhiskProtocol) {

  /** set the api host */
  def apiHost(url: String): OpenWhiskProtocolBuilder = this.modify(_.protocol.apiHost).setTo(url)

  /** set the protocol */
  def protocol(protocol: String): OpenWhiskProtocolBuilder = this.modify(_.protocol.protocol).setTo(protocol)

  /** set the port */
  def port(port: Int): OpenWhiskProtocolBuilder = this.modify(_.protocol.port).setTo(port)

  /** build the http protocol with the parameters provided by the openwhisk-protocol. */
  def build(implicit configuration: GatlingConfiguration) = {
    http
      .baseUrl(s"${protocol.protocol}://${protocol.apiHost}:${protocol.port}")
      .contentTypeHeader("application/json")
      .userAgentHeader("gatlingLoadTest")
      .warmUp("http://google.com")
      .build
  }
}
