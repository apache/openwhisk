/*
 * Copyright 2015-2016 IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
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

import spray.routing.Directives
import spray.routing.Route
import spray.routing.authentication.BasicAuth
import spray.routing.authentication.UserPass
import whisk.common.TransactionId
import whisk.core.entity.WhiskAuth

/** A common trait for secured routes */
trait AuthenticatedRoute extends Directives {
    /** An execution context for futures */
    protected implicit val executionContext: ExecutionContext

    /** Creates HTTP BaiscAuth handler */
    protected def basicauth(implicit transid: TransactionId) =
        BasicAuth(validateCredentials _, realm = "whisk rest service")

    /** Validates credentials against database of subjects */
    protected def validateCredentials(userpass: Option[UserPass])(implicit transid: TransactionId): Future[Option[WhiskAuth]]
}

/** A trait for authenticated routes. */
trait AuthenticatedRouteProvider {
    def routes(user: WhiskAuth)(implicit transid: TransactionId): Route
}