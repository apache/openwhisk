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

package org.apache.openwhisk.core.connector

import scala.util.Try
import spray.json._
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.entity._
import scala.concurrent.duration._
import java.util.concurrent.TimeUnit
import org.apache.openwhisk.core.entity.ActivationResponse.statusForCode

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
 * Message that is sent from the invoker to the controller after action is completed or after slot is free again for
 * new actions.
 */
abstract class AcknowledegmentMessage(private val tid: TransactionId) extends Message {
  override val transid: TransactionId = tid
  override def serialize: String = {
    AcknowledegmentMessage.serdes.write(this).compactPrint
  }
}

/**
 * This message is sent from the invoker to the controller, after the slot of an invoker that has been used by the
 * current action, is free again (after log collection)
 */
case class CompletionMessage(override val transid: TransactionId,
                             activationId: ActivationId,
                             isSystemError: Boolean,
                             invoker: InvokerInstanceId)
    extends AcknowledegmentMessage(transid) {

  override def toString = {
    activationId.asString
  }
}

object CompletionMessage extends DefaultJsonProtocol {
  def parse(msg: String): Try[CompletionMessage] = Try(serdes.read(msg.parseJson))
  implicit val serdes = jsonFormat4(CompletionMessage.apply)
}

/**
 * That message will be sent from the invoker to the controller after action completion if the user wants to have
 * the result immediately (blocking activation).
 * When adding fields, the serdes of the companion object must be updated also.
 * The whisk activation field will have its logs stripped.
 */
case class ResultMessage(override val transid: TransactionId, response: Either[ActivationId, WhiskActivation])
    extends AcknowledegmentMessage(transid) {

  override def toString = {
    response.fold(l => l, r => r.activationId).asString
  }
}

object ResultMessage extends DefaultJsonProtocol {
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
        case _           => deserializationError("could not read ResultMessage")
      }
    }

  def parse(msg: String): Try[ResultMessage] = Try(serdes.read(msg.parseJson))
  implicit val serdes = jsonFormat2(ResultMessage.apply)
}

object AcknowledegmentMessage extends DefaultJsonProtocol {
  def parse(msg: String): Try[AcknowledegmentMessage] = {
    Try(serdes.read(msg.parseJson))
  }

  implicit val serdes = new RootJsonFormat[AcknowledegmentMessage] {
    override def write(obj: AcknowledegmentMessage): JsValue = {
      obj match {
        case c: CompletionMessage => c.toJson
        case r: ResultMessage     => r.toJson
      }
    }

    override def read(json: JsValue): AcknowledegmentMessage = {
      json.asJsObject
      // The field invoker is only part of the CompletionMessage. If this field is part of the JSON, we try to convert
      // it to a CompletionMessage. Otherwise to a ResultMessage.
      // If both conversions fail, an error will be thrown that needs to be handled.
        .getFields("invoker")
        .headOption
        .map(_ => json.convertTo[CompletionMessage])
        .getOrElse(json.convertTo[ResultMessage])
    }
  }
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
                      duration: Duration,
                      waitTime: Duration,
                      initTime: Duration,
                      kind: String,
                      conductor: Boolean,
                      memory: Int,
                      causedBy: Option[String])
    extends EventMessageBody {
  val typeName = Activation.typeName
  override def serialize = toJson.compactPrint

  def toJson = Activation.activationFormat.write(this)

  def status: String = statusForCode(statusCode)

  def isColdStart: Boolean = initTime != Duration.Zero

  def namespace: String = getNamespaceAndActionName(name: String)._1

  def action: String = getNamespaceAndActionName(name: String)._2

  /**
    * Extract namespace and action from name
    * ex. whisk.system/apimgmt/createApi -> (whisk.system, apimgmt/createApi)
    */
  def getNamespaceAndActionName(name: String): (String, String) = {
    val nameArr = name.split("/", 2)
    (nameArr(0), nameArr(1))
  }
}

object Activation extends DefaultJsonProtocol {

  val typeName = "Activation"
  def parse(msg: String) = Try(activationFormat.read(msg.parseJson))

  private implicit val durationFormat = new RootJsonFormat[Duration] {
    override def write(obj: Duration): JsValue = obj match {
      case o if o.isFinite() => JsNumber(o.toMillis)
      case _                 => JsNumber.zero
    }

    override def read(json: JsValue): Duration = json match {
      case JsNumber(n) if n <= 0 => Duration.Zero
      case JsNumber(n)           => toDuration(n.longValue())
    }
  }

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

  /** Constructs an "Activation" event from a WhiskActivation */
  def from(a: WhiskActivation): Try[Activation] = {
    for {
      // There are no sensible defaults for these fields, so they are required. They should always be there but there is
      // no static analysis to proof that so we're defensive here.
      fqn <- a.annotations.getAs[String](WhiskActivation.pathAnnotation)
      kind <- a.annotations.getAs[String](WhiskActivation.kindAnnotation)
    } yield {
      Activation(
        fqn,
        a.response.statusCode,
        toDuration(a.duration.getOrElse(0)),
        toDuration(a.annotations.getAs[Long](WhiskActivation.waitTimeAnnotation).getOrElse(0)),
        toDuration(a.annotations.getAs[Long](WhiskActivation.initTimeAnnotation).getOrElse(0)),
        kind,
        a.annotations.getAs[Boolean](WhiskActivation.conductorAnnotation).getOrElse(false),
        a.annotations
          .getAs[ActionLimits](WhiskActivation.limitsAnnotation)
          .map(_.memory.megabytes)
          .getOrElse(0),
        a.annotations.getAs[String](WhiskActivation.causedByAnnotation).toOption)
    }
  }

  def toDuration(milliseconds: Long) = new FiniteDuration(milliseconds, TimeUnit.MILLISECONDS)
}

case class Metric(metricName: String, metricValue: Long) extends EventMessageBody {
  val typeName = "Metric"
  override def serialize = toJson.compactPrint
  def toJson = Metric.metricFormat.write(this).asJsObject
}

object Metric extends DefaultJsonProtocol {
  val typeName = "Metric"
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

  def from(a: WhiskActivation, source: String, userId: UUID): Try[EventMessage] = {
    Activation.from(a).map { body =>
      EventMessage(source, body, a.subject, a.namespace.toString, userId, body.typeName)
    }
  }

  def parse(msg: String) = Try(format.read(msg.parseJson))
}
