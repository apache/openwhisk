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
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

import akka.actor.ActorSystem
import akka.util.Timeout
import akka.util.Timeout.durationToTimeout
import spray.http.HttpRequest
import spray.client.pipelining.Post
import spray.httpx.SprayJsonSupport.sprayJsonMarshaller
import spray.httpx.SprayJsonSupport.sprayJsonUnmarshaller
import spray.json.DefaultJsonProtocol.StringJsonFormat
import spray.json.DefaultJsonProtocol.RootJsObjectFormat
import spray.json.DefaultJsonProtocol.mapFormat
import spray.json.DefaultJsonProtocol.JsValueFormat
import spray.json.RootJsonFormat
import spray.json.JsString
import spray.json.JsObject
import spray.json.pimpAny
import spray.json.pimpString
import spray.httpx.SprayJsonSupport.sprayJsonMarshaller
import spray.httpx.SprayJsonSupport.sprayJsonUnmarshaller
import whisk.common.TransactionId
import whisk.common.Verbosity
import whisk.core.connector.LoadBalancerResponse
import whisk.core.connector.LoadbalancerRequest
import whisk.core.connector.Message
import whisk.core.connector.ActivationMessage
import whisk.core.connector.ActivationMessage.{ publish, INVOKER }
import whisk.core.WhiskConfig
import whisk.core.entitlement.EntitlementService
import whisk.core.entitlement.LocalEntitlementService
import whisk.core.entitlement.RemoteEntitlementService
import whisk.core.loadBalancer.LoadBalancerService
import scala.language.postfixOps

object WhiskServices extends LoadbalancerRequest {

    type LoadBalancerReq = (String, ActivationMessage, TransactionId)

    def requiredProperties = WhiskConfig.loadbalancerHost ++
        WhiskConfig.consulServer ++
        WhiskConfig.entitlementHost

    def consulServer(config: WhiskConfig) = config.consulServer

    /**
     * Creates instance of an entitlement service.
     */
    def entitlementService(config: WhiskConfig, timeout: FiniteDuration = 5 seconds)(
        implicit as: ActorSystem, ec: ExecutionContext) = {
        config.entitlementHost match {
            case EntitlementService.LOCAL_ENTITLEMENT_HOST =>
                new LocalEntitlementService(config)
            case _ =>
                new RemoteEntitlementService(config, timeout, as, ec)
        }
    }

    /**
     * Creates an instance of a Load Balancer service.
     *
     * @param config the configuration with loadbalancerHost defined
     * @param timeout the duration before timing out the HTTP request
     * @return function that accepts a LoadBalancerReq, posts request to load balancer
     * and returns the HTTP response from the load balancer as a future
     */
    def makeLoadBalancerService(config: WhiskConfig, timeout: Timeout = 10 seconds)(
        implicit as: ActorSystem, ec: ExecutionContext): LoadBalancerReq => Future[LoadBalancerResponse] = {
        // This connects to a separate LoadBalancer micro-service.
        val requester = request(config.loadbalancerHost, timeout)
        (lbr: LoadBalancerReq) => { requester(Post(publish(lbr._1), lbr._2.toJson.asJsObject)) }
    }

    /**
     * Creates an internal load balancer component for use.
     * The signature is different here so we can leak the LoadBalancerService out due to
     * the Activator needing access to the LoadBalancer as a passthrough.
     */
    def makeLoadBalancerComponent(config: WhiskConfig, timeout: Timeout = 10 seconds)(
        implicit as: ActorSystem, ec: ExecutionContext): (LoadBalancerReq => Future[LoadBalancerResponse], () => JsObject) = {
        val loadBalancer = new LoadBalancerService(config, Verbosity.Loud)
        val requestTaker = (lbr: LoadBalancerReq) => { loadBalancer.doPublish(lbr._1, lbr._2)(lbr._3) }
        (requestTaker, loadBalancer.getInvokerHealth)
    }

}

/**
 * A trait which defines a few services which a whisk microservice may rely on.
 */
trait WhiskServices {
    /** An entitlement service to check access rights. */
    protected val entitlementService: EntitlementService

    /** Synchronously perform a request to the load balancer.  */
    protected val performLoadBalancerRequest: WhiskServices.LoadBalancerReq => Future[LoadBalancerResponse]

    /** The hostname of the consul server */
    protected val consulServer: String
}
