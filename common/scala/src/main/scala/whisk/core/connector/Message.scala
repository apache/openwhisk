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

package whisk.core.connector

import scala.util.Try
import spray.json._
import whisk.common.TransactionId
import whisk.core.entity._

/** Basic trait for messages that are sent on a message bus connector. */
trait Message {

  /**
   * A transaction id to attach to the message.
   */
  val transid = TransactionId.unknown

  /**
   * Serializes message to string. Must be idempotent.
   */
  def serialize: String

  /**
   * String representation of the message. Delegates to serialize.
   */
  override def toString = serialize
}

case class ActivationMessage(override val transid: TransactionId,
                             action: FullyQualifiedEntityName,
                             revision: DocRevision,
                             user: Identity,
                             activationId: ActivationId,
                             rootControllerIndex: ControllerInstanceId,
                             blocking: Boolean,
                             content: Option[JsObject],
                             cause: Option[ActivationId] = None,
                             traceContext: Option[Map[String, String]] = None)
    extends Message {

  override def serialize = ActivationMessage.serdes.write(this).compactPrint

  override def toString = {
    val value = (content getOrElse JsObject.empty).compactPrint
    s"$action?message=$value"
  }

  def causedBySequence: Boolean = cause.isDefined
}

object ActivationMessage extends DefaultJsonProtocol {

  def parse(msg: String) = Try(serdes.read(msg.parseJson))

  private implicit val fqnSerdes = FullyQualifiedEntityName.serdes
  implicit val serdes = jsonFormat10(ActivationMessage.apply)
}

/**
 * When adding fields, the serdes of the companion object must be updated also.
 * The whisk activation field will have its logs stripped.
 */
case class CompletionMessage(override val transid: TransactionId,
                             response: Either[ActivationId, WhiskActivation],
                             invoker: InvokerInstanceId)
    extends Message {

  override def serialize: String = {
    CompletionMessage.serdes.write(this).compactPrint
  }

  override def toString = {
    response.fold(l => l, r => r.activationId).asString
  }
}

object CompletionMessage extends DefaultJsonProtocol {
  implicit def eitherResponse =
    new JsonFormat[Either[ActivationId, WhiskActivation]] {
      def write(either: Either[ActivationId, WhiskActivation]) = either match {
        case Right(a) => a.toJson
        case Left(b)  => b.toJson
      }

      def read(value: JsValue) = value match {
        // per the ActivationId's serializer, it is guaranteed to be a String even if it only consists of digits
        case _: JsString => Left(value.convertTo[ActivationId])
        case _: JsObject => Right(value.convertTo[WhiskActivation])
        case _           => deserializationError("could not read CompletionMessage")
      }
    }

  def parse(msg: String): Try[CompletionMessage] = Try(serdes.read(msg.parseJson))
  private val serdes = jsonFormat3(CompletionMessage.apply)
}

case class PingMessage(instance: InvokerInstanceId) extends Message {
  override def serialize = PingMessage.serdes.write(this).compactPrint
}

object PingMessage extends DefaultJsonProtocol {
  def parse(msg: String) = Try(serdes.read(msg.parseJson))
  implicit val serdes = jsonFormat(PingMessage.apply _, "name")
}

trait EventMessageBody extends Message {
  def typeName: String
}

object EventMessageBody extends DefaultJsonProtocol {

  implicit def format = new JsonFormat[EventMessageBody] {
    def write(eventMessageBody: EventMessageBody) = eventMessageBody match {
      case m: Metric     => m.toJson
      case a: Activation => a.toJson
    }

    def read(value: JsValue) =
      if (value.asJsObject.fields.contains("metricName")) {
        value.convertTo[Metric]
      } else {
        value.convertTo[Activation]
      }
  }
}

case class Activation(name: String,
                      statusCode: Int,
                      duration: Long,
                      waitTime: Long,
                      initTime: Long,
                      kind: String,
                      conductor: Boolean,
                      memory: Int,
                      causedBy: Boolean)
    extends EventMessageBody {
  val typeName = "Activation"
  override def serialize = toJson.compactPrint

  def toJson = Activation.activationFormat.write(this)
}

object Activation extends DefaultJsonProtocol {
  def parse(msg: String) = Try(activationFormat.read(msg.parseJson))
  implicit val activationFormat =
    jsonFormat(
      Activation.apply _,
      "name",
      "statusCode",
      "duration",
      "waitTime",
      "initTime",
      "kind",
      "conductor",
      "memory",
      "causedBy")
}

case class Metric(metricName: String, metricValue: Long) extends EventMessageBody {
  val typeName = "Metric"
  override def serialize = toJson.compactPrint
  def toJson = Metric.metricFormat.write(this).asJsObject
}

object Metric extends DefaultJsonProtocol {
  def parse(msg: String) = Try(metricFormat.read(msg.parseJson))
  implicit val metricFormat = jsonFormat(Metric.apply _, "metricName", "metricValue")
}

case class EventMessage(source: String,
                        body: EventMessageBody,
                        subject: Subject,
                        namespace: String,
                        userId: UUID,
                        eventType: String,
                        timestamp: Long = System.currentTimeMillis())
    extends Message {
  override def serialize = EventMessage.format.write(this).compactPrint
}

object EventMessage extends DefaultJsonProtocol {
  implicit val format =
    jsonFormat(EventMessage.apply _, "source", "body", "subject", "namespace", "userId", "eventType", "timestamp")

  def parse(msg: String) = format.read(msg.parseJson)
}
