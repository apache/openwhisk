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

package org.apache.openwhisk.core.invoker

import akka.actor.ActorSystem
import akka.http.javadsl.model.HttpMethods
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.BasicHttpCredentials
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import akka.util.Timeout
import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.core.containerpool._
import org.apache.openwhisk.core.entity.{CodeExecAsString, ExecManifest}
import org.apache.openwhisk.core.entity.size._
import org.apache.openwhisk.http.BasicRasService
import spray.json.JsObject

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

/**
 * Implements web server to handle certain REST API calls.
 */
class InvokerServer(implicit val ec: ExecutionContext,
                    implicit val actorSystem: ActorSystem,
                    implicit val logger: Logging)
    extends BasicRasService {

  val invokerUsername = {
    val source = scala.io.Source.fromFile("/conf/invokerauth.username");
    try source.mkString.replaceAll("\r|\n", "")
    finally source.close()
  }
  val invokerPassword = {
    val source = scala.io.Source.fromFile("/conf/invokerauth.password");
    try source.mkString.replaceAll("\r|\n", "")
    finally source.close()
  }

  override def routes(implicit transid: TransactionId): Route = {
    super.routes ~ {
      (path("prewarmContainer") & (post | delete)) {
        extractCredentials {
          case Some(BasicHttpCredentials(username, password)) =>
            if (username == invokerUsername && password == invokerPassword) {
              extractMethod { method =>
                entity(as[String]) { prewarmRuntime =>
                  val execManifest = ExecManifest.initializePrewarm(prewarmRuntime)
                  if (execManifest.isFailure) {
                    logger.error(
                      this,
                      s"Received invalid prewarm runtimes manifest with ${method.name} request:${execManifest.failed.get}")
                    complete(s"Received invalid prewarm runtimes manifest with ${method.name} request")
                  } else {
                    val prewarmingConfigs: List[PrewarmingConfig] = execManifest.get.stemcells.flatMap {
                      case (mf, cells) =>
                        cells.map { cell =>
                          PrewarmingConfig(cell.count, new CodeExecAsString(mf, "", None), cell.memory)
                        }
                    }.toList
                    if (method.name == HttpMethods.POST.name) {
                      Invoker.invokerReactive.get.getContainerPoolInstance ! AddPreWarmConfigList(prewarmingConfigs)
                    } else {
                      Invoker.invokerReactive.get.getContainerPoolInstance ! DeletePreWarmConfigList(prewarmingConfigs)
                    }
                    complete(s"Change prewarm container request with ${method.name} is handling")
                  }
                }
              }
            } else {
              complete("username or password is wrong")
            }
          case _ => complete(StatusCodes.Unauthorized)
        }
      }
    } ~ {
      (path("prewarmContainerNumber") & get) {
        parameter('kind.as[String], 'memory.as[Int]) { (kind, memory) =>
          implicit val timeout = Timeout(5.seconds)
          val numberFuture = Future {
            Invoker.invokerReactive.get.getContainerPoolInstance
              .ask(PreWarmConfig(kind, memory.MB))
              .mapTo[JsObject]
          }.flatten
          complete(numberFuture)
        }
      }
    }
  }
}
