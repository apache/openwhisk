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

package org.apache.openwhisk.core.scheduler

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.BasicHttpCredentials
import akka.http.scaladsl.server.Route
import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.http.BasicRasService
import org.apache.openwhisk.http.ErrorResponse.terminate
import spray.json._

import scala.concurrent.ExecutionContext

/**
 * Implements web server to handle certain REST API calls.
 * Currently provides a health ping route, only.
 */
class SchedulerServer(scheduler: SchedulerCore, systemUsername: String, systemPassword: String)(
  implicit val ec: ExecutionContext,
  implicit val actorSystem: ActorSystem,
  implicit val logger: Logging)
    extends BasicRasService {

  override def routes(implicit transid: TransactionId): Route = {
    super.routes ~ extractCredentials {
      case Some(BasicHttpCredentials(username, password)) if username == systemUsername && password == systemPassword =>
        (path("disable") & post) {
          logger.warn(this, "Scheduler is disabled")
          scheduler.shutdown()
          complete("scheduler disabled")
        }
      case _ =>
        implicit val jsonPrettyResponsePrinter = PrettyPrinter
        terminate(StatusCodes.Unauthorized)
    }
  }
}

object SchedulerServer {

  val schedulerUsername = {
    val source = scala.io.Source.fromFile("/conf/schedulerauth.username")
    try source.mkString.replaceAll("\r|\n", "")
    finally source.close()
  }
  val schedulerPassword = {
    val source = scala.io.Source.fromFile("/conf/schedulerauth.password")
    try source.mkString.replaceAll("\r|\n", "")
    finally source.close()
  }

  def instance(scheduler: SchedulerCore)(implicit ec: ExecutionContext,
                                         actorSystem: ActorSystem,
                                         logger: Logging): BasicRasService =
    new SchedulerServer(scheduler, schedulerUsername, schedulerPassword)
}
