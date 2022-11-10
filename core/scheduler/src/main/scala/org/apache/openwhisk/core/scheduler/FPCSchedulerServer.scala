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
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.model.headers.BasicHttpCredentials
import akka.http.scaladsl.server.Route
import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.http.BasicRasService
import org.apache.openwhisk.http.CorsSettings.RespondWithServerCorsHeaders
import org.apache.openwhisk.http.ErrorResponse.terminate
import pureconfig.loadConfigOrThrow
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.concurrent.ExecutionContext

/**
 * Implements web server to handle certain REST API calls.
 * Currently provides a health ping route, only.
 */
class FPCSchedulerServer(scheduler: SchedulerCore, systemUsername: String, systemPassword: String)(
  implicit val ec: ExecutionContext,
  implicit val actorSystem: ActorSystem,
  implicit val logger: Logging)
    extends BasicRasService
    with RespondWithServerCorsHeaders {

  override def routes(implicit transid: TransactionId): Route = {
    super.routes ~ sendCorsHeaders {
      options {
        complete(OK)
      } ~ extractCredentials {
        case Some(BasicHttpCredentials(username, password))
            if username == systemUsername && password == systemPassword =>
          (path("state") & get) {
            complete {
              scheduler.getState.map {
                case (list, creationCount) =>
                  val sum = list.map(tuple => tuple._2).sum
                  (Map("queue" -> sum.toString) ++ Map("creationCount" -> creationCount.toString)).toJson
              }
            }
          } ~ (path("disable") & post) {
            logger.warn(this, "Scheduler is disabled")
            scheduler.disable()
            complete("scheduler disabled")
          } ~ (pathPrefix(FPCSchedulerServer.queuePathPrefix) & get) {
            pathEndOrSingleSlash {
              complete(scheduler.getQueueStatusData.map(s => s.toJson))
            } ~ (path("count") & get) {
              complete(scheduler.getQueueSize.map(s => s.toJson))
            }
          } ~ (path("activation" / "count") & get) {
            pathEndOrSingleSlash {
              complete(
                scheduler.getQueueStatusData
                  .map { s =>
                    s.map(_.waitingActivation.size)
                  }
                  .map(a => a.sum)
                  .map(_.toJson))
            }
          }
        case _ =>
          implicit val jsonPrettyResponsePrinter = PrettyPrinter
          terminate(StatusCodes.Unauthorized)
      }
    }
  }
}

object FPCSchedulerServer {

  private val schedulerUsername = loadConfigOrThrow[String](ConfigKeys.whiskSchedulerUsername)
  private val schedulerPassword = loadConfigOrThrow[String](ConfigKeys.whiskSchedulerPassword)
  private val queuePathPrefix = "queues"

  def instance(scheduler: SchedulerCore)(implicit ec: ExecutionContext,
                                         actorSystem: ActorSystem,
                                         logger: Logging): BasicRasService =
    new FPCSchedulerServer(scheduler, schedulerUsername, schedulerPassword)
}
