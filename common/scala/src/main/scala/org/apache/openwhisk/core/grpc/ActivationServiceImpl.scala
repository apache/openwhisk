package org.apache.openwhisk.core.grpc

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout
import org.apache.openwhisk.common.Logging
import org.apache.openwhisk.core.connector.{ActivationMessage, Message}
import org.apache.openwhisk.core.entity.{DocInfo, DocRevision, FullyQualifiedEntityName}
import org.apache.openwhisk.grpc.{ActivationService, FetchRequest, FetchResponse}
import spray.json._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.Try

class ActivationServiceImpl(private val queueManager: ActorRef)(implicit actorSystem: ActorSystem, logging: Logging)
    extends ActivationService {
  implicit val requestTimeout: Timeout = Timeout(50.seconds)
  implicit val ec: ExecutionContextExecutor = actorSystem.dispatcher

  override def fetchActivation(request: FetchRequest): Future[FetchResponse] = {
    Future(for {
      fqn <- FullyQualifiedEntityName.parse(request.fqn)
      rev <- DocRevision.parse(request.rev)
    } yield (fqn, rev)).flatMap(Future.fromTry) flatMap { res =>
      val key = res._1.toDocId.asDocInfo(res._2)
      (queueManager ? ActivationRequest(res._1, key))
        .mapTo[ActivationResponse]
        .map(msg => FetchResponse(msg.serialize))
        .recover {
          case t: Throwable =>
            logging.error(this, s"Failed to get message from QueueManager, error: ${t.getMessage}")
            FetchResponse(ActivationResponse(Left(NoActivationMessage())).serialize)
        }
    }

  }
}

object ActivationServiceImpl {

  def apply(queueManager: ActorRef)(implicit actorSystem: ActorSystem, logging: Logging) =
    new ActivationServiceImpl(queueManager)
}

case class ActivationRequest(action: FullyQualifiedEntityName, docInfo: DocInfo)
case class ActivationResponse(message: Either[MemoryQueueError, ActivationMessage]) extends Message {
  override def serialize = ActivationResponse.serdes.write(this).compactPrint
}

object ActivationResponse extends DefaultJsonProtocol {

  private implicit val noMessageSerdes = NoActivationMessage.serdes
  private implicit val noQueueSerdes = NoMemoryQueue.serdes
  private implicit val mismatchSerdes = ActionMismatch.serdes
  private implicit val messageSerdes = ActivationMessage.serdes
  private implicit val memoryqueueErrorSerdes = MemoryQueueErrorSerdes.memoryQueueErrorFormat

  def parse(msg: String) = Try(serdes.read(msg.parseJson))

  implicit def rootEitherFormat[A: RootJsonFormat, B: RootJsonFormat] =
    new RootJsonFormat[Either[A, B]] {
      val format = DefaultJsonProtocol.eitherFormat[A, B]

      def write(either: Either[A, B]) = format.write(either)

      def read(value: JsValue) = format.read(value)
    }

  type ActivationResponse = Either[MemoryQueueError, ActivationMessage]
  implicit val serdes = jsonFormat(ActivationResponse.apply _, "message")

}

sealed trait MemoryQueueError extends Product {
  val causedBy: String
}

object MemoryQueueErrorSerdes {

  private implicit val noMessageSerdes = NoActivationMessage.serdes
  private implicit val noQueueSerdes = NoMemoryQueue.serdes
  private implicit val mismatchSerdes = ActionMismatch.serdes

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
