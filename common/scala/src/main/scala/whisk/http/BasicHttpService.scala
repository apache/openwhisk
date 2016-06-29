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

package whisk.http

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

import akka.actor.Actor
import akka.actor.ActorContext
import akka.actor.ActorSystem
import akka.actor.Props
import akka.event.Logging
import akka.io.IO
import akka.japi.Creator
import akka.pattern.ask
import akka.util.Timeout
import spray.can.Http
import spray.http.ContentType
import spray.http.HttpEntity
import spray.http.HttpRequest
import spray.http.HttpResponse
import spray.http.MediaTypes.`text/plain`
import spray.httpx.SprayJsonSupport.sprayJsonMarshaller
import spray.httpx.marshalling
import spray.httpx.marshalling.ToResponseMarshallable.isMarshallable
import spray.routing.Directive.pimpApply
import spray.routing.Directives
import spray.routing.HttpService
import spray.routing.RejectionHandler
import spray.routing.Route
import spray.routing.directives.DebuggingDirectives
import spray.routing.directives.LogEntry
import spray.routing.directives.LoggingMagnet.forMessageFromFullShow
import whisk.common.Logging
import whisk.common.TransactionCounter
import whisk.common.TransactionId

/**
 * This trait extends the spray HttpService trait with logging and transaction counting
 * facilities common to all OpenWhisk REST services.
 */
trait BasicHttpService extends HttpService with TransactionCounter with Logging {

    /**
     * Gets the actor context.
     */
    implicit def actorRefFactory: ActorContext

    /**
     * Gets the routes implemented by the HTTP service.
     *
     * @param transid the id for the transaction (every request is assigned an id)
     */
    def routes(implicit transid: TransactionId): Route

    /**
     * Gets the log level for a given route. The default is
     * InfoLevel so override as needed.
     *
     * @param the route
     * @return a log level for the route
     */
    def loglevelForRoute(route: String): Logging.LogLevel = Logging.InfoLevel

    /**
     * Receives a message and runs the router.
     */
    def receive = runRoute(
        assignId { implicit transid =>
            DebuggingDirectives.logRequest(logRequestInfo _) {
                routes
            }
        })

    /** Assigns transaction id to every request. */
    protected val assignId = extract(_ => transid())

    /** Rejection handler to terminate connection on a bad request. Delegates to Spray handler. */
    protected def customRejectionHandler(implicit transid: TransactionId) = RejectionHandler {
        case rejections =>
            info(this, s"[REJECT] $rejections")
            BasicHttpService.customRejectionHandler.apply(rejections)
    }

    /** Generates log entry for every request. */
    protected def logRequestInfo(req: HttpRequest)(implicit tid: TransactionId): LogEntry = {
        val m = req.method.toString
        val p = req.uri.path.toString
        val q = req.uri.query.toString
        val l = loglevelForRoute(p)
        LogEntry(s"[$tid] $m $p $q", l)
    }
}

object BasicHttpService extends Directives {
    def startService[T <: Actor](name: String, interface: String, port: Integer, service: Creator[T]) = {
        val system = ActorSystem(name)
        val actor = system.actorOf(Props.create(service), s"$name-service")

        implicit val timeout = Timeout(5 seconds)
        IO(Http)(system) ? Http.Bind(actor, interface, port)
    }

    /** Rejection handler to terminate connection on a bad request. Delegates to Spray handler. */
    def customRejectionHandler(implicit transid: TransactionId) = RejectionHandler {
        // get default rejection message, package it as an ErrorResponse instance
        // which gets serialized into a Json object
        case r if RejectionHandler.Default.isDefinedAt(r) => {
            ctx =>
                RejectionHandler.Default(r) {
                    ctx.withHttpResponseMapped {
                        case resp @ HttpResponse(_, HttpEntity.NonEmpty(ContentType(`text/plain`, _), msg), _, _) =>
                            resp.withEntity(marshalling.marshalUnsafe(ErrorResponse(msg.asString, transid)))
                    }
                }
        }
        case CustomRejection(status, cause) :: _ => complete(status, ErrorResponse(cause, transid))
    }
}
