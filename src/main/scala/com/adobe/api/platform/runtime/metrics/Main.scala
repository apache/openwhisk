/*
Copyright 2018 Adobe. All rights reserved.
This file is licensed to you under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License. You may obtain a copy
of the License at http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under
the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR REPRESENTATIONS
OF ANY KIND, either express or implied. See the License for the specific language
governing permissions and limitations under the License.
 */

package com.adobe.api.platform.runtime.metrics
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import kamon.Kamon

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContextExecutor, Future}

object Main {
  def main(args: Array[String]): Unit = {
    Kamon.loadReportersFromConfig()
    implicit val system: ActorSystem = ActorSystem("events-actor-system")
    implicit val materializer: ActorMaterializer = ActorMaterializer()
    val binding = OpenWhiskEvents.start(system.settings.config)
    addShutdownHook(binding)
  }

  private def addShutdownHook(binding: Future[Http.ServerBinding])(implicit actorSystem: ActorSystem,
                                                                   materializer: ActorMaterializer): Unit = {
    implicit val ec: ExecutionContextExecutor = actorSystem.dispatcher
    sys.addShutdownHook {
      Await.result(binding.map(_.unbind()), 30.seconds)
      Await.result(actorSystem.whenTerminated, 30.seconds)
    }
  }
}
