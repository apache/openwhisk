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

package org.apache.openwhisk.core.monitoring.metrics

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import kamon.Kamon

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContextExecutor, Future}

object Main {
  def main(args: Array[String]): Unit = {
    Kamon.init()
    implicit val system: ActorSystem = ActorSystem("events-actor-system")
    val binding = OpenWhiskEvents.start(system.settings.config)
    addShutdownHook(binding)
  }

  private def addShutdownHook(binding: Future[Http.ServerBinding])(implicit actorSystem: ActorSystem): Unit = {
    implicit val ec: ExecutionContextExecutor = actorSystem.dispatcher
    sys.addShutdownHook {
      Await.result(binding.map(_.unbind()), 30.seconds)
      Await.result(actorSystem.whenTerminated, 30.seconds)
    }
  }
}
