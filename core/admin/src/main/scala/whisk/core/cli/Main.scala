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
import ch.qos.logback.classic.{Level, LoggerContext}
import org.rogach.scallop._
import org.slf4j.LoggerFactory
import whisk.common.{AkkaLogging, Logging, TransactionId}
import whisk.core.database.UserCommand

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success}

class Conf(arguments: Seq[String]) extends ScallopConf(arguments) {
  banner("OpenWhisk admin command line tool")

  printedName = "wskadmin"
  val verbose = tally()

  addSubcommand(new UserCommand)
  shortSubcommandsHelp()

  requireSubcommand()
  verify()
}

object Main {
  def main(args: Array[String]) {
    //Parse conf before instantiating actorSystem to ensure fast pre check of config
    val conf = new Conf(args)
    LogSupport.init(conf)

    conf.subcommands match {
      case List(c: WhiskCommand) => c.failNoSubCommand()
      case _                     =>
    }
    val exitCode = execute(conf)
    System.exit(exitCode)
  }

  private def execute(conf: Conf): Int = {
    implicit val actorSystem = ActorSystem("admin-cli")
    try {
      executeWithSystem(conf)
    } finally {
      actorSystem.terminate()
      Await.result(actorSystem.whenTerminated, 30.seconds)
    }
  }

  private def executeWithSystem(conf: Conf)(implicit actorSystem: ActorSystem): Int = {
    implicit val logger = new AkkaLogging(akka.event.Logging.getLogger(actorSystem, this))
    implicit val materializer = ActorMaterializer.create(actorSystem)

    val admin = new WhiskAdmin(conf)
    val f = admin.executeCommand()
    val result = Await.ready(f, 30.seconds).value.get
    result match {
      case Success(r) =>
        r match {
          case Right(msg) =>
            println(msg)
            0
          case Left(e) =>
            println(e.message)
            e.code
        }
      case Failure(e) =>
        e.printStackTrace()
        3
    }
  }
}

class CommandError(val message: String, val code: Int)
case class IllegalState(override val message: String) extends CommandError(message, 1)
case class IllegalArg(override val message: String) extends CommandError(message, 2)

trait WhiskCommand {
  this: ScallopConfBase =>

  shortSubcommandsHelp()

  def failNoSubCommand(): Unit = {
    val s = parentConfig.builder.findSubbuilder(commandNameAndAliases.head).get
    println(s.help)
    sys.exit(0)
  }
}

class WhiskAdmin(conf: Conf)(implicit val actorSystem: ActorSystem,
                             implicit val materializer: ActorMaterializer,
                             implicit val logging: Logging) {
  //TODO Turn on extra logging in verbose mode
  implicit val tid = TransactionId(TransactionId.systemPrefix + "cli")
  def executeCommand(): Future[Either[CommandError, String]] = {
    conf.subcommands match {
      case List(cmd: UserCommand, x) => cmd.exec(x)
    }
  }
}

object LogSupport {

  def init(conf: Conf): Unit = {
    val ctx = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
    ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(toLevel(conf.verbose()))
  }

  private def toLevel(v: Int) = {
    v match {
      case 0 => Level.WARN
      case 1 => Level.INFO
      case 2 => Level.DEBUG
      case _ => Level.ALL
    }
  }
}
