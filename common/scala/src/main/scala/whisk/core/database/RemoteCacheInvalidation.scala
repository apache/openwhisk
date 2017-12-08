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

package whisk.core.database

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import spray.json._
import whisk.common.Logging
import whisk.core.WhiskConfig
import whisk.core.connector.{Message, MessagingProvider}
import whisk.core.entity._
import whisk.spi.SpiLoader

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

case class CacheInvalidationMessage(key: CacheKey, instanceId: String) extends Message {
  override def serialize = CacheInvalidationMessage.serdes.write(this).compactPrint
}

object CacheInvalidationMessage extends DefaultJsonProtocol {
  def parse(msg: String) = Try(serdes.read(msg.parseJson))
  implicit val serdes = jsonFormat(CacheInvalidationMessage.apply _, "key", "instanceId")
}

class RemoteCacheInvalidation(config: WhiskConfig, component: String, instance: InstanceId)(implicit logging: Logging,
                                                                                            as: ActorSystem) {

  implicit private val materializer = ActorMaterializer()
  implicit private val ec = as.dispatcher

  private val topic = "cacheInvalidation"
  private val instanceId = s"$component${instance.toInt}"

  private val msgProvider = SpiLoader.get[MessagingProvider]

  msgProvider.getConsumer(s"$topic$instanceId", topic, 128).runForeach(removeFromLocalCache)

  private val cacheInvalidationProducer = msgProvider.getProducer()

  def notifyOtherInstancesAboutInvalidation(key: CacheKey): Future[Unit] = {
    cacheInvalidationProducer.send(topic, CacheInvalidationMessage(key, instanceId)).map(_ => Unit)
  }

  def invalidateWhiskActionMetaData(key: CacheKey) =
    WhiskActionMetaData.removeId(key)

  private def removeFromLocalCache(raw: String): Unit = {
    CacheInvalidationMessage.parse(raw) match {
      case Success(msg: CacheInvalidationMessage) => {
        if (msg.instanceId != instanceId) {
          WhiskActionMetaData.removeId(msg.key)
          WhiskAction.removeId(msg.key)
          WhiskPackage.removeId(msg.key)
          WhiskRule.removeId(msg.key)
          WhiskTrigger.removeId(msg.key)
        }
      }
      case Failure(t) => logging.error(this, s"failed processing message: $raw with $t")
    }
  }
}
