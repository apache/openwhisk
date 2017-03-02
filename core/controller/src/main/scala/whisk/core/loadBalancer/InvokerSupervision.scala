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
import whisk.common.AkkaLogging
import akka.actor.FSM.SubscribeTransitionCallBack
import akka.actor.FSM.Transition
import whisk.common.TransactionId
import whisk.core.connector.PingMessage

// Received events
case object GetStatus

// States an Invoker can be in
sealed trait InvokerState
case object Offline extends InvokerState
case object Healthy extends InvokerState

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
class InvokerPool(invokerDownCallback: String => Unit) extends Actor {
    implicit val transid = TransactionId.invokerHealth
    val logging = new AkkaLogging(context.system.log)
    implicit val timeout = Timeout(5.seconds)
    implicit val ec = context.dispatcher

    def receive = {
        case p: PingMessage =>
            val invoker = context
                .child(p.name) // get an existing actor
                .getOrElse { // or create a new one lazily
                    logging.info(this, s"registered a new invoker: ${p.name}")(TransactionId.invokerHealth)
                    val ref = context.actorOf(InvokerActor.props, p.name)
                    ref ! SubscribeTransitionCallBack(self) // register for state change events
                    ref
                }
            invoker.forward(p)

        case GetStatus =>
            val list = context.children.map { invoker =>
                val name = invoker.path.name
                invoker.ask(GetStatus).mapTo[InvokerState].map(name -> _)
            }
            pipe(Future.sequence(list).map(_.toMap)) to sender()

        case Transition(invoker, oldState, Offline) => invokerDownCallback(invoker.path.name)
    }
}

object InvokerPool {
    def props(cb: String => Unit) = Props(new InvokerPool(cb))
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
        case Healthy -> Offline => logging.warn(this, s"$name went down")
        case Offline -> Healthy => logging.info(this, s"$name is online")
    }

    initialize()
}

object InvokerActor {
    def props() = Props[InvokerActor]
}
