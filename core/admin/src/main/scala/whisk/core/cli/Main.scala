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

package whisk.core.cli

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import org.rogach.scallop._
import whisk.common.{AkkaLogging, Logging}
import whisk.core.database.UserCommand
import whisk.core.entity.WhiskAuthStore
import whisk.core.entity.types._

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class Conf(arguments: Seq[String]) extends ScallopConf(arguments) {
  banner("OpenWhisk admin command line tool")
  addSubcommand(UserCommand)
  shortSubcommandsHelp()

  requireSubcommand()
  verify()
}

object Main {
  def main(args: Array[String]) {
    execute(new Conf(args))
  }

  private def execute(conf: Conf): Unit = {
    implicit val actorSystem = ActorSystem("admin-cli")
    try {
      executeWithSystem(conf)
    } finally {
      actorSystem.terminate()
      Await.result(actorSystem.whenTerminated, 30.seconds)
    }
  }

  private def executeWithSystem(conf: Conf)(implicit actorSystem: ActorSystem): Unit = {
    implicit val logger = new AkkaLogging(akka.event.Logging.getLogger(actorSystem, this))
    implicit val materializer = ActorMaterializer.create(actorSystem)

    implicit val authStore = WhiskAuthStore.datastore()

    val admin = new WhiskAdmin(conf)
    admin.executeCommand()

    authStore.shutdown()
  }
}

class WhiskAdmin(conf: Conf)(implicit val actorSystem: ActorSystem,
                             implicit val materializer: ActorMaterializer,
                             implicit val logging: Logging,
                             implicit val authStore: AuthStore) {

  def executeCommand(): Unit = {
    conf.subcommands match {
      case List(UserCommand, x) => UserCommand.exec(x)
    }
  }
}
