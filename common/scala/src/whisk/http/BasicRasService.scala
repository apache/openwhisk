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
import akka.event.Logging
import akka.japi.Creator
import spray.http.MediaTypes.`application/json`
import whisk.common.TransactionId
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._

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

object BasicRasService {

    def startService(name: String, interface: String, port: Integer) = {
        BasicHttpService.startService(name, interface, port, new ServiceBuilder)
    }

    private class RasService extends BasicRasService with Actor {
        override def actorRefFactory = context
    }

    private class ServiceBuilder extends Creator[RasService] {
        def create = new RasService
    }
}