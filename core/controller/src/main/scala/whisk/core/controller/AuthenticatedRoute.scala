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

import scala.Left
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import spray.http.StatusCodes.InternalServerError
import spray.http.StatusCodes.ServiceUnavailable
import spray.routing.RequestContext
import spray.routing.Route
import spray.routing.authentication.BasicHttpAuthenticator
import spray.routing.authentication.UserPass
import whisk.common.TransactionId
import whisk.core.entity.Identity
import whisk.http.CustomRejection

/** A common trait for secured routes */
trait AuthenticatedRoute {

    /** An execution context for futures */
    protected implicit val executionContext: ExecutionContext

    /** Creates HTTP BasicAuth handler */
    protected def basicauth(implicit transid: TransactionId) = {
        new BasicHttpAuthenticator[Identity](realm = "whisk rest service", validateCredentials _) {
            override def apply(ctx: RequestContext) = {
                super.apply(ctx) recover {
                    case t: IllegalStateException => Left(CustomRejection(InternalServerError))
                    case t                        => Left(CustomRejection(ServiceUnavailable))
                }
            }
        }
    }

    /** Validates credentials against database of subjects */
    protected def validateCredentials(userpass: Option[UserPass])(implicit transid: TransactionId): Future[Option[Identity]]
}

/** A trait for authenticated routes. */
trait AuthenticatedRouteProvider {
    def routes(user: Identity)(implicit transid: TransactionId): Route
}
