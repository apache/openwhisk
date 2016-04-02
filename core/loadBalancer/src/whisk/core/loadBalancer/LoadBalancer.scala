/*
 * Copyright 2015-2016 IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.core.loadBalancer

import scala.util.Failure
import scala.util.Success

import akka.actor.Actor
import akka.japi.Creator

import spray.http.StatusCodes.InternalServerError
import spray.http.StatusCodes.OK
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._
import spray.json.JsBoolean
import spray.json.JsObject

import whisk.common.ConsulKV
import whisk.common.ConsulKV.LoadBalancerKeys
import whisk.common.ConsulKVReporter
import whisk.common.Verbosity
import whisk.common.TransactionId
import whisk.connector.kafka.KafkaProducerConnector
import whisk.core.WhiskConfig
import whisk.core.WhiskConfig.consulServer
import whisk.core.WhiskConfig.kafkaHost
import whisk.core.WhiskConfig.kafkaPartitions
import whisk.core.WhiskConfig.servicePort
import whisk.core.connector.{ ActivationMessage => Message }
import whisk.http.BasicHttpService
import whisk.http.BasicRasService
import whisk.utils.ExecutionContextFactory

class LoadBalancer(config: WhiskConfig, verbosity: Verbosity.Level)
    extends LoadBalancerService(config, verbosity)
    with BasicRasService
    with Actor {

    override def actorRefFactory = context

    setVerbosity(verbosity)

    override def routes(implicit transid: TransactionId) = super.routes ~ publish ~ invokers

    /**
     * Handles POST /publish/topic URI.
     *
     * @param component the component name extracted from URI (invoker, or activator)
     * @param msg the Message received via POST
     * @return response to terminate HTTP connection with
     */
    def publish(implicit transid: TransactionId) = {
        (path("publish" / s"""(${Message.ACTIVATOR}|${Message.INVOKER})""".r) & post & entity(as[Message])) {
            (component, message) =>
                onComplete(doPublish(component, message)(message.transid)) {
                    case Success(response) => complete(OK, response)
                    case Failure(t)        => complete(InternalServerError)
                }
        }
    }

     /**
     * Handles GET /invokers URI.
     *
     * @return JSON of invoker health
     */
     val invokers = {
        (path("invokers") & get) {
            complete {
                getInvokerHealth()
            }
        }
    }

}

object LoadBalancer {

    def requiredProperties =
        Map(servicePort -> null) ++
            kafkaHost ++
            consulServer ++
            Map(kafkaPartitions -> null) ++
            InvokerHealth.requiredProperties

    private class ServiceBuilder(config: WhiskConfig) extends Creator[LoadBalancer] {
        def create = new LoadBalancer(config, Verbosity.Loud)
    }

    def main(args: Array[String]): Unit = {

        if (config.isValid) {
            val port = config.servicePort.toInt
            BasicHttpService.startService("loadbalancer", "0.0.0.0", port, new ServiceBuilder(config))
        }
    }

    val config = new WhiskConfig(requiredProperties)
}
