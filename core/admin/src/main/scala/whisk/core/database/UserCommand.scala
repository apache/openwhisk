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

package whisk.core.database

import java.util.UUID

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import org.rogach.scallop.{ScallopConfBase, Subcommand}
import spray.json.{JsBoolean, JsObject, JsValue, RootJsonFormat}
import whisk.common.{Logging, TransactionId}
import whisk.core.cli.{CommandError, CommandMessages, IllegalState, WhiskCommand}
import whisk.core.database.UserCommand.ExtendedAuth
import whisk.core.entity.types._
import whisk.core.entity.{AuthKey, DocInfo, EntityName, Subject, WhiskAuth, WhiskDocumentReader, WhiskNamespace}
import whisk.http.Messages
import whisk.spi.SpiLoader

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.classTag
import scala.util.Try

class UserCommand extends Subcommand("user") with WhiskCommand {
  descr("manage users")

  class CreateUserCmd extends Subcommand("create") {
    descr("create a user and show authorization key")
    val auth =
      opt[String](
        descr = "the uuid:key to initialize the subject authorization key with",
        argName = "AUTH",
        short = 'u')
    val namespace =
      opt[String](descr = "create key for given namespace instead (defaults to subject id)", argName = "NAMESPACE")
    val subject = trailArg[String](descr = "the subject to create")

    validate(subject) { s =>
      if (s.length < 5) {
        Left(CommandMessages.shortName)
      } else {
        Right(Unit)
      }
    }

    validate(auth) { a =>
      a.split(":") match {
        case Array(uuid, key) =>
          if (key.length < 64) {
            Left(CommandMessages.shortKey)
          } else if (!isUUID(uuid)) {
            Left(CommandMessages.invalidUUID)
          } else {
            Right(Unit)
          }
        case _ => Left(s"failed to determine authorization id and key: $a")
      }

    }

    def isUUID(u: String) = Try(UUID.fromString(u)).isSuccess

    def desiredNamespace = EntityName(namespace.getOrElse(subject()).trim)

    def authKey: AuthKey = auth.map(AuthKey(_)).getOrElse(AuthKey())
  }

  val create = new CreateUserCmd

  addSubcommand(create)

  val delete = new Subcommand("delete") {
    descr("delete a user")
    val subject = trailArg[String](descr = "the subject to delete")
    val namespace =
      opt[String](descr = "delete key for given namespace only", argName = "NAMESPACE")
  }
  addSubcommand(delete)

  val get = new Subcommand("get") {
    descr("get authorization key for user")

    val subject = trailArg[String](descr = "the subject to get key for")
    val namespace =
      opt[String](descr = "the namespace to get the key for, defaults to subject id", argName = "NAMESPACE")

    val all = opt[Boolean](descr = "list all namespaces and their keys")
  }
  addSubcommand(get)

  def exec(cmd: ScallopConfBase)(implicit system: ActorSystem,
                                 logging: Logging,
                                 materializer: ActorMaterializer,
                                 transid: TransactionId): Future[Either[CommandError, String]] = {
    implicit val executionContext = system.dispatcher
    val authStore = UserCommand.createDataStore()
    val result = cmd match {
      case `create` => createUser(authStore)
      case `delete` => deleteUser(authStore)
      case `get`    => getKey(authStore)
    }
    result.onComplete { _ =>
      authStore.shutdown()
    }
    result
  }

  def createUser(authStore: AuthStore)(implicit transid: TransactionId,
                                       ec: ExecutionContext): Future[Either[CommandError, String]] = {
    authStore.get[ExtendedAuth](DocInfo(create.subject())).flatMap { auth =>
      if (auth.isBlocked) {
        Future.successful(Left(IllegalState(CommandMessages.subjectBlocked)))
      } else if (auth.namespaces.exists(_.name == create.desiredNamespace)) {
        Future.successful(Left(IllegalState(CommandMessages.namespaceExists)))
      } else {
        val newNS = auth.namespaces + WhiskNamespace(create.desiredNamespace, create.authKey)
        val newAuth = WhiskAuth(auth.subject, newNS).revision[WhiskAuth](auth.rev)
        authStore.put(newAuth).map(_ => Right(create.authKey.compact))
      }
    }
  }.recoverWith {
    case _: NoDocumentException =>
      val auth =
        WhiskAuth(Subject(create.subject()), Set(WhiskNamespace(create.desiredNamespace, create.authKey)))
      authStore.put(auth).map(_ => Right(create.authKey.compact))
  }

  def deleteUser(authStore: AuthStore)(implicit transid: TransactionId,
                                       ec: ExecutionContext): Future[Either[CommandError, String]] = {
    authStore
      .get[ExtendedAuth](DocInfo(delete.subject()))
      .flatMap { auth =>
        delete.namespace
          .map { namespaceToDelete =>
            val newNS = auth.namespaces.filter(_.name.asString != namespaceToDelete)
            if (newNS == auth.namespaces) {
              Future.successful(
                Left(IllegalState(CommandMessages.namespaceMissing(namespaceToDelete, delete.subject()))))
            } else {
              val newAuth = WhiskAuth(auth.subject, newNS).revision[WhiskAuth](auth.rev)
              authStore.put(newAuth).map(_ => Right(CommandMessages.namespaceDeleted))
            }
          }
          .getOrElse {
            authStore.del(auth.docinfo).map(_ => Right(CommandMessages.subjectDeleted))
          }
      }
      .recover {
        case _: NoDocumentException =>
          Left(IllegalState(CommandMessages.subjectMissing))
      }
  }

  def getKey(authStore: AuthStore)(implicit transid: TransactionId,
                                   ec: ExecutionContext): Future[Either[CommandError, String]] = {
    authStore
      .get[ExtendedAuth](DocInfo(get.subject()))
      .map { auth =>
        if (get.all.isSupplied) {
          val msg = auth.namespaces.map(ns => s"${ns.name}\t${ns.authkey.compact}").mkString("\n")
          Right(msg)
        } else {
          val ns = get.namespace.getOrElse(get.subject())
          auth.namespaces
            .find(_.name.asString == ns)
            .map(n => Right(n.authkey.compact))
            .getOrElse(Left(IllegalState(CommandMessages.namespaceMissing(ns, get.subject()))))
        }
      } recover {
      case _: NoDocumentException =>
        Left(IllegalState(CommandMessages.subjectMissing))
    }
  }
}

object UserCommand {
  def createDataStore()(implicit system: ActorSystem,
                        logging: Logging,
                        materializer: ActorMaterializer): ArtifactStore[WhiskAuth] =
    SpiLoader
      .get[ArtifactStoreProvider]
      .makeStore[WhiskAuth]()(
        classTag[WhiskAuth],
        ExtendedAuthFormat,
        WhiskDocumentReader,
        system,
        logging,
        materializer)

  class ExtendedAuth(subject: Subject, namespaces: Set[WhiskNamespace], blocked: Option[Boolean])
      extends WhiskAuth(subject, namespaces) {
    override def toJson: JsObject =
      blocked.map(b => JsObject(super.toJson.fields + ("blocked" -> JsBoolean(b)))).getOrElse(super.toJson)

    def isBlocked: Boolean = blocked.getOrElse(false)
  }

  private object ExtendedAuthFormat extends RootJsonFormat[WhiskAuth] {
    override def write(obj: WhiskAuth): JsValue = {
      obj.toDocumentRecord
    }

    override def read(json: JsValue): WhiskAuth = {
      val r = Try[ExtendedAuth] {
        val auth = WhiskAuth.serdes.read(json)
        val blocked = json.asJsObject.fields.get("blocked") match {
          case Some(b: JsBoolean) => Some(b.value)
          case _                  => None
        }
        new ExtendedAuth(auth.subject, auth.namespaces, blocked).revision[ExtendedAuth](auth.rev)
      }
      if (r.isSuccess) r.get else throw DocumentUnreadable(Messages.corruptedEntity)
    }
  }
}
