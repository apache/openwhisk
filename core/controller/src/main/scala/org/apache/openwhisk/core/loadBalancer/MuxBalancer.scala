package org.apache.openwhisk.core.loadBalancer

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.stream.ActorMaterializer
import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.core.{ConfigKeys, WhiskConfig}
import org.apache.openwhisk.core.WhiskConfig._
import org.apache.openwhisk.core.connector.{ActivationMessage, MessagingProvider}
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.spi.SpiLoader
import pureconfig.loadConfigOrThrow
import spray.json._
import pureconfig._
import pureconfig.generic.auto._

import scala.concurrent.Future

class MuxBalancer(config: WhiskConfig,
                  feedFactory: FeedFactory,
                  controllerInstance: ControllerInstanceId,
                  implicit val messagingProvider: MessagingProvider = SpiLoader.get[MessagingProvider],
                  override val lbConfig: ShardingContainerPoolBalancerConfig =
                  loadConfigOrThrow[ShardingContainerPoolBalancerConfig](ConfigKeys.loadbalancer))(
  implicit actorSystem: ActorSystem,
  logging: Logging,
  materializer: ActorMaterializer)
    extends CommonLoadBalancer(config, feedFactory, controllerInstance) {

  private val defaultLoadBalancer =
    getClass[LoadBalancerProvider](lbConfig.strategy.default).instance(config, controllerInstance)
  private val customLoadBalancerMap: Map[String, LoadBalancer] =
    lbConfig.strategy.custom.foldLeft(Map.empty[String, LoadBalancer]) {
      case (result, (name, strategyConfig)) =>
        result + (name -> getClass[LoadBalancerProvider](strategyConfig.className).instance(config, controllerInstance))
    }

  /**
   * Instantiates an object of the given type.
   *
   * Similar to SpiLoader.get, with the difference that the constructed class does not need to be declared as Spi.
   * Thus there could be multiple classes implementing same interface constructed at the same time
   *
   * @param name the name of the class
   * @tparam A expected type to return
   * @return instance of the class
   */
  private def getClass[A](name: String): A = {
    val clazz = Class.forName(name + "$")
    val classInst = clazz.getField("MODULE$").get(clazz).asInstanceOf[A]
    classInst
  }

  override def invokerHealth(): Future[IndexedSeq[InvokerHealth]] = Future.successful(IndexedSeq.empty[InvokerHealth])
  override protected def releaseInvoker(invoker: InvokerInstanceId, entry: ActivationEntry) = {
    // Currently do nothing
  }
  override protected val invokerPool: ActorRef = actorSystem.actorOf(Props.empty)

  /**
   * Publish a message to the loadbalancer
   *
   * Select the LoadBalancer based on the annotation, if available, otherwise use the default one
    **/
  override def publish(action: ExecutableWhiskActionMetaData, msg: ActivationMessage)(
    implicit transid: TransactionId): Future[Future[Either[ActivationId, WhiskActivation]]] = {
    action.annotations.get("activationStrategy") match {
      case None =>
        defaultLoadBalancer.publish(action, msg)
      case Some(JsString(value)) => {
        if (customLoadBalancerMap.contains(value)) {
          customLoadBalancerMap(value).publish(action, msg)
        } else {
          defaultLoadBalancer.publish(action, msg)
        }
      }
      case Some(_) => defaultLoadBalancer.publish(action, msg)
    }
  }
}

object MuxBalancer extends LoadBalancerProvider {

  override def instance(whiskConfig: WhiskConfig, instance: ControllerInstanceId)(
    implicit actorSystem: ActorSystem,
    logging: Logging,
    materializer: ActorMaterializer): LoadBalancer = {

    new MuxBalancer(whiskConfig, createFeedFactory(whiskConfig, instance), instance)
  }

  def requiredProperties =
    ExecManifest.requiredProperties ++
      wskApiHost
}
