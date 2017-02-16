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

import scala.concurrent.duration._
import scala.concurrent.Future

import akka.actor.Actor
import akka.actor.FSM
import akka.actor.Props
import akka.pattern.ask
import akka.pattern.pipe
import akka.util.Timeout
import akka.actor.FSM.SubscribeTransitionCallBack
import akka.actor.FSM.Transition
import whisk.common.AkkaLogging
import whisk.common.TransactionId
import whisk.core.connector.PingMessage
import whisk.common.KeyValueStore
import spray.json._
import spray.json.DefaultJsonProtocol._
import whisk.common.ConsulKV.LoadBalancerKeys

// Received events
case object GetStatus
case object StatusChange

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
class InvokerPool(kv: KeyValueStore, invokerDownCallback: String => Unit) extends Actor {
    implicit val transid = TransactionId.invokerHealth
    val logging = new AkkaLogging(context.system.log)
    implicit val timeout = Timeout(5.seconds)
    implicit val ec = context.dispatcher

    /** Collects the status of all invokers */
    def collectStatus = {
        val list = context.children.map { invoker =>
            val name = invoker.path.name
            invoker.ask(GetStatus).mapTo[InvokerState].map(name -> _)
        }
        Future.sequence(list).map(_.toMap)
    }

    def receive = {
        case p: PingMessage =>
            val invoker = context
                .child(p.name) // get an existing actor
                .getOrElse { // or create a new one lazily
                    logging.info(this, s"registered a new invoker: ${p.name}")(TransactionId.invokerHealth)
                    val ref = context.actorOf(InvokerActor.props, p.name)
                    ref ! SubscribeTransitionCallBack(self) // register for state change events
                    self ! StatusChange
                    ref
                }
            invoker.forward(p)

        case GetStatus => pipe(collectStatus) to sender()

        case StatusChange => collectStatus.foreach { allState =>
            val json = allState.mapValues(_.asString).toJson
            kv.put(LoadBalancerKeys.invokerHealth, json.compactPrint)

            val pretty = allState.toSeq.sortBy {
                case (name, _) => name.drop(7).toInt
            }.map { case (name, state) => s"$name: $state" }
            logging.info(this, s"invoker status changed to ${pretty.mkString(", ")}")
        }

        case Transition(invoker, oldState, newState) =>
            self ! StatusChange
            newState match {
                case Offline => Future(invokerDownCallback(invoker.path.name))
                case _       =>
            }
    }
}

object InvokerPool {
    def props(kv: KeyValueStore, cb: String => Unit) = Props(new InvokerPool(kv, cb))
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

    // This is done at this point to not intermingle with the state-machine
    // especially their timeouts.
    def customReceive: Receive = { case GetStatus => sender() ! stateName }
    override def receive = customReceive.orElse(super.receive)

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
