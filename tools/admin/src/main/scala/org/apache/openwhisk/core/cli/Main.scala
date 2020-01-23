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

package org.apache.openwhisk.core.cli

import java.io.File

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import ch.qos.logback.classic.{Level, LoggerContext}
import org.rogach.scallop._
import org.slf4j.LoggerFactory
import pureconfig.error.ConfigReaderException
import org.apache.openwhisk.common.{AkkaLogging, Logging, TransactionId}
import org.apache.openwhisk.core.database.{LimitsCommand, UserCommand}

import scala.concurrent.duration.{Duration, DurationInt}
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success, Try}

class Conf(arguments: Seq[String]) extends ScallopConf(arguments) {
  banner("OpenWhisk admin command line tool")
  val durationConverter = singleArgConverter[Duration](Duration(_))

  //Spring boot launch script changes the working directory to one where jar file is present
  //So invocation like ./bin/wskadmin-next -c config.conf would fail to resolve file as it would
  //be looked in directory where jar is present. This convertor makes use of `OLDPWD` to also
  //do a fallback check in that directory
  val fileConverter = singleArgConverter[File] { f =>
    val f1 = new File(f)
    val oldpwd = System.getenv("OLDPWD")
    if (f1.exists())
      f1
    else if (oldpwd != null) {
      val f2 = new File(oldpwd, f)
      if (f2.exists()) f2 else f1
    } else {
      f1
    }
  }
  val verbose = tally()
  val configFile = opt[File](descr = "application.conf which overwrites the default whisk.conf")(fileConverter)
  val timeout =
    opt[Duration](descr = "time to wait for asynchronous task to finish", default = Some(30.seconds))(durationConverter)
  printedName = Main.printedName

  def verboseEnabled: Boolean = verbose() > 0

  addSubcommand(new UserCommand)
  addSubcommand(new LimitsCommand)
  shortSubcommandsHelp()

  requireSubcommand()
  validateFileExists(configFile)
  verify()
}

object Main {
  val printedName = "wskadmin"

  def main(args: Array[String]): Unit = {
    //Parse conf before instantiating actorSystem to ensure fast pre check of config
    val conf = new Conf(args)
    initLogging(conf)
    initConfig(conf)

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
      Await.result(Http().shutdownAllConnectionPools(), 60.seconds)
      actorSystem.terminate()
      Await.result(actorSystem.whenTerminated, 60.seconds)
    }
  }

  private def executeWithSystem(conf: Conf)(implicit actorSystem: ActorSystem): Int = {
    implicit val logger = new AkkaLogging(akka.event.Logging.getLogger(actorSystem, this))
    implicit val materializer = ActorMaterializer.create(actorSystem)

    val admin = new WhiskAdmin(conf)
    val result = Try {
      val f = admin.executeCommand()
      Await.result(f, admin.timeout)
    }
    result match {
      case Success(r) =>
        r match {
          case Right(msg) =>
            println(msg)
            0
          case Left(e) =>
            printErr(e.message)
            e.code
        }
      case Failure(e) =>
        e match {
          case _: ConfigReaderException[_] =>
            printErr("Incomplete config. Provide application.conf via '-c' option")
            if (conf.verboseEnabled) {
              e.printStackTrace()
            }
          case _ =>
            e.printStackTrace()
        }
        3
    }
  }

  private def initConfig(conf: Conf): Unit = {
    val file = conf.configFile.getOrElse {
      new File("../whisk.conf")
    }
    if (file.exists()) {
      System.setProperty("config.file", file.getAbsolutePath)
    }
  }

  private def initLogging(conf: Conf): Unit = {
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

  private def printErr(message: String): Unit = {
    //Taken from ScallopConf
    if (overrideColorOutput.value.getOrElse(System.console() != null)) {
      Console.err.println("[\u001b[31m%s\u001b[0m] Error: %s" format (printedName, message))
    } else {
      // no colors on output
      Console.err.println("[%s] Error: %s" format (printedName, message))
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

case class WhiskAdmin(conf: Conf)(implicit val actorSystem: ActorSystem,
                                  implicit val materializer: ActorMaterializer,
                                  implicit val logging: Logging) {
  implicit val tid = TransactionId(TransactionId.systemPrefix + "cli")
  def executeCommand(): Future[Either[CommandError, String]] = {
    conf.subcommands match {
      case List(cmd: UserCommand, x)   => cmd.exec(x)
      case List(cmd: LimitsCommand, x) => cmd.exec(x)
    }
  }

  def timeout: Duration = {
    conf.subcommands match {
      case _ => conf.timeout()
    }
  }
}
