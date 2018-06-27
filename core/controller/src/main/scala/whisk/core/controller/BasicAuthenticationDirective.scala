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

package whisk.core.controller

import akka.actor.ActorSystem
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.directives.{AuthenticationDirective, AuthenticationResult}
import akka.stream.ActorMaterializer

import whisk.common.{Logging, TransactionId}
import whisk.core.entity._

import scala.concurrent.{ExecutionContext, Future}

class AuthenticatedRouteBasicAuth(implicit val actorSystem: ActorSystem,
                                  implicit val httpRequest: HttpRequest,
                                  implicit val materializer: ActorMaterializer,
                                  implicit val logging: Logging)
    extends BasicAuthenticate {
  protected implicit val executionContext = actorSystem.dispatcher
  protected val authStore = WhiskAuthStore.datastore

  /** Creates HTTP BasicAuth handler */
  def basicAuth[A](verify: Option[BasicHttpCredentials] => Future[Option[A]])(
    implicit executionContext: ExecutionContext) = {
    authenticateOrRejectWithChallenge[BasicHttpCredentials, A] { creds =>
      verify(creds).map {
        case Some(t) => AuthenticationResult.success(t)
        case None    => AuthenticationResult.failWithChallenge(HttpChallenges.basic("OpenWhisk secure realm"))
      }
    }
  }

  def getDirective(implicit transid: TransactionId, actorSystem: ActorSystem) = basicAuth(validateCredentials)

}

object BasicAuthenticationDirectiveProvider extends AuthenticationDirectiveProvider {
  override def authenticationDirective(implicit transid: TransactionId,
                                       httpRequest: HttpRequest,
                                       actorSystem: ActorSystem,
                                       materializer: ActorMaterializer,
                                       logging: Logging): AuthenticationDirective[Identity] =
    new AuthenticatedRouteBasicAuth().getDirective
}
