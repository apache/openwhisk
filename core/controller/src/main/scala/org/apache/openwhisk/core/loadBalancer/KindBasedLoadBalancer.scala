package org.apache.openwhisk.core.loadBalancer

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.stream.ActorMaterializer
import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.core.WhiskConfig
import org.apache.openwhisk.core.WhiskConfig._
import org.apache.openwhisk.core.connector.{ActivationMessage, MessagingProvider}
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.spi.SpiLoader
import spray.json._

import scala.concurrent.Future

class KindBasedLoadBalancer(config: WhiskConfig,
                            feedFactory: FeedFactory,
                            controllerInstance: ControllerInstanceId,
                            implicit val messagingProvider: MessagingProvider = SpiLoader.get[MessagingProvider])(
  implicit actorSystem: ActorSystem,
  logging: Logging,
  materializer: ActorMaterializer)
    extends CommonLoadBalancer(config, feedFactory, controllerInstance) {

  private val balancers = lbConfig.strategy.foldLeft(Map.empty[String, LoadBalancer]) {
    case (result, (name, lbClass)) => result + (name -> getClass(lbClass))
  }

  def getClass[A](name: String): A = {
    logging.info(this, "'" + name + "'$")
    val clazz = Class.forName(name + "$")
    clazz.getField("MODULE$").get(clazz).asInstanceOf[A]
  }

  override def invokerHealth(): Future[IndexedSeq[InvokerHealth]] = Future.successful(IndexedSeq.empty[InvokerHealth])
  override protected def releaseInvoker(invoker: InvokerInstanceId, entry: ActivationEntry) = {
    // Currently do nothing
  }
  override protected val invokerPool: ActorRef = actorSystem.actorOf(Props.empty)

  /** 1. Publish a message to the loadbalancer */
  override def publish(action: ExecutableWhiskActionMetaData, msg: ActivationMessage)(
    implicit transid: TransactionId): Future[Future[Either[ActivationId, WhiskActivation]]] = {
    logging.info(this, "We are here")
    action.annotations.get("activationStrategy") match {
      case None =>
        balancers("default").publish(action, msg)
      case Some(JsString(value)) => {
        if (balancers.contains(value)) {
          balancers(value).publish(action, msg)
        } else {
          balancers("default").publish(action, msg)
        }
      }
      case (Some(_))  =>  balancers("default").publish(action, msg)
    }

//    if (action.annotations.get("activationStrategy").isDefined && balancers.contains(action.annotations.get("activationStrategy").get.toString.)
//    {
//      balancers().publish(action, msg)
//    } else {
//      balancers("default").publish(action, msg)
//    }
  }
}

object KindBasedLoadBalancer extends LoadBalancerProvider {

  override def instance(whiskConfig: WhiskConfig, instance: ControllerInstanceId)(
    implicit actorSystem: ActorSystem,
    logging: Logging,
    materializer: ActorMaterializer): LoadBalancer = {

    new KindBasedLoadBalancer(whiskConfig, createFeedFactory(whiskConfig, instance), instance)
  }

  def requiredProperties =
    ExecManifest.requiredProperties ++
      wskApiHost
}
