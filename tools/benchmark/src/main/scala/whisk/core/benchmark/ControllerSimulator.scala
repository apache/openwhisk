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

package whisk.core.benchmark

import java.nio.charset.StandardCharsets
import java.time.Instant

import akka.actor.{ActorSystem, Props}
import breeze.stats.DescriptiveStats
import whisk.common.{AkkaLogging, Logging, TransactionId}
import whisk.core.WhiskConfig
import whisk.core.connector.{ActivationMessage, CompletionMessage, MessageFeed, MessagingProvider}
import whisk.core.database.NoDocumentException
import whisk.core.entity._
import whisk.spi.SpiLoader

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}

object ControllerSimulator {
  def between(start: Instant, end: Instant): FiniteDuration =
    Duration.fromNanos(java.time.Duration.between(start, end).toNanos)

  def main(args: Array[String]): Unit = {
    implicit val as: ActorSystem = ActorSystem()
    implicit val ec: ExecutionContext = as.dispatcher
    implicit val log: Logging = new AkkaLogging(as.log)
    implicit val tid: TransactionId = TransactionId.controller

    val messageCount = sys.env.get("INVOCATIONS").map(_.toInt).getOrElse(10)
    val controllerToSimulate = InstanceId(sys.env.get("CONTROLLER_ID").map(_.toInt).getOrElse(0))
    val invokerToUse = InstanceId(sys.env.get("INVOKER_ID").map(_.toInt).getOrElse(0))

    val actionCode =
      sys.env.getOrElse("ACTION_CODE", "function main() { return new Promise(resolve => setTimeout(resolve, 200)); }")

    val topic = s"invoker${invokerToUse.toInt}"

    val config = new WhiskConfig(
      WhiskConfig.kafkaHost ++ ExecManifest.requiredProperties ++ WhiskEntityStore.requiredProperties)

    /**
     * INITIALIZE MESSAGING
     */
    val messaging = SpiLoader.get[MessagingProvider]
    val producer = messaging.getProducer(config, as.dispatcher)
    val consumer = messaging.getConsumer(config, "completions", s"completed${controllerToSimulate.toInt}", 1000000)

    // Stores all invoked activations to track their completion.
    val activations = TrieMap[ActivationId, (Promise[FiniteDuration], Instant)]()

    as.actorOf(Props {
      new MessageFeed(
        "acks",
        log,
        consumer,
        1000000,
        500.milliseconds,
        bytes => {
          CompletionMessage.parse(new String(bytes, StandardCharsets.UTF_8)).foreach { msg =>
            val id = msg.response.fold(id => id, _.activationId)
            activations.get(id).foreach { case (p, start) => p.success(between(start, Instant.now)) }
          }
          Future.successful(())
        })
    })

    /**
     * INITIALIZE TESTACTION
     */
    val identity = Identity(Subject(), EntityName("test"), AuthKey(), Set())

    ExecManifest.initialize(config)
    val action = ExecManifest.runtimesManifest
      .resolveDefaultRuntime("nodejs:6")
      .map { manifest =>
        new WhiskAction(
          namespace = identity.namespace.toPath,
          name = EntityName("noop"),
          exec = CodeExecAsString(manifest, actionCode, None))
      }
      .get

    val db = WhiskEntityStore.datastore(config)
    val actionF = WhiskAction
      .get(db, action.docid)
      .flatMap { oldAction =>
        WhiskAction.put(db, action.revision(oldAction.rev))(tid, notifier = None)
      }
      .recoverWith {
        case _: NoDocumentException => WhiskAction.put(db, action)(tid, notifier = None)
      }

    val actionInfo: DocInfo = Await.result(actionF, 10.minutes)
    log.info(this, "testaction created successfully")

    /**
     * INITIALIZE TESTMESSAGE
     */
    val baseMessage = ActivationMessage(
      transid = TransactionId.unknown, // to be replaced by the testrunner
      action = action.fullyQualifiedName(true),
      revision = actionInfo.rev,
      user = identity,
      activationId = ActivationId(), // to be replaced by the testrunner
      activationNamespace = identity.namespace.toPath,
      rootControllerIndex = controllerToSimulate,
      blocking = true,
      content = None)

    log.info(this, "sending testprobe to rule out Kafka rebalancing")
    val activationId = ActivationId()
    val firstSend =
      producer.send(topic, baseMessage.copy(transid = TransactionId(0), activationId = activationId)).andThen {
        case _ => activations.put(activationId, (Promise[FiniteDuration](), Instant.now))
      }

    Await.ready(firstSend, 10.minutes)
    Await.ready(activations(activationId)._1.future, 10.minutes)

    val begin = Instant.now
    log.info(this, s"sending $messageCount messages")

    val sends = (1 to messageCount).par.map { i =>
      val activationId = ActivationId()
      producer.send(topic, baseMessage.copy(transid = TransactionId(i), activationId = activationId)).andThen {
        case _ => activations.put(activationId, (Promise[FiniteDuration](), Instant.now))
      }
    }

    // Sends are out
    Await.ready(Future.sequence(sends.seq), 10.minutes)

    log.info(this, "sending finished")

    // Await all activations
    val results = Await.result(Future.sequence(activations.mapValues(_._1.future).values), 10.minutes)
    val end = Instant.now

    log.info(this, "invocations finished, dumping report")

    val millis = results.map(_.toMillis.toDouble)

    println("===== REPORT =====")
    println("")
    println(s"Took ${between(begin, end)}")
    println(s"Requests/sec: ${messageCount.toDouble / between(begin, end).toMillis * 1000} ")
    println("")
    println("===== DISTRIBUTION/STATISTICS =====")
    val percentiles = Seq(0.5, 0.75, 0.90, 0.99, 1)
    percentiles.foreach(p => {
      println(s"${(p * 100).toInt}th: ${DescriptiveStats.percentile(millis, p)}")
    })
    val stats = breeze.stats.meanAndVariance(millis)
    println(stats)
    println("==================")

    producer.close()
    consumer.close()
    as.terminate()
  }
}
