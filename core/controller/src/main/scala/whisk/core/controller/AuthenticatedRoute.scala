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

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.model.headers.BasicHttpCredentials
import akka.http.scaladsl.server.directives._
import akka.http.scaladsl.server.directives.AuthenticationResult
import akka.http.scaladsl.model.headers._

import whisk.common.TransactionId
import whisk.core.entity.Identity

/** A common trait for secured routes */
trait AuthenticatedRoute {

    /** An execution context for futures */
    protected implicit val executionContext: ExecutionContext

    /** Creates HTTP BasicAuth handler */
    def basicAuth[A](verify: Option[BasicHttpCredentials] => Future[Option[A]]) = {
        authenticateOrRejectWithChallenge[BasicHttpCredentials, A] { creds =>
            verify(creds).map {
                case Some(t) => AuthenticationResult.success(t)
                case None    => AuthenticationResult.failWithChallenge(HttpChallenges.basic("OpenWhisk secure realm"))
            }
        }
    }

    /** Validates credentials against database of subjects */
    protected def validateCredentials(credentials: Option[BasicHttpCredentials])(implicit transid: TransactionId): Future[Option[Identity]]
}

/** A trait for authenticated routes. */
trait AuthenticatedRouteProvider {
    def routes(user: Identity)(implicit transid: TransactionId): Route
}
