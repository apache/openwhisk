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

package org.apache.openwhisk.standalone

import java.nio.charset.StandardCharsets.UTF_8

import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.directives.FileAndResourceDirectives.ResourceFile
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import ch.megard.akka.http.cors.scaladsl.CorsDirectives._
import org.apache.commons.io.IOUtils
import org.apache.commons.lang3.SystemUtils
import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.core.ExecManifestSupport
import org.apache.openwhisk.http.BasicHttpService
import pureconfig._

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}
import scala.sys.process._
import scala.util.{Failure, Success, Try}

class PlaygroundLauncher(host: String,
                         extHost: String,
                         controllerPort: Int,
                         pgPort: Int,
                         authKey: String,
                         devMode: Boolean,
                         noBrowser: Boolean)(implicit logging: Logging,
                                             ec: ExecutionContext,
                                             actorSystem: ActorSystem,
                                             materializer: ActorMaterializer,
                                             tid: TransactionId) {
  private val interface = loadConfigOrThrow[String]("whisk.controller.interface")
  private val jsFileName = "playgroundFunctions.js"
  private val jsContentType = ContentType(MediaTypes.`application/javascript`, HttpCharsets.`UTF-8`)

  private val uiPath = {
    //Depending on fact the run is done from within IDE or from terminal the classpath prefix needs to be adapted
    val res = getClass.getResource(s"/playground/ui/$jsFileName")
    Try(ResourceFile(res)) match {
      case Success(_) => "playground/ui"
      case Failure(_) => "BOOT-INF/classes/playground/ui"
    }
  }

  private val jsFileContent = {
    val js = resourceToString(jsFileName, "ui")
    val content =
      js.replace("window.APIHOST='http://localhost:3233'", s"window.APIHOST='http://$extHost:$controllerPort'")
    content.getBytes(UTF_8)
  }

  private val pg = "playground"
  private val pgUrl = s"http://${StandaloneDockerSupport.getLocalHostName()}:$pgPort/$pg"

  private val wsk = new Wsk(host, controllerPort, authKey)

  def run(): ServiceContainer = {
    BasicHttpService.startHttpService(PlaygroundService.route, pgPort, None, interface)(actorSystem, materializer)
    ServiceContainer(pgPort, pgUrl, "Playground")
  }

  def install(): Unit = {
    val actions = List("delete", "fetch", "run", "userpackage")
    val f = Source(actions)
      .mapAsync(1) { name =>
        val actionName = s"playground-$name"
        val js = resourceToString(s"playground-$name.js", "actions")
        val r = wsk.updatePgAction(actionName, js)
        r.foreach(_ => logging.info(this, s"Installed action $actionName"))
        r
      }
      .runWith(Sink.ignore)
    Await.result(f, 5.minutes)
    Try {
      if (!devMode) {
        prePullDefaultImages()
      }
      if (!noBrowser) {
        launchBrowser(pgUrl)
        logging.info(this, s"Launched browser $pgUrl")
      }
    }.failed.foreach(t => logging.warn(this, "Failed to launch browser " + t))
  }

  private def launchBrowser(url: String): Unit = {
    if (SystemUtils.IS_OS_MAC) {
      s"open $url".!!
    } else if (SystemUtils.IS_OS_WINDOWS) {
      s"""start "$url" """.!!
    } else if (SystemUtils.IS_OS_LINUX) {
      s"xdg-open $url".!!
    }
  }

  private def prePullDefaultImages(): Unit = {
    ExecManifestSupport.getDefaultImage("nodejs").foreach { imageName =>
      StandaloneDockerSupport.prePullImage(imageName)
    }
  }

  object PlaygroundService extends BasicHttpService {
    override def routes(implicit transid: TransactionId): Route =
      path(PathEnd | Slash | pg) { redirect(s"/$pg/ui/index.html", StatusCodes.Found) } ~
        cors() {
          pathPrefix(pg / "ui" / Segment) { fileName =>
            get {
              if (fileName == jsFileName) {
                complete(HttpEntity(jsContentType, jsFileContent))
              } else {
                getFromResource(s"$uiPath/$fileName")
              }
            }
          }
        }
  }

  private def resourceToString(name: String, resType: String) =
    IOUtils.resourceToString(s"/playground/$resType/$name", UTF_8)
}
