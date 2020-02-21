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

package org.apache.openwhisk.core.database.cosmosdb.cache

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import kamon.Kamon
import org.apache.openwhisk.common.{AkkaLogging, ConfigMXBean, Logging}
import org.apache.openwhisk.http.{BasicHttpService, BasicRasService}

object Main {
  def main(args: Array[String]): Unit = {
    implicit val system: ActorSystem = ActorSystem("cache-invalidator-actor-system")
    implicit val materializer: ActorMaterializer = ActorMaterializer()
    implicit val log: Logging = new AkkaLogging(akka.event.Logging.getLogger(system, this))

    ConfigMXBean.register()
    Kamon.init()
    val port = CacheInvalidatorConfig(system.settings.config).invalidatorConfig.port
    BasicHttpService.startHttpService(new BasicRasService {}.route, port, None)
    CacheInvalidator.start(system.settings.config)
    log.info(this, s"Started the server at http://localhost:$port")
  }
}
