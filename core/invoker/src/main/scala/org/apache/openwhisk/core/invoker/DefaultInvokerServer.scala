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

package org.apache.openwhisk.core.invoker

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.headers.BasicHttpCredentials
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.http.BasicRasService
import org.apache.openwhisk.http.ErrorResponse.terminate
import pureconfig.loadConfigOrThrow
import spray.json.PrettyPrinter

import scala.concurrent.ExecutionContext

/**
 * Implements web server to handle certain REST API calls.
 */
class DefaultInvokerServer(val invoker: InvokerCore, systemUsername: String, systemPassword: String)(
  implicit val ec: ExecutionContext,
  val actorSystem: ActorSystem,
  val logger: Logging)
    extends BasicRasService {

  /** Pretty print JSON response. */
  implicit val jsonPrettyResponsePrinter = PrettyPrinter

  override def routes(implicit transid: TransactionId): Route = {
    super.routes ~ extractCredentials {
      case Some(BasicHttpCredentials(username, password)) if username == systemUsername && password == systemPassword =>
        (path("enable") & post) {
          complete(invoker.enable())
        } ~ (path("disable") & post) {
          complete(invoker.disable())
        } ~ (path("isEnabled") & get) {
          complete(invoker.isEnabled())
        }
      case _ => terminate(StatusCodes.Unauthorized)
    }
  }
}

object DefaultInvokerServer extends InvokerServerProvider {

  private val invokerUsername = loadConfigOrThrow[String](ConfigKeys.whiskInvokerUsername)
  private val invokerPassword = loadConfigOrThrow[String](ConfigKeys.whiskInvokerPassword)

  override def instance(
    invoker: InvokerCore)(implicit ec: ExecutionContext, actorSystem: ActorSystem, logger: Logging): BasicRasService =
    new DefaultInvokerServer(invoker, invokerUsername, invokerPassword)
}
