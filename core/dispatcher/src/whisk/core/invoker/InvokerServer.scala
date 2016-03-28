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

package whisk.core.invoker

import akka.actor.Actor
import spray.http.StatusCodes.OK
import spray.http.StatusCodes.NotFound
import spray.http.StatusCodes.InternalServerError
import spray.httpx.marshalling._
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._
import spray.json._
import spray.json.JsString
import spray.json.pimpAny
import spray.routing.Directive.pimpApply
import whisk.common.Logging
import whisk.common.TransactionId
import whisk.core.connector.{ ActivationMessage => Message }
import whisk.core.dispatcher.Dispatcher
import whisk.core.entity.ActivationId
import whisk.core.entity.DocId
import whisk.core.entity.Subject
import whisk.http.BasicRasService
import scala.util.Try
import scala.util.Failure
import scala.util.Success
import scala.concurrent.ExecutionContext

/**
 * Implements web server to handle certain REST API calls for testing.
 */
trait InvokerServer
    extends BasicRasService
    with Actor
    with Logging {

    override def actorRefFactory = context
    override def routes(implicit transid: TransactionId) = super.routes ~ getContainer(transid) ~ invoke(transid)
    protected val invokerInstance: Invoker

    // this is used by wskadmin
    def getContainer(implicit transid: TransactionId) = {
        (get & path("api" / "getContainer" / """[\w-]+""".r)) {
            id =>
                complete {
                    val activationId = Try { ActivationId(id) } toOption
                    val container = activationId flatMap { invokerInstance.getContainerName(_) }
                    container match {
                        case Some(name) => (OK, JsObject("name" -> name.toJson))
                        case None       => (NotFound, JsObject("error" -> s"'$id' is not recognized".toJson))
                    }
                }
        }
    }

    // this is used by wskadmin
    def invoke(transid: TransactionId) = {
        (post & path("api" / "invoke" / Rest) & entity(as[JsObject])) {
            (topic, body) =>
                Try { invokeImpl(topic, body, transid) } match {
                    case Success(id) => complete(OK, id.toJsObject)
                    case Failure(t)  => complete(InternalServerError, JsObject("error" -> t.getMessage.toJson))
                }
        }
    }

    private def invokeImpl(topic: String, body: JsObject, transid: TransactionId) = {
        val JsString(subject) = body.fields("subject")
        val args = body.fields("args").asJsObject
        val msg = Message(transid, "", Subject(subject), ActivationId(), Some(args), None)
        invokerInstance.fetchFromStoreAndInvoke(DocId(topic).asDocInfo, msg)(transid) // asynchronous
        msg.activationId
    }

    protected[invoker] implicit val executionContext: ExecutionContext
}
