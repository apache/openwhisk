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
import akka.http.scaladsl.model.StatusCodes._
import java.util.concurrent.TimeUnit

import org.apache.openwhisk.core.entity.ActivationResponse.{statusForCode, ERROR_FIELD}
import org.apache.openwhisk.utils.JsHelpers

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
                             initArgs: Set[String] = Set.empty,
                             lockedArgs: Map[String, String] = Map.empty,
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

/**
 * Message that is sent from the invoker to the controller after action is completed or after slot is free again for
 * new actions.
 */
abstract class AcknowledegmentMessage(private val tid: TransactionId) extends Message {
  override val transid: TransactionId = tid

  override def serialize: String = AcknowledegmentMessage.serdes.write(this).compactPrint

  /** Pithy descriptor for logging. */
  def messageType: String

  /** Does message indicate slot is free? */
  def isSlotFree: Option[InstanceId]

  /** Does message contain a result? */
  def result: Option[Either[ActivationId, WhiskActivation]]

  /**
   * Is the acknowledgement for an activation that failed internally?
   * For some message, this is not relevant and the result is None.
   */
  def isSystemError: Option[Boolean]

  def activationId: ActivationId

  /** Serializes the message to JSON. */
  def toJson: JsValue

  /**
   * Converts the message to a more compact form if it cannot cross the message bus as is or some of its details are not necessary.
   */
  def shrink: AcknowledegmentMessage
}

/**
 * This message is sent from an invoker to the controller in situations when the resource slot and the action
 * result are available at the same time, and so the split-phase notification is not necessary. Instead the message
 * combines the `CompletionMessage` and `ResultMessage`. The `response` may be an `ActivationId` to allow for failures
 * to send the activation result because of event-bus size limitations.
 *
 * The constructor is private so that callers must use the more restrictive constructors which ensure the respose is always
 * Right when this message is created.
 */
case class CombinedCompletionAndResultMessage private (override val transid: TransactionId,
                                                       response: Either[ActivationId, WhiskActivation],
                                                       override val isSystemError: Option[Boolean],
                                                       instance: InstanceId)
    extends AcknowledegmentMessage(transid) {
  override def messageType = "combined"

  override def result = Some(response)

  override def isSlotFree = Some(instance)

  override def activationId = response.fold(identity, _.activationId)

  override def toJson = CombinedCompletionAndResultMessage.serdes.write(this)

  override def shrink = copy(response = response.flatMap(a => Left(a.activationId)))

  override def toString = activationId.asString
}

/**
 * This message is sent from an invoker to the controller, once the resource slot in the invoker (used by the
 * corresponding activation) free again (i.e., after log collection). The `CompletionMessage` is part of a split
 * phase notification to the load balancer where an invoker first sends a `ResultMessage` and later sends the
 * `CompletionMessage`.
 */
case class CompletionMessage private (override val transid: TransactionId,
                                      override val activationId: ActivationId,
                                      override val isSystemError: Option[Boolean],
                                      instance: InstanceId)
    extends AcknowledegmentMessage(transid) {
  override def messageType = "completion"

  override def result = None

  override def isSlotFree = Some(instance)

  override def toJson = CompletionMessage.serdes.write(this)

  override def shrink = this

  override def toString = activationId.asString
}

/**
 * This message is sent from an invoker to the load balancer once an action result is available for blocking actions.
 * This is part of a split phase notification, and does not indicate that the slot is available, which is indicated with
 * a `CompletionMessage`. Note that activation record will not contain any logs from the action execution, only the result.
 *
 * The constructor is private so that callers must use the more restrictive constructors which ensure the respose is always
 * Right when this message is created.
 */
case class ResultMessage private (override val transid: TransactionId, response: Either[ActivationId, WhiskActivation])
    extends AcknowledegmentMessage(transid) {
  override def messageType = "result"

  override def result = Some(response)

  override def isSlotFree = None

  override def isSystemError = response.fold(_ => None, a => Some(a.response.isWhiskError))

  override def activationId = response.fold(identity, _.activationId)

  override def toJson = ResultMessage.serdes.write(this)

  override def shrink = copy(response = response.flatMap(a => Left(a.activationId)))

  override def toString = activationId.asString
}

object ActivationMessage extends DefaultJsonProtocol {
  def parse(msg: String) = Try(serdes.read(msg.parseJson))

  private implicit val fqnSerdes = FullyQualifiedEntityName.serdes
  implicit val serdes = jsonFormat12(ActivationMessage.apply)
}

object CombinedCompletionAndResultMessage extends DefaultJsonProtocol {
  // this constructor is restricted to ensure the message is always created with certain invariants
  private def apply(transid: TransactionId,
                    activation: Either[ActivationId, WhiskActivation],
                    isSystemError: Option[Boolean],
                    instance: InstanceId): CombinedCompletionAndResultMessage =
    new CombinedCompletionAndResultMessage(transid, activation, isSystemError, instance)

  def apply(transid: TransactionId,
            activation: WhiskActivation,
            instance: InstanceId): CombinedCompletionAndResultMessage =
    new CombinedCompletionAndResultMessage(transid, Right(activation), Some(activation.response.isWhiskError), instance)

  implicit private val eitherSerdes = AcknowledegmentMessage.eitherResponse
  implicit val serdes = jsonFormat4(
    CombinedCompletionAndResultMessage
      .apply(_: TransactionId, _: Either[ActivationId, WhiskActivation], _: Option[Boolean], _: InstanceId))
}

object CompletionMessage extends DefaultJsonProtocol {
  // this constructor is restricted to ensure the message is always created with certain invariants
  private def apply(transid: TransactionId,
                    activation: WhiskActivation,
                    isSystemError: Option[Boolean],
                    instance: InstanceId): CompletionMessage =
    new CompletionMessage(transid, activation.activationId, Some(activation.response.isWhiskError), instance)

  def apply(transid: TransactionId, activation: WhiskActivation, instance: InstanceId): CompletionMessage = {
    new CompletionMessage(transid, activation.activationId, Some(activation.response.isWhiskError), instance)
  }

  implicit val serdes = jsonFormat4(
    CompletionMessage.apply(_: TransactionId, _: ActivationId, _: Option[Boolean], _: InstanceId))
}

object ResultMessage extends DefaultJsonProtocol {
  // this constructor is restricted to ensure the message is always created with certain invariants
  private def apply(transid: TransactionId, response: Either[ActivationId, WhiskActivation]): ResultMessage =
    new ResultMessage(transid, response)

  def apply(transid: TransactionId, activation: WhiskActivation): ResultMessage =
    new ResultMessage(transid, Right(activation))

  implicit private val eitherSerdes = AcknowledegmentMessage.eitherResponse
  implicit val serdes = jsonFormat2(ResultMessage.apply(_: TransactionId, _: Either[ActivationId, WhiskActivation]))
}

object AcknowledegmentMessage extends DefaultJsonProtocol {
  def parse(msg: String): Try[AcknowledegmentMessage] = Try(serdes.read(msg.parseJson))

  protected[connector] val eitherResponse = new JsonFormat[Either[ActivationId, WhiskActivation]] {
    def write(either: Either[ActivationId, WhiskActivation]) = either.fold(_.toJson, _.toJson)

    def read(value: JsValue) = value match {
      case _: JsString =>
        // per the ActivationId serializer, an activation id is a String even if it only consists of digits
        Left(value.convertTo[ActivationId])

      case _: JsObject => Right(value.convertTo[WhiskActivation])
      case _           => deserializationError("could not read ResultMessage")
    }
  }

  implicit val serdes = new RootJsonFormat[AcknowledegmentMessage] {
    override def write(m: AcknowledegmentMessage): JsValue = m.toJson

    // The field invoker is only part of CombinedCompletionAndResultMessage and CompletionMessage.
    // If this field is part of the JSON, we try to deserialize into one of these two types,
    // and otherwise to a ResultMessage. If all conversions fail, an error will be thrown that needs to be handled.
    override def read(json: JsValue): AcknowledegmentMessage = {
      val JsObject(fields) = json
      val completion = fields.contains("instance")
      val result = fields.contains("response")
      if (completion && result) {
        json.convertTo[CombinedCompletionAndResultMessage]
      } else if (completion) {
        json.convertTo[CompletionMessage]
      } else {
        json.convertTo[ResultMessage]
      }
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

  implicit val format = new JsonFormat[EventMessageBody] {
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
                      activationId: String,
                      statusCode: Int,
                      duration: Duration,
                      waitTime: Duration,
                      initTime: Duration,
                      kind: String,
                      conductor: Boolean,
                      memory: Int,
                      causedBy: Option[String],
                      size: Option[Int] = None,
                      userDefinedStatusCode: Option[Int] = None)
    extends EventMessageBody {
  val typeName = Activation.typeName

  override def serialize = toJson.compactPrint

  def entityPath: FullyQualifiedEntityName = EntityPath(name).toFullyQualifiedEntityName

  def toJson = Activation.activationFormat.write(this)

  def status: String = statusForCode(statusCode)

  def isColdStart: Boolean = initTime != Duration.Zero

  def namespace: String = entityPath.path.root.name

  def action: String = entityPath.fullPath.relativePath.get.namespace

}

object Activation extends DefaultJsonProtocol {

  val typeName = "Activation"

  def parse(msg: String) = Try(activationFormat.read(msg.parseJson))

  private implicit val durationFormat = new RootJsonFormat[Duration] {
    override def write(obj: Duration): JsValue = obj match {
      case o if o.isFinite => JsNumber(o.toMillis)
      case _               => JsNumber.zero
    }

    override def read(json: JsValue): Duration = json match {
      case JsNumber(n) if n <= 0 => Duration.Zero
      case JsNumber(n)           => toDuration(n.longValue)
    }
  }

  implicit val activationFormat =
    jsonFormat(
      Activation.apply _,
      "name",
      "activationId",
      "statusCode",
      "duration",
      "waitTime",
      "initTime",
      "kind",
      "conductor",
      "memory",
      "causedBy",
      "size",
      "userDefinedStatusCode")

  /** Get "StatusCode" from result response set by action developer * */
  def userDefinedStatusCode(result: Option[JsValue]): Option[Int] = {
    val statusCode = JsHelpers
      .getFieldPath(result.get.asJsObject, ERROR_FIELD, "statusCode")
      .orElse(JsHelpers.getFieldPath(result.get.asJsObject, "statusCode"))
    statusCode.map {
      case value => Try(value.convertTo[BigInt].intValue).toOption.getOrElse(BadRequest.intValue)
    }
  }

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
        a.activationId.asString,
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
        a.annotations.getAs[String](WhiskActivation.causedByAnnotation).toOption,
        a.response.size,
        userDefinedStatusCode(a.response.result))
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

case class InvokerResourceMessage(status: String,
                                  freeMemory: Long,
                                  busyMemory: Long,
                                  inProgressMemory: Long,
                                  tags: Seq[String],
                                  dedicatedNamespaces: Seq[String])
    extends Message {

  /**
   * Serializes message to string. Must be idempotent.
   */
  override def serialize: String = InvokerResourceMessage.serdes.write(this).compactPrint
}

object InvokerResourceMessage extends DefaultJsonProtocol {
  def parse(msg: String): Try[InvokerResourceMessage] = Try(serdes.read(msg.parseJson))

  implicit val serdes =
    jsonFormat(
      InvokerResourceMessage.apply _,
      "status",
      "freeMemory",
      "busyMemory",
      "inProgressMemory",
      "tags",
      "dedicatedNamespaces")
}

/**
 * This case class is used when retrieving the snapshot of the queue status from the scheduler at a certain moment.
 * This is useful to figure out the internal status when any issue happens.
 * The following would be an example result.
 *
 * [
 * ...
 * {
 * "data": "RunningData",
 * "fqn": "whisk.system/elasticsearch/status-alarm@0.0.2",
 * "invocationNamespace": "style95",
 * "status": "Running",
 * "waitingActivation": 1
 * },
 * ...
 * ]
 */
object StatusQuery

case class StatusData(invocationNamespace: String, fqn: String, waitingActivation: Int, status: String, data: String)
    extends Message {

  override def serialize: String = StatusData.serdes.write(this).compactPrint

}

object StatusData extends DefaultJsonProtocol {

  implicit val serdes =
    jsonFormat(StatusData.apply _, "invocationNamespace", "fqn", "waitingActivation", "status", "data")
}

case class ContainerCreationMessage(override val transid: TransactionId,
                                    invocationNamespace: String,
                                    action: FullyQualifiedEntityName,
                                    revision: DocRevision,
                                    whiskActionMetaData: WhiskActionMetaData,
                                    rootSchedulerIndex: SchedulerInstanceId,
                                    schedulerHost: String,
                                    rpcPort: Int,
                                    retryCount: Int = 0,
                                    creationId: CreationId = CreationId.generate())
    extends ContainerMessage(transid) {

  override def toJson: JsValue = ContainerCreationMessage.serdes.write(this)

  override def serialize: String = toJson.compactPrint
}

object ContainerCreationMessage extends DefaultJsonProtocol {
  def parse(msg: String): Try[ContainerCreationMessage] = Try(serdes.read(msg.parseJson))

  private implicit val fqnSerdes = FullyQualifiedEntityName.serdes
  private implicit val instanceIdSerdes = SchedulerInstanceId.serdes
  private implicit val byteSizeSerdes = size.serdes
  implicit val serdes = jsonFormat10(
    ContainerCreationMessage.apply(
      _: TransactionId,
      _: String,
      _: FullyQualifiedEntityName,
      _: DocRevision,
      _: WhiskActionMetaData,
      _: SchedulerInstanceId,
      _: String,
      _: Int,
      _: Int,
      _: CreationId))
}

case class ContainerDeletionMessage(override val transid: TransactionId,
                                    invocationNamespace: String,
                                    action: FullyQualifiedEntityName,
                                    revision: DocRevision,
                                    whiskActionMetaData: WhiskActionMetaData)
    extends ContainerMessage(transid) {
  override def toJson: JsValue = ContainerDeletionMessage.serdes.write(this)

  override def serialize: String = toJson.compactPrint
}

object ContainerDeletionMessage extends DefaultJsonProtocol {
  def parse(msg: String): Try[ContainerDeletionMessage] = Try(serdes.read(msg.parseJson))

  private implicit val fqnSerdes = FullyQualifiedEntityName.serdes
  private implicit val instanceIdSerdes = SchedulerInstanceId.serdes
  private implicit val byteSizeSerdes = size.serdes
  implicit val serdes = jsonFormat5(
    ContainerDeletionMessage
      .apply(_: TransactionId, _: String, _: FullyQualifiedEntityName, _: DocRevision, _: WhiskActionMetaData))
}

abstract class ContainerMessage(private val tid: TransactionId) extends Message {
  override val transid: TransactionId = tid

  override def serialize: String = ContainerMessage.serdes.write(this).compactPrint

  /** Serializes the message to JSON. */
  def toJson: JsValue
}

object ContainerMessage extends DefaultJsonProtocol {
  def parse(msg: String): Try[ContainerMessage] = Try(serdes.read(msg.parseJson))

  implicit val serdes = new RootJsonFormat[ContainerMessage] {
    override def write(m: ContainerMessage): JsValue = m.toJson

    override def read(json: JsValue): ContainerMessage = {
      val JsObject(fields) = json
      val creation = fields.contains("creationId")
      if (creation) {
        json.convertTo[ContainerCreationMessage]
      } else {
        json.convertTo[ContainerDeletionMessage]
      }
    }
  }
}

sealed trait ContainerCreationError

object ContainerCreationError extends Enumeration {

  case object NoAvailableInvokersError extends ContainerCreationError

  case object NoAvailableResourceInvokersError extends ContainerCreationError

  case object ResourceNotEnoughError extends ContainerCreationError

  case object WhiskError extends ContainerCreationError

  case object UnknownError extends ContainerCreationError

  case object TimeoutError extends ContainerCreationError

  case object ShuttingDownError extends ContainerCreationError

  case object NonExecutableActionError extends ContainerCreationError

  case object DBFetchError extends ContainerCreationError

  case object BlackBoxError extends ContainerCreationError

  case object ZeroNamespaceLimit extends ContainerCreationError

  case object TooManyConcurrentRequests extends ContainerCreationError

  val whiskErrors: Set[ContainerCreationError] =
    Set(
      NoAvailableInvokersError,
      NoAvailableResourceInvokersError,
      ResourceNotEnoughError,
      WhiskError,
      ShuttingDownError,
      UnknownError,
      TimeoutError,
      ZeroNamespaceLimit)

  private def parse(name: String) = name.toUpperCase match {
    case "NOAVAILABLEINVOKERSERROR"         => NoAvailableInvokersError
    case "NOAVAILABLERESOURCEINVOKERSERROR" => NoAvailableResourceInvokersError
    case "RESOURCENOTENOUGHERROR"           => ResourceNotEnoughError
    case "NONEXECUTBLEACTIONERROR"          => NonExecutableActionError
    case "DBFETCHERROR"                     => DBFetchError
    case "WHISKERROR"                       => WhiskError
    case "BLACKBOXERROR"                    => BlackBoxError
    case "TIMEOUTERROR"                     => TimeoutError
    case "ZERONAMESPACELIMIT"               => ZeroNamespaceLimit
    case "TOOMANYCONCURRENTREQUESTS"        => TooManyConcurrentRequests
    case "UNKNOWNERROR"                     => UnknownError
  }

  implicit val serds = new RootJsonFormat[ContainerCreationError] {
    override def write(error: ContainerCreationError): JsValue = JsString(error.toString)

    override def read(json: JsValue): ContainerCreationError =
      Try {
        val JsString(str) = json
        ContainerCreationError.parse(str.trim.toUpperCase)
      } getOrElse {
        throw deserializationError("ContainerCreationError must be a valid string")
      }
  }
}

case class ContainerCreationAckMessage(override val transid: TransactionId,
                                       creationId: CreationId,
                                       invocationNamespace: String,
                                       action: FullyQualifiedEntityName,
                                       revision: DocRevision,
                                       actionMetaData: WhiskActionMetaData,
                                       rootInvokerIndex: InvokerInstanceId,
                                       schedulerHost: String,
                                       rpcPort: Int,
                                       retryCount: Int = 0,
                                       error: Option[ContainerCreationError] = None,
                                       reason: Option[String] = None)
    extends Message {

  /**
   * Serializes message to string. Must be idempotent.
   */
  override def serialize: String = ContainerCreationAckMessage.serdes.write(this).compactPrint
}

object ContainerCreationAckMessage extends DefaultJsonProtocol {
  def parse(msg: String): Try[ContainerCreationAckMessage] = Try(serdes.read(msg.parseJson))

  private implicit val fqnSerdes = FullyQualifiedEntityName.serdes
  private implicit val byteSizeSerdes = size.serdes
  implicit val serdes = jsonFormat12(ContainerCreationAckMessage.apply)
}
