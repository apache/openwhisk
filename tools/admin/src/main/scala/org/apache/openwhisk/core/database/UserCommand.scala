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

package org.apache.openwhisk.core.database

import java.util.UUID

import akka.actor.ActorSystem
import akka.stream.scaladsl.{Sink, Source}
import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.core.cli.{CommandError, CommandMessages, IllegalState, WhiskCommand}
import org.apache.openwhisk.core.database.UserCommand.ExtendedAuth
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.entity.types._
import org.apache.openwhisk.http.Messages
import org.apache.openwhisk.spi.SpiLoader
import org.rogach.scallop.{ScallopConfBase, Subcommand}
import spray.json.{JsBoolean, JsObject, JsString, JsValue, RootJsonFormat}

import scala.concurrent.{ExecutionContext, Future}
import scala.language.reflectiveCalls
import scala.reflect.classTag
import scala.util.{Properties, Try}

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
    val revoke =
      opt[Boolean](descr = "revoke the current authorization key and generate a new key", short = 'r')
    val force =
      opt[Boolean](descr = "force update an existing subject authorization uuid:key", short = 'f')
    val subject = trailArg[String](descr = "the subject to create")

    validate(subject) { s =>
      if (s.length < 5) {
        Left(CommandMessages.shortName)
      } else {
        Right(())
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
            Right(())
          }
        case _ => Left(s"failed to determine authorization id and key: $a")
      }

    }

    def isUUID(u: String) = Try(UUID.fromString(u)).isSuccess

    def desiredNamespace(authKey: BasicAuthenticationAuthKey) =
      Namespace(EntityName(namespace.getOrElse(subject()).trim), authKey.uuid)
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

  val whois = new Subcommand("whois") {
    descr("identify user from an authorization key")
    val authkey = trailArg[String](descr = "the credentials to look up 'uuid:key'")
  }
  addSubcommand(whois)

  val list = new Subcommand("list") {
    descr("list authorization keys associated with a namespace")
    val namespace = trailArg[String](descr = "the namespace to lookup")

    val pick = opt[Int](descr = "show no more than N identities", argName = "N", validate = _ > 0)
    val key = opt[Boolean](descr = "show only the keys")
    val all = opt[Boolean](descr = "show all identities")

    def limit: Int = {
      if (all.isSupplied) 0
      else pick.getOrElse(0)
    }

    def showOnlyKeys = key.isSupplied
  }
  addSubcommand(list)

  val block = new Subcommand("block") {
    descr("block one or more users")
    val subjects = trailArg[List[String]](descr = "one or more users to block")
  }
  addSubcommand(block)

  val unblock = new Subcommand("unblock") {
    descr("unblock one or more users")
    val subjects = trailArg[List[String]](descr = "one or more users to unblock")
  }
  addSubcommand(unblock)

  def exec(cmd: ScallopConfBase)(implicit system: ActorSystem,
                                 logging: Logging,
                                 transid: TransactionId): Future[Either[CommandError, String]] = {
    implicit val executionContext = system.dispatcher
    val authStore = UserCommand.createDataStore()
    val result = cmd match {
      case `create`  => createUser(authStore)
      case `delete`  => deleteUser(authStore)
      case `get`     => getKey(authStore)
      case `whois`   => whoIs(authStore)
      case `list`    => list(authStore)
      case `block`   => changeUserState(authStore, block.subjects(), blocked = true)
      case `unblock` => changeUserState(authStore, unblock.subjects(), blocked = false)
    }
    result.onComplete { _ =>
      authStore.shutdown()
    }
    result
  }

  def createUser(authStore: AuthStore)(implicit transid: TransactionId,
                                       ec: ExecutionContext): Future[Either[CommandError, String]] = {
    val authKey = create.auth.map(BasicAuthenticationAuthKey(_)).getOrElse(BasicAuthenticationAuthKey())
    authStore
      .get[ExtendedAuth](DocInfo(create.subject()))
      .flatMap { auth =>
        val nsToUpdate = create.desiredNamespace(authKey).name
        val existingNS = auth.namespaces.filter(_.namespace.name != nsToUpdate)
        if (auth.isBlocked) {
          Future.successful(Left(IllegalState(CommandMessages.subjectBlocked)))
        } else if (!auth.namespaces.exists(_.namespace.name == nsToUpdate) || create.force.isSupplied) {
          val newNS = existingNS + WhiskNamespace(create.desiredNamespace(authKey), authKey)
          val newAuth = WhiskAuth(auth.subject, newNS).revision[WhiskAuth](auth.rev)
          authStore.put(newAuth).map(_ => Right(authKey.compact))
        } else if (create.revoke.isSupplied) {
          val updatedAuthKey = auth.namespaces.find(_.namespace.name == nsToUpdate).get.authkey
          val newAuthKey = new BasicAuthenticationAuthKey(updatedAuthKey.uuid, Secret())

          val newNS = existingNS + WhiskNamespace(create.desiredNamespace(newAuthKey), newAuthKey)
          val newAuth = WhiskAuth(auth.subject, newNS).revision[WhiskAuth](auth.rev)
          authStore.put(newAuth).map(_ => Right(newAuthKey.compact))
        } else {
          Future.successful(Left(IllegalState(CommandMessages.namespaceExists)))
        }
      }
      .recoverWith {
        case _: NoDocumentException =>
          val auth =
            WhiskAuth(Subject(create.subject()), Set(WhiskNamespace(create.desiredNamespace(authKey), authKey)))
          authStore.put(auth).map(_ => Right(authKey.compact))
      }
  }

  def deleteUser(authStore: AuthStore)(implicit transid: TransactionId,
                                       ec: ExecutionContext): Future[Either[CommandError, String]] = {
    authStore
      .get[ExtendedAuth](DocInfo(delete.subject()))
      .flatMap { auth =>
        delete.namespace
          .map { namespaceToDelete =>
            val newNS = auth.namespaces.filter(_.namespace.name.asString != namespaceToDelete)
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
          val msg =
            auth.namespaces.map(ns => s"${ns.namespace.name}\t${ns.authkey.compact}").mkString(Properties.lineSeparator)
          Right(msg)
        } else {
          val ns = get.namespace.getOrElse(get.subject())
          auth.namespaces
            .find(_.namespace.name.asString == ns)
            .map(n => Right(n.authkey.compact))
            .getOrElse(Left(IllegalState(CommandMessages.namespaceMissing(ns, get.subject()))))
        }
      } recover {
      case _: NoDocumentException =>
        Left(IllegalState(CommandMessages.subjectMissing))
    }
  }

  def whoIs(authStore: AuthStore)(implicit transid: TransactionId,
                                  ec: ExecutionContext): Future[Either[CommandError, String]] = {
    Identity
      .get(authStore, BasicAuthenticationAuthKey(whois.authkey()))
      .map { i =>
        val msg = Seq(s"subject: ${i.subject}", s"namespace: ${i.namespace}").mkString(Properties.lineSeparator)
        Right(msg)
      }
      .recover {
        case _: NoDocumentException =>
          Left(IllegalState(CommandMessages.subjectMissing))
      }
  }

  def list(authStore: AuthStore)(implicit transid: TransactionId,
                                 ec: ExecutionContext): Future[Either[CommandError, String]] = {
    Identity
      .list(authStore, List(list.namespace()), limit = list.limit)
      .map { rows =>
        if (rows.isEmpty) Left(IllegalState(CommandMessages.namespaceMissing(list.namespace())))
        else {
          val msg = rows
            .map { row =>
              row.getFields("id", "value") match {
                case Seq(JsString(subject), JsObject(value)) =>
                  val JsString(uuid) = value("uuid")
                  val JsString(secret) = value("key")
                  s"$uuid:$secret${if (list.showOnlyKeys) "" else s"\t$subject"}"
                case _ => throw new IllegalStateException("identities view malformed")
              }
            }
            .mkString(Properties.lineSeparator)
          Right(msg)
        }
      }
  }

  def changeUserState(authStore: AuthStore, subjects: List[String], blocked: Boolean)(
    implicit transid: TransactionId,
    system: ActorSystem,
    ec: ExecutionContext): Future[Either[CommandError, String]] = {
    Source(subjects)
      .mapAsync(1)(changeUserState(authStore, _, blocked))
      .runWith(Sink.seq[Either[CommandError, String]])
      .map { rows =>
        val lefts = rows.count(_.isLeft)
        val msg = rows
          .map {
            case Left(x)  => x.message
            case Right(x) => x
          }
          .mkString(Properties.lineSeparator)

        if (lefts > 0) Left(new CommandError(msg, lefts)) else Right(msg)
      }
  }

  private def changeUserState(authStore: AuthStore, subject: String, blocked: Boolean)(
    implicit transid: TransactionId,
    ec: ExecutionContext): Future[Either[CommandError, String]] = {
    authStore
      .get[ExtendedAuth](DocInfo(subject))
      .flatMap { auth =>
        val newAuth = new ExtendedAuth(auth.subject, auth.namespaces, Some(blocked))
        newAuth.revision[ExtendedAuth](auth.rev)
        val msg = if (blocked) CommandMessages.blocked(subject) else CommandMessages.unblocked(subject)
        authStore.put(newAuth).map(_ => Right(msg))
      }
      .recover {
        case _: NoDocumentException =>
          Left(IllegalState(CommandMessages.subjectMissing(subject)))
      }
  }
}

object UserCommand {
  def createDataStore()(implicit system: ActorSystem, logging: Logging): ArtifactStore[WhiskAuth] =
    SpiLoader
      .get[ArtifactStoreProvider]
      .makeStore[WhiskAuth]()(classTag[WhiskAuth], ExtendedAuthFormat, WhiskDocumentReader, system, logging)

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
