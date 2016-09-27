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

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

import akka.actor.ActorSystem
import akka.event.Logging.InfoLevel
import akka.util.Timeout
import akka.util.Timeout.durationToTimeout
import spray.json.JsObject
import whisk.common.TransactionId
import whisk.core.connector.ActivationMessage
import whisk.core.WhiskConfig
import whisk.core.entitlement.EntitlementService
import whisk.core.entitlement.LocalEntitlementService
import whisk.core.entitlement.RemoteEntitlementService
import whisk.core.entity.{ ActivationId, WhiskActivation }
import whisk.core.loadBalancer.LoadBalancerService
import scala.language.postfixOps
import whisk.core.entity.ActivationId.ActivationIdGenerator

object WhiskServices {

    type LoadBalancerReq = (ActivationMessage, TransactionId)

    def requiredProperties = WhiskConfig.loadbalancerHost ++ WhiskConfig.consulServer ++ EntitlementService.requiredProperties

    def consulServer(config: WhiskConfig) = config.consulServer

    /**
     * Creates instance of an entitlement service.
     */
    def entitlementService(config: WhiskConfig, timeout: FiniteDuration = 5 seconds)(
        implicit as: ActorSystem) = {
        // remote entitlement service requires a host:port definition. If not given,
        // i.e., the value equals ":" or ":xxxx", use a local entitlement flow.
        if (config.entitlementHost.startsWith(":")) {
            new LocalEntitlementService(config)
        } else {
            new RemoteEntitlementService(config, timeout)
        }
    }

    /**
     * Creates an instance of a Load Balancer component.
     *
     * @param config the configuration with loadbalancerHost defined
     * @param timeout the duration before timing out the HTTP request
     * @return function that accepts a LoadBalancerReq, posts request to load balancer
     * and returns the HTTP response from the load balancer as a future
     */
    def makeLoadBalancerComponent(config: WhiskConfig, timeout: Timeout = 10 seconds)(
        implicit as: ActorSystem): (LoadBalancerReq => Future[Unit], () => JsObject, (ActivationId, FiniteDuration, TransactionId) => Future[WhiskActivation]) = {
        val loadBalancer = new LoadBalancerService(config, InfoLevel)
        val requestTaker = (lbr: LoadBalancerReq) => { loadBalancer.publish(lbr._1)(lbr._2) }
        (requestTaker, loadBalancer.getInvokerHealth, loadBalancer.queryActivationResponse)
    }

}

/**
 * A trait which defines a few services which a whisk microservice may rely on.
 */
trait WhiskServices {
    /** Whisk configuration object. */
    protected val whiskConfig: WhiskConfig

    /** An entitlement service to check access rights. */
    protected val entitlementService: EntitlementService

    /** A generator for new activation ids. */
    protected val activationId: ActivationIdGenerator

    /** Perform a request to the load balancer.  */
    protected val performLoadBalancerRequest: WhiskServices.LoadBalancerReq => Future[Unit]

    /** Ask load balancer (instead of db) for activation response */
    protected val queryActivationResponse: (ActivationId, FiniteDuration, TransactionId) => Future[WhiskActivation]

    /** The hostname of the consul server */
    protected val consulServer: String
}
