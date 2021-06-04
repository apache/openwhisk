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

package org.apache.openwhisk.core.loadBalancer

import akka.actor.{ActorRefFactory, ActorSystem, Props}
import org.apache.openwhisk.common.{InvokerHealth, Logging, TransactionId}
import org.apache.openwhisk.core.WhiskConfig
import org.apache.openwhisk.core.connector._
import org.apache.openwhisk.core.controller.Controller
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.spi.Spi

import scala.concurrent.Future
import scala.concurrent.duration._

trait LoadBalancer {

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
  def publish(action: ExecutableWhiskActionMetaData, msg: ActivationMessage)(
    implicit transid: TransactionId): Future[Future[Either[ActivationId, WhiskActivation]]]

  /**
   * Returns a message indicating the health of the containers and/or container pool in general.
   *
   * @return a Future[IndexedSeq[InvokerHealth]] representing the health of the pools managed by the loadbalancer.
   */
  def invokerHealth(): Future[IndexedSeq[InvokerHealth]]

  /** Gets the number of in-flight activations for a specific user. */
  def activeActivationsFor(namespace: UUID): Future[Int]

  /** Gets the number of in-flight activations in the system. */
  def totalActiveActivations: Future[Int]

  /** Gets the size of the cluster all loadbalancers are acting in */
  def clusterSize: Int = 1

  /** Gets the throttling for given action. */
  def checkThrottle(namespace: EntityPath, action: String): Boolean = false
}

/**
 * An Spi for providing load balancer implementations.
 */
trait LoadBalancerProvider extends Spi {
  def requiredProperties: Map[String, String]

  def instance(whiskConfig: WhiskConfig, instance: ControllerInstanceId)(implicit actorSystem: ActorSystem,
                                                                         logging: Logging): LoadBalancer

  /** Return default FeedFactory */
  def createFeedFactory(whiskConfig: WhiskConfig, instance: ControllerInstanceId)(implicit actorSystem: ActorSystem,
                                                                                  logging: Logging): FeedFactory = {

    val activeAckTopic = s"${Controller.topicPrefix}completed${instance.asString}"
    val maxActiveAcksPerPoll = 128
    val activeAckPollDuration = 1.second

    new FeedFactory {
      def createFeed(f: ActorRefFactory, provider: MessagingProvider, acker: Array[Byte] => Future[Unit]) = {
        f.actorOf(Props {
          new MessageFeed(
            "activeack",
            logging,
            provider.getConsumer(whiskConfig, activeAckTopic, activeAckTopic, maxPeek = maxActiveAcksPerPoll),
            maxActiveAcksPerPoll,
            activeAckPollDuration,
            acker)
        })
      }
    }
  }
}

/** Exception thrown by the loadbalancer */
case class LoadBalancerException(msg: String) extends Throwable(msg)
