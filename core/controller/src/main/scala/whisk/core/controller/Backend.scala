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

import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

import akka.actor.ActorSystem
import akka.event.Logging.InfoLevel
import whisk.core.WhiskConfig
import whisk.core.entitlement._
import whisk.core.loadBalancer.{ LoadBalancer, LoadBalancerService }
import scala.language.postfixOps
import whisk.core.entity.ActivationId.ActivationIdGenerator
import whisk.core.iam.NamespaceProvider

object WhiskServices {

    def requiredProperties = WhiskConfig.loadbalancerHost ++ WhiskConfig.consulServer ++ EntitlementProvider.requiredProperties

    def consulServer(config: WhiskConfig) = config.consulServer

    /**
     * Creates instance of an entitlement service.
     */
    def entitlementService(config: WhiskConfig, loadBalancer: LoadBalancer, iam: NamespaceProvider, timeout: FiniteDuration = 5 seconds)(
        implicit as: ActorSystem) = {
        // remote entitlement service requires a host:port definition. If not given,
        // i.e., the value equals ":" or ":xxxx", use a local entitlement flow.
        if (config.entitlementHost.startsWith(":")) {
            new LocalEntitlementProvider(config, loadBalancer, iam)
        } else {
            new RemoteEntitlementService(config, loadBalancer, iam, timeout)
        }
    }

    /**
     * Creates instance of an identity provider.
     */
    def iamProvider(config: WhiskConfig, timeout: FiniteDuration = 5 seconds)(implicit as: ActorSystem) = {
        new NamespaceProvider(config, timeout)
    }

    /**
     * Creates an instance of a Load Balancer component.
     *
     * @param config the configuration with loadbalancerHost defined
     * @return a load balancer component
     */
    def makeLoadBalancerComponent(config: WhiskConfig)(implicit as: ActorSystem) = new LoadBalancerService(config, InfoLevel)

}

/**
 * A trait which defines a few services which a whisk microservice may rely on.
 */
trait WhiskServices {
    /** Whisk configuration object. */
    protected val whiskConfig: WhiskConfig

    /** An entitlement service to check access rights. */
    protected val entitlementProvider: EntitlementProvider

    /** An identity provider. */
    protected val iam: NamespaceProvider

    /** A generator for new activation ids. */
    protected val activationIdFactory: ActivationIdGenerator

    /** A load balancing service that launches invocations. */
    protected val loadBalancer: LoadBalancer

    /** The hostname of the consul server. */
    protected val consulServer: String
}
