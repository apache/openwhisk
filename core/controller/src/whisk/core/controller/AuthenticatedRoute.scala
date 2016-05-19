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

import scala.Left
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Failure
import scala.util.Success

import spray.http.StatusCodes.InternalServerError
import spray.http.StatusCodes.ServiceUnavailable
import spray.routing.Rejection
import spray.routing.RequestContext
import spray.routing.Route
import spray.routing.authentication.BasicHttpAuthenticator
import spray.routing.authentication.UserPass
import whisk.common.TransactionId
import whisk.core.entity.WhiskAuth
import whisk.http.CustomRejection

/** A common trait for secured routes */
trait AuthenticatedRoute {

    /** An execution context for futures */
    protected implicit val executionContext: ExecutionContext

    /** Creates HTTP BaiscAuth handler */
    protected def basicauth(implicit transid: TransactionId) = {
        new BasicHttpAuthenticator[WhiskAuth](realm = "whisk rest service", validateCredentials _) {
            override def apply(ctx: RequestContext) = {
                val promise = Promise[Either[Rejection, WhiskAuth]]
                super.apply(ctx) onComplete {
                    case Success(t)                        => promise.success(t)
                    case Failure(t: IllegalStateException) => promise.success(Left(CustomRejection(InternalServerError)))
                    case Failure(t)                        => promise.success(Left(CustomRejection(ServiceUnavailable)))
                }
                promise.future
            }
        }
    }

    /** Validates credentials against database of subjects */
    protected def validateCredentials(userpass: Option[UserPass])(implicit transid: TransactionId): Future[Option[WhiskAuth]]
}

/** A trait for authenticated routes. */
trait AuthenticatedRouteProvider {
    def routes(user: WhiskAuth)(implicit transid: TransactionId): Route
}
