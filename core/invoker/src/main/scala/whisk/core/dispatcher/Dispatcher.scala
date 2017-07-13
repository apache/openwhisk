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

package whisk.core.dispatcher

import java.nio.charset.StandardCharsets

import scala.collection.concurrent.TrieMap
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.Failure
import scala.util.Success

import akka.actor.ActorSystem
import akka.actor.Props
import akka.actor.actorRef2Scala
import whisk.common.Counter
import whisk.common.Logging
import whisk.core.connector.ActivationMessage
import whisk.core.connector.MessageConsumer
import whisk.core.connector.MessageFeed

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
    consumer: MessageConsumer,
    pollDuration: FiniteDuration,
    maxPipelineDepth: Int,
    actorSystem: ActorSystem)(
        implicit logging: Logging)
    extends Registrar {

    // create activation request feed but do not start it, until the invoker is registered
    val activationFeed = actorSystem.actorOf(Props(new MessageFeed("activation", logging, consumer, maxPipelineDepth, pollDuration, process, autoStart = false)))

    def start() = activationFeed ! MessageFeed.Ready
    def stop() = consumer.close()

    /**
     * Consumes activation messages from the bus using a streaming consumer
     * interface. Each message is a JSON object serialization of ActivationMessage.
     *
     * For every message that is received, process it with all attached handlers.
     * A handler is registered via addHandler and unregistered via removeHandler.
     * There is typically only one handler.
     */
    def process(bytes: Array[Byte]): Future[Unit] = Future {
        val raw = new String(bytes, StandardCharsets.UTF_8)
        ActivationMessage.parse(raw) match {
            case Success(m) =>
                handlers foreach {
                    case (name, handler) => handleMessage(handler, m)
                }
            case Failure(t) => logging.info(this, errorMsg(raw, t))
        }
    }

    private def handleMessage(handler: MessageHandler, msg: ActivationMessage): Unit = {
        implicit val tid = msg.transid

        Future {
            val count = counter.next()
            logging.debug(this, s"activeCount = $count while handling ${handler.name}")
            handler.onMessage(msg) // returns a future which is flat-mapped via identity to hang onComplete
        } flatMap (identity) onComplete {
            case Success(a) => logging.debug(this, s"activeCount = ${counter.prev()} after handling ${handler.name}")
            case Failure(t) => logging.error(this, s"activeCount = ${counter.prev()} ${errorMsg(handler, t)}")
        }
    }

    private def errorMsg(handler: MessageHandler, e: Throwable): String = {
        s"failed applying handler '${handler.name}': ${errorMsg(e)}"
    }

    private def errorMsg(msg: String, e: Throwable): String = {
        s"failed processing message: $msg $e${e.getStackTrace.mkString("", " ", "")}"
    }

    private def errorMsg(e: Throwable): String = {
        if (e.isInstanceOf[java.util.concurrent.ExecutionException]) {
            s"$e${e.getCause.getStackTrace.mkString("", " ", "")}"
        } else {
            s"$e${e.getStackTrace.mkString("", " ", "")}"
        }
    }

    private val counter = new Counter()
    private implicit val executionContext = actorSystem.dispatcher
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
    def addHandler(handler: MessageHandler, replace: Boolean): Option[MessageHandler] = {
        if (handler != null) {
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
    def removeHandler(name: String): Option[MessageHandler] = {
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
    def removeHandler(handler: MessageHandler): Option[MessageHandler] = {
        if (handler != null) {
            handlers.remove(handler.name)
        } else None
    }

    protected val handlers = new TrieMap[String, MessageHandler]
}
