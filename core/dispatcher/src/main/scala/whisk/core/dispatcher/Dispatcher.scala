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

package whisk.core.dispatcher

import scala.collection.concurrent.TrieMap
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.Failure
import scala.util.Success
import scala.util.matching.Regex.Match

import akka.actor.ActorSystem
import akka.actor.Props
import akka.actor.actorRef2Scala
import whisk.common.Counter
import whisk.common.Logging
import whisk.common.TransactionId
import whisk.core.connector.{ ActivationMessage => Message }
import whisk.core.connector.MessageConsumer
import whisk.core.invoker.InvokerService
import akka.event.Logging.LogLevel

object Dispatcher extends Logging {
    def main(args: Array[String]): Unit = {
        val name = if (args.nonEmpty) args(0).trim.toLowerCase() else ""
        name match {
            case "invoker" => InvokerService.main(args)
            case _         => error(Dispatcher, s"unrecognized app $name")
        }
    }
}

/**
 * Creates a dispatcher that pulls messages from the message pub/sub connector.
 * This is currently used by invoker only. It may be removed in the future and
 * its functionality merged directly with the invoker. The current model allows
 * for different message types to be received by more than one consumer in the
 * same process (via handler registration).
 *
 * @param verbosity level for logging
 * @param consumer the consumer providing messages
 * @param pollDuration the long poll duration (max duration to wait for new messages)
 * @param maxPipelineDepth the maximum number of messages allowed in the queued (even >=2)
 * @param actorSystem an actor system to create actor
 */
@throws[IllegalArgumentException]
class Dispatcher(
    verbosity: LogLevel,
    consumer: MessageConsumer,
    pollDuration: FiniteDuration,
    maxPipelineDepth: Int,
    actorSystem: ActorSystem)
    extends Registrar
    with Logging {

    setVerbosity(verbosity)

    val activationFeed = actorSystem.actorOf(Props(new ActivationFeed(this: Logging, consumer, maxPipelineDepth, pollDuration, process)))

    def start() = activationFeed ! ActivationFeed.FillQueueWithMessages
    def stop() = consumer.close()

    /**
     * Consumes messages from the bus using a streaming consumer
     * interface. Each message is a JSON object with at least these properties:
     * { path: the topic name,
     *   payload: the message body }
     *
     * Expected topics are "/whisk/invoke[0..n-1]" (handled by Invoker).
     * Expected paths "actions/invoke" (handled by Invoker).
     *
     * The paths should generally mirror the REST API.
     *
     * For every message that is received, this method extracts the path property
     * from the message and checks if there are registered handlers for the message.
     * A handler is registered via addHandler and unregistered via removeHandler.
     * All matches are checked in parallel, and messages are dispatched to all matching
     * handlers. The handling of a message is wrapped in a Future. A handler is skipped
     * if it is not active.
     */
    def process(topic: String, bytes: Array[Byte]) = {
        val raw = new String(bytes, "utf-8")
        Message(raw) match {
            case Success(m) =>
                implicit val tid = m.transid
                if (m.path.nonEmpty) inform(handlers) foreach {
                    case (name, handler) =>
                        val matches = handler.matches(m.path)
                        handleMessage(handler, topic, m, matches)
                }
            case Failure(t) => info(this, errorMsg(raw, t))
        }
    }

    private def handleMessage(rule: DispatchRule, topic: String, msg: Message, matches: Seq[Match]) = {
        implicit val tid = msg.transid
        implicit val executionContext = actorSystem.dispatcher

        if (matches.nonEmpty) Future {
            val count = counter.next()
            info(this, s"activeCount = $count while handling ${rule.name}")
            rule.doit(topic, msg, matches) // returns a future which is flat-mapped to hang onComplete
        } flatMap (identity) onComplete {
            case Success(a) => info(this, s"activeCount = ${counter.prev()} after handling $rule")
            case Failure(t) => error(this, s"activeCount = ${counter.prev()} ${errorMsg(rule, t)}")
        }
    }

    private def inform(matchers: TrieMap[String, DispatchRule])(implicit transid: TransactionId) = {
        val names = matchers map { _._2.name } reduce (_ + "," + _)
        info(this, s"matching message to ${matchers.size} handlers: $names")
        matchers
    }

    private def errorMsg(handler: DispatchRule, e: Throwable): String =
        s"failed applying handler '${handler.name}': ${errorMsg(e)}"

    private def errorMsg(msg: String, e: Throwable) =
        s"failed processing message: $msg $e${e.getStackTrace.mkString("", " ", "")}"

    private def errorMsg(e: Throwable): String = {
        if (e.isInstanceOf[java.util.concurrent.ExecutionException]) {
            s"$e${e.getCause.getStackTrace.mkString("", " ", "")}"
        } else {
            s"$e${e.getStackTrace.mkString("", " ", "")}"
        }
    }

    private val counter = new Counter()
}

trait Registrar {
    /**
     * Adds handler for a message. The handler name must be unique, else
     * the new handler replaces a previously added one unless this behavior
     * is overridden by setting replace to false.
     *
     * @param handler is the message handler to add override
     * @param replace indicates whether a new handler should replace an older handler by the same name
     * @return an option dispatch rule, the previous value of the rule if any
     */
    def addHandler(handler: DispatchRule, replace: Boolean): Option[DispatchRule] = {
        if (handler != null && handler.isValid) {
            if (replace) handlers.put(handler.name, handler)
            else handlers.putIfAbsent(handler.name, handler)
        } else None
    }

    /**
     * Removes handlers by name if it exists.
     *
     * @param name is the name of the handler to remove
     * @return the handler just removed if any
     */
    def removeHandler(name: String): Option[DispatchRule] = {
        if (name != null && name.trim.nonEmpty)
            handlers.remove(name)
        else None
    }

    /**
     * Removes handler if it exists.
     *
     * @param handler is the message handler to remove
     * @return the handler just removed if any
     */
    def removeHandler(handler: DispatchRule): Option[DispatchRule] = {
        if (handler != null && handler.isValid) {
            handlers.remove(handler.name)
        } else None
    }

    protected val handlers = new TrieMap[String, DispatchRule]
}
