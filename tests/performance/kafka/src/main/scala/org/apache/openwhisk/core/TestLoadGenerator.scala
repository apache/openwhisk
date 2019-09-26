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

package org.apache.openwhisk.core

import akka.actor.{ActorSystem, CoordinatedShutdown}
import akka.event.slf4j.SLF4JLogging
import akka.http.scaladsl.server.Route
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, Supervision}
import kamon.Kamon
import org.apache.openwhisk.common.{AkkaLogging, ConfigMXBean, Logging, TransactionId}
import org.apache.openwhisk.http.{BasicHttpService, BasicRasService}
import pureconfig.loadConfigOrThrow
import akka.http.scaladsl.server.Directives
import akka.http.scaladsl.unmarshalling.Unmarshaller

import scala.language.postfixOps
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.{Success, Try}

object TestLoadGenerator extends SLF4JLogging {
  val configRoot = "whisk.load-generator"

  def main(args: Array[String]): Unit = {
    initConfig()
    implicit val system: ActorSystem = ActorSystem("test-actor-system")
    val decider: Supervision.Decider = { e =>
      log.error("Unhandled exception in stream", e)
      Supervision.Stop
    }
    val materializerSettings = ActorMaterializerSettings(system).withSupervisionStrategy(decider)
    implicit val materializer: ActorMaterializer = ActorMaterializer(materializerSettings)
    implicit val logging: Logging = new AkkaLogging(akka.event.Logging.getLogger(system, this))

    ConfigMXBean.register()
    Kamon.loadReportersFromConfig()
    val loadConfig =
      loadConfigOrThrow[LoadGeneratorConfig](system.settings.config.getConfig(configRoot))
    val port = loadConfig.port
    CoordinatedShutdown(system)
    BasicHttpService.startHttpService(new LoadGenService(loadConfig).route, port, None)
  }

  private def initConfig() = {
    if (!sys.props.contains("config.file")) {
      sys.props.put("config.resource", "load-test.conf")
    }
  }

  class LoadGenService(config: LoadGeneratorConfig)(implicit system: ActorSystem,
                                                    materializer: ActorMaterializer,
                                                    logging: Logging)
      extends BasicRasService
      with Directives {
    override def routes(implicit transid: TransactionId): Route = super.routes ~ runRoute()

    implicit val stringToFiniteDuration: Unmarshaller[String, FiniteDuration] = {
      Unmarshaller.strict[String, FiniteDuration] { value =>
        Try { Duration(value) } match {
          case Success(i) if i.isFinite() => i.asInstanceOf[FiniteDuration]
          case _ =>
            throw new IllegalArgumentException(s"Invalid duration $value")
        }
      }
    }

    def runRoute()(implicit transid: TransactionId): Route =
      path("run") {
        get {
          parameters(
            'count.as[Int] ? 10,
            'topic.as[String] ? "completed-others",
            'size.as[Int] ? 100,
            'telems.as[Int] ?,
            'tper.as[FiniteDuration] ?) { (count, topic, size, telems, tper) =>
            val throttle = for {
              e <- telems
              per <- tper
            } yield ThrottleSettings(e, per)

            val msgGen = new WhiskActivationGenerator(size)
            val loadGen = LoadGenerator(config, count, topic, msgGen, throttle)
            complete(s"Started run [${loadGen.id}] - Count $count, Topic $topic, Throttle $throttle")
          }
        }
      } ~ path("status") {
        concat(
          pathEnd(complete(loadGeneratorStatus)),
          path(LongNumber) { id =>
            val gen = LoadGenerator.generators.get(id)
            val result = gen
              .map(_.status)
              .getOrElse(s"Unknown id $id")
            complete(result)
          })
      }

    private def loadGeneratorStatus = {
      val statuses = LoadGenerator.generators.values.map(_.status())
      if (statuses.isEmpty) "No Load generator runs in progress" else statuses.mkString("\n")
    }
  }
}
