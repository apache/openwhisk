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
import scala.concurrent.Promise
import scala.util.Failure
import scala.util.Success

import spray.routing.authentication.UserPass
import whisk.common.Logging
import whisk.common.TransactionId
import whisk.core.database.NoDocumentException
import whisk.core.entity.AuthKey
import whisk.core.entity.Secret
import whisk.core.entity.UUID
import whisk.core.entity.WhiskAuth
import whisk.core.entity.WhiskAuthStore
import whisk.core.entity.types.AuthStore

object Authenticate {
    /** Required properties for this component */
    def requiredProperties = WhiskAuthStore.requiredProperties
}

/** A trait to validate basic auth credentials */
trait Authenticate extends Logging {

    /** An execution context for futures */
    protected implicit val executionContext: ExecutionContext

    /** Database service to lookup credentials */
    protected val authStore: AuthStore

    /**
     * Validates credentials against the authentication database; may be used in
     * authentication directive.
     */
    def validateCredentials(userpass: Option[UserPass])(implicit transid: TransactionId): Future[Option[WhiskAuth]] = {
        val promise = Promise[Option[WhiskAuth]]
        userpass map { pw =>
            Future {
                // inside a future should these fail to validate
                AuthKey(UUID(pw.user), Secret(pw.pass))
            } flatMap { authkey =>
                info(this, s"authenticate: ${authkey.uuid}")
                WhiskAuth.get(authStore, authkey.uuid) map { result =>
                    if (authkey == result.authkey) {
                        info(this, s"authentication valid")
                        Some(result)
                    } else {
                        info(this, s"authentication not valid")
                        None
                    }
                }
            } onComplete {
                case Success(s) =>
                    promise.success(s)
                case Failure(_: NoDocumentException | _: IllegalArgumentException) =>
                    info(this, s"authentication not valid")
                    promise.success(None)
                case Failure(t) =>
                    info(this, s"authentication error: $t")
                    promise.failure(t)
            }
        } getOrElse {
            info(this, s"credentials are malformed")
            promise.success(None)
        }
        promise.future
    }
}
