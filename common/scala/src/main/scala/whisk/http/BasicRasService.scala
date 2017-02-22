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

import akka.actor.Actor
import akka.actor.ActorSystem
import akka.event.Logging
import akka.japi.Creator
import spray.httpx.SprayJsonSupport._
import whisk.common.Logging
import whisk.common.TransactionId

/**
 * This trait extends the BasicHttpService with a standard "ping" endpoint which
 * responds to health queries, intended for monitoring.
 */
trait BasicRasService extends BasicHttpService {

    override def routes(implicit transid: TransactionId) = ping

    override def loglevelForRoute(route: String): Logging.LogLevel = {
        if (route == "/ping") {
            Logging.DebugLevel
        } else {
            super.loglevelForRoute(route)
        }
    }

    val ping = path("ping") {
        get { complete("pong") }
    }
}

/**
 * Singleton which provides a factory for instances of the BasicRasService.
 */
object BasicRasService {

    def startService(system: ActorSystem, name: String, interface: String, port: Integer)(implicit logging: Logging) = {
        BasicHttpService.startService(system, name, interface, port, new ServiceBuilder)
    }

    /**
     * In spray, we send messages to an Akka Actor. A RasService represents an Actor
     * which extends the BasicRasService trait.
     */
    private class RasService(implicit val logging: Logging) extends BasicRasService with Actor {
        override def actorRefFactory = context
    }

    /**
     * Akka-style factory for RasService.
     */
    private class ServiceBuilder(implicit logging: Logging) extends Creator[RasService] {
        def create = new RasService
    }
}
