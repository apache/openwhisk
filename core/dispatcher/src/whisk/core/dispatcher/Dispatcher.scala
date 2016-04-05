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
import scala.collection.parallel.mutable.ParTrieMap
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success
import scala.util.matching.Regex.Match
import whisk.common.Counter
import whisk.common.Logging
import whisk.common.TransactionId
import whisk.connector.kafka.KafkaConsumerConnector
import whisk.core.WhiskConfig
import whisk.core.WhiskConfig.servicePort
import whisk.core.WhiskConfig.kafkaHost
import whisk.core.activator.ActivatorService
import whisk.core.connector.{ ActivationMessage => Message }
import whisk.core.connector.MessageConsumer
import whisk.core.invoker.InvokerService
import whisk.utils.ExecutionContextFactory
import scala.util.Try

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

/**
 * A general framework to read messages off a message bus (e.g., kafka)
 * and "dispatch" messages to registered handlers.
 */
trait MessageDispatcher extends Registrar with Logging {
    consumer: MessageConsumer =>

    /**
     * Starts a listener to consume message from connector (e.g., kafka).
     * The listener consumes messages from the bus using a streaming consumer
     * interface. Each message is a JSON object with at least these properties:
     * { path: the topic name,
     *   payload: the message body }
     *
     * Expected topics are "/whisk/invoke[0..n-1]" (handled by Invoker)
     * and "/whisk" (handled by Activator).
     *
     * Expected paths are "/rules/[enable,disable]" and "triggers/fire"
     * (handled by Activator) and "actions/invoke" (handled by Invoker).
     *
     * The paths should generally mirror the REST API.
     *
     * For every message that is received, the listener extracts the path property
     * from the message and checks if there are registered handlers for the message.
     * A handler is registered via addHandler and unregistered via removeHandler.
     * All matches are checked in parallel, and messages are dispatched to all matching
     * handlers. The handling of a message is wrapped in a Future. A handler is skipped
     * if it is not active.
     */
    def start() = if (!started) {
        consumer.onMessage(bytes => {
            val raw = new String(bytes, "utf-8")
            val msg = Message(raw)
            msg match {
                case Success(m) =>
                    implicit val tid = m.transid
                    if (m.path.nonEmpty) {
                        inform(handlers.par) foreach {
                            case (name, handler) =>
                                val matches = handler.matches(m.path)
                                handleMessage(handler, m, matches)
                        }
                    }
                    true
                case Failure(t) =>
                    info(this, errorMsg(raw, t))
                    true
            }
        })
        started = true
    }

    /** Stops the message dispatch and closes the producer and consumer. */
    def stop() = if (started) {
        consumer.close()
        started = false
    }

    private def handleMessage(rule: DispatchRule, msg: Message, matches: Seq[Match]) = {
        implicit val ec = Dispatcher.executionContext
        implicit val tid = msg.transid
        if (matches.nonEmpty) Future {
            val count = counter.next()
            info(this, s"activeCount = $count while handling ${rule.name}")
            rule.doit(msg, matches)
        } flatMap (identity) onComplete {
            case Success(a) => info(this, s"activeCount = ${counter.prev()} after handling $rule")
            case Failure(t) => error(this, s"activeCount = ${counter.prev()} ${errorMsg(rule, t)}")
        }
    }

    private def inform(matchers: ParTrieMap[String, DispatchRule])(implicit transid: TransactionId) = {
        val names = matchers map { _._2.name } reduce (_ + "," + _)
        info(this, s"matching message to ${matchers.size} handlers: $names")
        matchers
    }

    private def errorMsg(handler: DispatchRule, e: Throwable): String =
        s"failed applying handler: $handler\n" + errorMsg(e)

    private def errorMsg(msg: String, e: Throwable) =
        s"failed processing message: $msg\n$e${e.getStackTrace.mkString("", " ", "")}"

    private def errorMsg(e: Throwable): String = {
        if (e.isInstanceOf[java.util.concurrent.ExecutionException])
            s"$e${e.getCause.getStackTrace.mkString("", " ", "")}"
        else
            s"$e${e.getStackTrace.mkString("", " ", "")}"
    }

    private val counter = new Counter()
    private var started = false
}

/**
 * Creates a dispatcher that uses kafka as the message pub/sub connector.
 * This is currently used by invoker and activator.
 *
 * @param config with zookeeper host properties
 * @topic the topic to subscribe to (creates a consumer client for this topic)
 * @groupid the groupid for the topic consumer
 */
class Dispatcher(
    config: WhiskConfig,
    topic: String,
    groupid: String)
    extends KafkaConsumerConnector(config.kafkaHost, groupid, topic)
    with MessageDispatcher {
}

object Dispatcher extends Logging {
    def requiredProperties =
        Map(servicePort -> 8080.toString()) ++ kafkaHost

    val executionContext = ExecutionContextFactory.makeCachedThreadPoolExecutionContext()

    def main(args: Array[String]): Unit = {
        val name = if (args.nonEmpty) args(0).trim.toLowerCase() else ""
        name match {
            case "activator" => ActivatorService.main(args)
            case "invoker"   => InvokerService.main(args)
            case _           => error(Dispatcher, s"unrecognized app $name")
        }
    }
}
