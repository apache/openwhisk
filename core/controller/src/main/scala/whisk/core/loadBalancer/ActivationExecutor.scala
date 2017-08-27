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

package whisk.core.loadBalancer

import akka.actor.ActorSystem
import scala.concurrent.Future
import spray.json.JsObject
import whisk.common.Logging
import whisk.common.TransactionId
import whisk.core.WhiskConfig
import whisk.core.connector.ActivationMessage
import whisk.core.entity.ActivationId
import whisk.core.entity.ExecutableWhiskAction
import whisk.core.entity.InstanceId
import whisk.core.entity.UUID
import whisk.core.entity.WhiskActivation
import whisk.spi.Spi

/**
 * Separates the activation execution from the system level load balancer, to enable multiple resource pools that have
 * distinct execution behaviors. (e.g. concurrent vs sequential, short timeout vs long timeout, etc)
 */
trait ActivationExecutor {
    /**
     * Publishes activation message on internal bus for an invoker to pick up.
     *
     * @param action the action to invoke
     * @param msg the activation message to publish on an invoker topic
     * @param transid the transaction id for the request
     * @return result a nested Future the outer indicating completion of publishing and
     *         the inner the completion of the action (i.e., the result)
     *         if it is ready before timeout (Right) otherwise the activation id (Left).
     *         The future is guaranteed to complete within the declared action time limit
     *         plus a grace period (see activeAckTimeoutGrace).
     */
    def publish(action: ExecutableWhiskAction, msg: ActivationMessage)(
        implicit transid: TransactionId): Future[Future[Either[ActivationId, WhiskActivation]]]

    /** Indicate the priority of this executor, in case multiple executors support execution of a single activation */
    def priority(): Int

    /** Indicate whether this action + activation are supported in this executor */
    def supports(action: ExecutableWhiskAction, msg:ActivationMessage): Boolean

    /**
     * Return a message indicating the health of the containers and/or container pool in general
     * @return a Future[String] representing the heal response that will be sent to the client
     */
    def healthStatus: Future[JsObject]

    /** Name of this executor, used for reporting in health status. */
    def name:String

    /** Gets the number of in-flight activations for a specific user in this executor. */
    def activeActivationsFor(namspace: UUID): Int

    /** Gets the number of in-flight activations in this executor. */
    def totalActiveActivations: Int
}

/**
 * SPI for exposing multiple ActivationExecutors to the system
 */
trait ActivationExecutorsProvider extends Spi {
    def executors(config: WhiskConfig, instance: InstanceId)
            (implicit logging: Logging, actorSystem: ActorSystem): Seq[ActivationExecutor]
}
