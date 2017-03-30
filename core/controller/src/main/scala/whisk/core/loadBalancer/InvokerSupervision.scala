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

package whisk.core.loadBalancer

import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration._

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorRefFactory
import akka.actor.FSM
import akka.actor.FSM.CurrentState
import akka.actor.FSM.SubscribeTransitionCallBack
import akka.actor.FSM.Transition
import akka.actor.Props
import akka.util.Timeout
import spray.json._
import spray.json.DefaultJsonProtocol._
import whisk.common.AkkaLogging
import whisk.common.ConsulKV.LoadBalancerKeys
import whisk.common.KeyValueStore
import whisk.common.TransactionId
import whisk.core.connector.PingMessage

// Received events
case object GetStatus

// States an Invoker can be in
sealed trait InvokerState { val asString: String }
case object Offline extends InvokerState { val asString = "down" }
case object Healthy extends InvokerState { val asString = "up" }

// Data stored in the Invoker
final case class InvokerInfo()

/**
 * Actor representing a pool of invokers
 *
 * The InvokerPool manages a Invokers through subactors. An new Invoker
 * is registered lazily by sending it a Ping event with the name of the
 * Invoker. Ping events are furthermore forwarded to the respective
 * Invoker for their respective State handling.
 *
 * Note: An Invoker that never sends an initial Ping will not be considered
 * by the InvokerPool and thus might not be caught by monitoring.
 */
class InvokerPool(
    childFactory: (ActorRefFactory, String) => ActorRef,
    kv: KeyValueStore,
    invokerDownCallback: String => Unit) extends Actor {

    implicit val transid = TransactionId.invokerHealth
    implicit val timeout = Timeout(5.seconds)
    implicit val ec = context.dispatcher
    val logging = new AkkaLogging(context.system.log)

    // State of the actor. It's important not to close over these
    // references directly, so they don't escape the Actor.
    val invokers = mutable.HashMap.empty[String, ActorRef]
    val invokerStatus = mutable.HashMap.empty[String, InvokerState]

    def receive = {
        case p: PingMessage =>
            val invoker = invokers.getOrElseUpdate(p.name, {
                logging.info(this, s"registered a new invoker: ${p.name}")(TransactionId.invokerHealth)
                val ref = childFactory(context, p.name)
                ref ! SubscribeTransitionCallBack(self) // register for state change events
                ref
            })
            invoker.forward(p)

        case GetStatus => sender() ! invokerStatus.toMap

        case CurrentState(invoker, currentState: InvokerState) =>
            invokerStatus.update(invoker.path.name, currentState)
            publishStatus()

        case Transition(invoker, oldState: InvokerState, newState: InvokerState) =>
            invokerStatus.update(invoker.path.name, newState)
            newState match {
                case Offline => Future(invokerDownCallback(invoker.path.name))
                case _       =>
            }
            publishStatus()
    }

    def publishStatus() = {
        val json = invokerStatus.toMap.mapValues(_.asString).toJson
        kv.put(LoadBalancerKeys.invokerHealth, json.compactPrint)

        val pretty = invokerStatus.toSeq.sortBy {
            case (name, _) => name.filter(_.isDigit).toInt
        }.map { case (name, state) => s"$name: $state" }
        logging.info(this, s"invoker status changed to ${pretty.mkString(", ")}")
    }
}

object InvokerPool {
    def props(
        f: (ActorRefFactory, String) => ActorRef,
        kv: KeyValueStore,
        cb: String => Unit) = Props(new InvokerPool(f, kv, cb))
}

/**
 * Actor representing an Invoker
 *
 * This finite state-machine represents an Invoker in its possible
 * states "Healthy" and "Offline".
 */
class InvokerActor extends FSM[InvokerState, InvokerInfo] {
    implicit val transid = TransactionId.invokerHealth
    val logging = new AkkaLogging(context.system.log)
    def name = self.path.name

    val healthyTimeout = 10.seconds

    startWith(Healthy, InvokerInfo())

    /**
     * An Offline invoker represents an existing but broken
     * invoker.
     */
    when(Offline) {
        case Event(_: PingMessage, _) => goto(Healthy)
    }

    /**
     * A Healthy invoker is characterized by continuously getting
     * pings. It will go offline if that state is not confirmed
     * for 20 seconds.
     */
    when(Healthy, stateTimeout = healthyTimeout) {
        case Event(_: PingMessage, _) => stay
        case Event(StateTimeout, _)   => goto(Offline)
    }

    onTransition {
        case Healthy -> Offline => logging.warn(this, s"$name is down")
        case Offline -> Healthy => logging.info(this, s"$name is online")
    }

    initialize()
}

object InvokerActor {
    def props() = Props[InvokerActor]
}
