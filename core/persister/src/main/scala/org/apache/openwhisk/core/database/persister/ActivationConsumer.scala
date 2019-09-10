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

package org.apache.openwhisk.core.database.persister

import akka.Done
import akka.actor.ActorSystem
import akka.kafka.scaladsl.Consumer.DrainingControl
import akka.kafka.scaladsl.{Committer, Consumer}
import akka.kafka.{CommitterSettings, ConsumerSettings, Subscriptions}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Keep, Sink}
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.{ByteArrayDeserializer, StringDeserializer}
import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.core.entity.WhiskActivation
import spray.json._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

trait ActivationPersister {
  def persist(wa: WhiskActivation)(implicit tid: TransactionId): Future[Done]
}

case class ActivationConsumer(config: PersisterConfig, persister: ActivationPersister)(implicit system: ActorSystem,
                                                                                       materializer: ActorMaterializer,
                                                                                       logging: Logging) {
  import ActivationConsumer._

  def isRunning: Boolean = !control.isShutdown.isCompleted

  private implicit val ec: ExecutionContext = system.dispatcher

  private val control: DrainingControl[Done] = {
    val committerDefaults = CommitterSettings(system)

    Consumer
      .committableSource(consumerSettings(), Subscriptions.topics(topic))
      .mapAsync(5) { msg =>
        //TODO Make parallelism configurable
        val f = Try(parseActivation(msg.record.value())) match {
          case Success(a) => persist(a)
          case Failure(e) =>
            logging.warn(this, s"Error parsing json for record ${msg.record.key()}" + e)
            Future.successful(Done)
        }
        f.map(_ => msg.committableOffset)
      }
      .via(Committer.flow(committerDefaults))
      .toMat(Sink.ignore)(Keep.both)
      .mapMaterializedValue(DrainingControl.apply)
      .run()
  }

  def shutdown(): Future[Done] = {
    //TODO Lag recording
    control.drainAndShutdown()(system.dispatcher)
  }

  def persist(act: WhiskActivation): Future[Done] = {
    //TODO Once tid is added as meta property then extract it and use that
    //TODO Failure case handling - If there is an issue in storing then stream
    persister.persist(act)(tid)
  }

  private def parseActivation(data: Array[Byte]) = {
    val js = JsonParser(ParserInput(data))
    WhiskActivation.serdes.read(js)
  }

  def consumerSettings(): ConsumerSettings[String, Array[Byte]] =
    ConsumerSettings(system, new StringDeserializer, new ByteArrayDeserializer)
      .withGroupId("activation-persister") //TODO Make it configurable
      .withBootstrapServers(config.kafkaHosts)
      .withProperty(ConsumerConfig.CLIENT_ID_CONFIG, config.clientId)
}

object ActivationConsumer {
  val topic = "activations"
  val tid = TransactionId(TransactionId.systemPrefix + "persister")
}
