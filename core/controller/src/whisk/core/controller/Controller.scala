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

package whisk.core.controller

import scala.concurrent.ExecutionContext

import akka.actor.Actor
import akka.japi.Creator
import spray.routing.Directive.pimpApply
import whisk.common.TransactionId
import whisk.common.Verbosity
import whisk.core.loadBalancer.LoadBalancerService
import whisk.core.WhiskConfig
import whisk.core.WhiskConfig.{ consulServer, kafkaHost, kafkaPartitions }
import whisk.http.BasicHttpService
import whisk.http.BasicRasService
import whisk.utils.ExecutionContextFactory

class Controller(
    config: WhiskConfig,
    instance: Int,
    verbosity: Verbosity.Level,
    executionContext: ExecutionContext)
    extends BasicRasService
    with Actor {

    override def actorRefFactory = context

    override def routes(implicit transid: TransactionId) = {
        handleRejections(badRequestHandler) {
            super.routes ~ apiv1.routes
        }
    }

    setVerbosity(verbosity)
    info(this, s"starting controller instance ${instance}")

    /** The REST APIs. */
    private val apiv1 = new RestAPIVersion_v1(config, verbosity, context.system, executionContext)

}

object Controller {
    def requiredProperties =
        Map(WhiskConfig.servicePort -> 8080.toString) ++
            RestAPIVersion_v1.requiredProperties ++
            consulServer ++
            kafkaHost ++
            Map(kafkaPartitions -> null) ++
            LoadBalancerService.requiredProperties

    private class ServiceBuilder(config: WhiskConfig, instance: Int) extends Creator[Controller] {
        implicit val executionContext = ExecutionContextFactory.makeExecutionContext()
        def create = new Controller(config, instance, Verbosity.Loud, executionContext)
    }

    def main(args: Array[String]): Unit = {
        val config = new WhiskConfig(requiredProperties)
        val instance = if (args.length > 0) args(1).toInt else 0

        if (config.isValid) {
            val port = config.servicePort.toInt
            BasicHttpService.startService("controller", "0.0.0.0", port, new ServiceBuilder(config, instance))
        }
    }
}
