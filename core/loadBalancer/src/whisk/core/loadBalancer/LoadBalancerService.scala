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

package whisk.core.loadBalancer

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success

import spray.http.StatusCodes.InternalServerError
import spray.http.StatusCodes.OK
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._
import spray.json.JsObject
import whisk.common.Logging
import whisk.common.TransactionId
import whisk.core.connector.{ ActivationMessage => Message }
import whisk.core.connector.LoadBalancerResponse
import whisk.http.BasicRasService

trait LoadBalancerService
    extends BasicRasService
    with Logging {

    /** The execution context for futures */
    implicit val executionContext: ExecutionContext

    override def routes(implicit transid: TransactionId) = super.routes ~ publish ~ invokers

    def publish(implicit transid: TransactionId) = {
        (path("publish" / s"""(${Message.ACTIVATOR}|${Message.INVOKER})""".r) & post & entity(as[Message])) {
            (component, message) =>
                onComplete(doPublish(component, message)(message.transid)) {
                    case Success(response) => complete(OK, response)
                    case Failure(t)        => complete(InternalServerError)
                }
        }
    }

    val invokers = {
        (path("invokers") & get) {
            complete {
                getInvokerHealth()
            }
        }
    }

    /**
     * Handles POST /publish/topic URI.
     *
     * @param component the component name extracted from URI (invoker, or activator)
     * @param msg the Message received via POST
     * @return response to terminate HTTP connection with
     */
    def doPublish(component: String, msg: Message)(implicit transid: TransactionId): Future[LoadBalancerResponse]

    /**
     * Handles GET /invokers URI.
     *
     * @return JSON of invoker health
     */
    def getInvokerHealth(): JsObject
}