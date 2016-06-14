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

package whisk.core.connector

import scala.concurrent.ExecutionContext
import scala.language.postfixOps
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

import akka.actor.ActorSystem
import akka.util.Timeout
import akka.util.Timeout.durationToTimeout
import spray.client.pipelining.unmarshal
import spray.http.HttpRequest
import spray.httpx.SprayJsonSupport.sprayJsonUnmarshaller
import spray.json.DefaultJsonProtocol
import whisk.common.HttpClient
import whisk.core.entity.ActivationId

case class LoadBalancerResponse private (id: Option[ActivationId] = None, error: Option[String] = None)

object LoadBalancerResponse extends DefaultJsonProtocol {
    def error(msg: String) = LoadBalancerResponse(error = Some(msg))
    def id(id: ActivationId) = LoadBalancerResponse(id = Some(id))

    implicit val serdes = jsonFormat2(LoadBalancerResponse.apply)
}

trait LoadbalancerRequest extends HttpClient {
    /**
     * Creates an HTTP client to post requests to the Load Balancer.
     *
     * @param loadbalancerHost the load balancer host:port (required in this format)
     * @param timeout the duration before timing out the HTTP request
     * @return function that accepts an HttpRequest, posts request to load balancer
     * and returns the HTTP response from the load balancer as a future
     */
    protected def request(loadbalancerHost: String, timeout: Timeout = 5 seconds)(
        implicit as: ActorSystem, ec: ExecutionContext): HttpRequest => Future[LoadBalancerResponse] = {
        val loadbalancer = loadbalancerHost.split(":")
        (req: HttpRequest) => {
            httpRequest(loadbalancer(0), loadbalancer(1).toInt, timeout) flatMap {
                _(req) map { unmarshal[LoadBalancerResponse] }
            }
        }
    }
}
