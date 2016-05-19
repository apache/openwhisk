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

package whisk.common

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import akka.actor.ActorSystem
import akka.io.IO
import akka.pattern.ask
import akka.util.Timeout
import spray.can.Http
import spray.client.pipelining.SendReceive
import spray.client.pipelining.sendReceive

/**
 * Trait which implements a method to make an HTTP Request.
 */
trait HttpClient {
    /** Creates HTTP request client. */
    protected def httpRequest(host: String, port: Int, timeout: Timeout)(
        implicit as: ActorSystem, ec: ExecutionContext): Future[SendReceive] = {
        implicit val duration = timeout
        for (
            Http.HostConnectorInfo(connector, _) <- IO(Http) ? Http.HostConnectorSetup(host, port)
        ) yield sendReceive(connector)
    }
}
