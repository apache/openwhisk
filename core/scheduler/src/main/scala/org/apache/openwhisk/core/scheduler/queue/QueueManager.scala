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

package org.apache.openwhisk.core.scheduler.queue

import akka.actor.ActorRef
import org.apache.openwhisk.core.connector._
import org.apache.openwhisk.core.entity._
import spray.json.{DefaultJsonProtocol, _}
import scala.collection.concurrent.TrieMap
import scala.util.Try

object QueueSize
case class MemoryQueueKey(invocationNamespace: String, docInfo: DocInfo)
case class MemoryQueueValue(queue: ActorRef, isLeader: Boolean)

sealed trait MemoryQueueError extends Product {
  val causedBy: String
}

object MemoryQueueErrorSerdes {

  private implicit val noMessageSerdes = NoActivationMessage.serdes
  private implicit val noQueueSerdes = NoMemoryQueue.serdes
  private implicit val mismatchSerdes = ActionMismatch.serdes

  // format that discriminates based on an additional
  // field "type" that can either be "Cat" or "Dog"
  implicit val memoryQueueErrorFormat = new RootJsonFormat[MemoryQueueError] {
    def write(obj: MemoryQueueError): JsValue =
      JsObject((obj match {
        case msg: NoActivationMessage => msg.toJson
        case msg: NoMemoryQueue       => msg.toJson
        case msg: ActionMismatch      => msg.toJson
      }).asJsObject.fields + ("type" -> JsString(obj.productPrefix)))

    def read(json: JsValue): MemoryQueueError =
      json.asJsObject.getFields("type") match {
        case Seq(JsString("NoActivationMessage")) => json.convertTo[NoActivationMessage]
        case Seq(JsString("NoMemoryQueue"))       => json.convertTo[NoMemoryQueue]
        case Seq(JsString("ActionMismatch"))      => json.convertTo[ActionMismatch]
      }
  }
}

case class NoActivationMessage(noActivationMessage: String = NoActivationMessage.asString)
    extends MemoryQueueError
    with Message {
  override val causedBy: String = noActivationMessage
  override def serialize = NoActivationMessage.serdes.write(this).compactPrint
}

object NoActivationMessage extends DefaultJsonProtocol {
  val asString: String = "no activation message exist"
  def parse(msg: String) = Try(serdes.read(msg.parseJson))
  implicit val serdes = jsonFormat(NoActivationMessage.apply _, "noActivationMessage")
}

case class NoMemoryQueue(noMemoryQueue: String = NoMemoryQueue.asString) extends MemoryQueueError with Message {
  override val causedBy: String = noMemoryQueue
  override def serialize = NoMemoryQueue.serdes.write(this).compactPrint
}

object NoMemoryQueue extends DefaultJsonProtocol {
  val asString: String = "no memory queue exist"
  def parse(msg: String) = Try(serdes.read(msg.parseJson))
  implicit val serdes = jsonFormat(NoMemoryQueue.apply _, "noMemoryQueue")
}

case class ActionMismatch(actionMisMatch: String = ActionMismatch.asString) extends MemoryQueueError with Message {
  override val causedBy: String = actionMisMatch
  override def serialize = ActionMismatch.serdes.write(this).compactPrint
}

object ActionMismatch extends DefaultJsonProtocol {
  val asString: String = "action version does not match"
  def parse(msg: String) = Try(serdes.read(msg.parseJson))
  implicit val serdes = jsonFormat(ActionMismatch.apply _, "actionMisMatch")
}

object QueuePool {
  private val _queuePool = TrieMap[MemoryQueueKey, MemoryQueueValue]()

  private[scheduler] def get(key: MemoryQueueKey) = _queuePool.get(key)

  private[scheduler] def put(key: MemoryQueueKey, value: MemoryQueueValue) = _queuePool.put(key, value)

  private[scheduler] def remove(key: MemoryQueueKey) = _queuePool.remove(key)

  private[scheduler] def countLeader() = _queuePool.count(_._2.isLeader)

  private[scheduler] def clear(): Unit = _queuePool.clear()

  private[scheduler] def size = _queuePool.size

  private[scheduler] def values = _queuePool.values

  private[scheduler] def keys = _queuePool.keys
}

case class CreateQueue(invocationNamespace: String,
                       fqn: FullyQualifiedEntityName,
                       revision: DocRevision,
                       whiskActionMetaData: WhiskActionMetaData)
case class CreateQueueResponse(invocationNamespace: String, fqn: FullyQualifiedEntityName, success: Boolean)
