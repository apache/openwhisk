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

package org.apache.openwhisk.test.http

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

import org.apache.pekko.actor.Actor
import org.apache.pekko.actor.ActorLogging
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.model.headers._
import org.apache.pekko.stream.scaladsl._
import org.apache.pekko.pattern.ask
import org.apache.pekko.pattern.pipe
import org.apache.pekko.util.Timeout

object RESTProxy {
  // Orders the proxy to immediately unbind and rebind after the duration has passed.
  case class UnbindFor(duration: FiniteDuration)
}

/**
 * A simple REST proxy that can receive commands to change its behavior (e.g.
 * simulate failures of the proxied service). Not for use in production.
 */
class RESTProxy(val host: String, val port: Int)(val serviceAuthority: Uri.Authority, val useHTTPS: Boolean)
    extends Actor
    with ActorLogging {
  private implicit val actorSystem = context.system
  private implicit val executionContex = actorSystem.dispatcher

  private val destHost = serviceAuthority.host.address
  private val destPort = serviceAuthority.port

  // These change as connections come and go
  private var binding: Option[Http.ServerBinding] = None

  // Public messages
  import RESTProxy._

  // Internal messages
  private case object DoBind
  private case object DoUnbind
  private case class Request(request: HttpRequest)

  // Route requests through messages to this actor, to serialize w.r.t events such as unbinding
  private def mkRequestFlow: Flow[HttpRequest, HttpResponse, _] = {

    Flow.apply[HttpRequest].mapAsync(4) { request =>
      ask(self, Request(request))(timeout = Timeout(1.minute)).mapTo[HttpResponse]
    }
  }

  private def bind(checkState: Boolean = true): Unit = {
    assert(!checkState || binding.isEmpty, "Proxy is already bound")

    if (binding.isEmpty) {
      log.debug(s"[RESTProxy] Binding to '$host:$port'.")
      val b = Await.result(Http().newServerAt(host, port).bindFlow(mkRequestFlow), 5.seconds)
      binding = Some(b)
    }
  }

  private def unbind(checkState: Boolean = true): Unit = {
    assert(!checkState || binding.isDefined, "Proxy is not bound")

    binding.foreach { b =>
      log.debug(s"[RESTProxy] Unbinding from '${b.localAddress}'")
      Await.result(b.unbind(), 5.seconds)
      binding = None
    }
  }

  override def preStart() = bind()

  override def postStop() = unbind(checkState = false)

  override def receive = {
    case UnbindFor(d) =>
      self ! DoUnbind
      actorSystem.scheduler.scheduleOnce(d, self, DoBind)

    case DoUnbind =>
      unbind(checkState = false)

    case DoBind =>
      bind(checkState = false)

    case Request(request) =>
      // If the actor isn't bound to the port / has no materializer,
      // the request is simply dropped.

      log.debug(s"[RESTProxy] Proxying '${request.uri}' to '${serviceAuthority}'")

      val flow = if (useHTTPS) {
        Http().outgoingConnectionHttps(destHost, destPort)
      } else {
        Http().outgoingConnection(destHost, destPort)
      }

      // pekko-http doesn't like us to set those headers ourselves.
      val upstreamRequest = request.withHeaders(headers = request.headers.filter(_ match {
        case `Timeout-Access`(_) => false
        case _                   => true
      }))

      Source
        .single(upstreamRequest)
        .via(flow)
        .runWith(Sink.head)
        .pipeTo(sender)

  }
}
