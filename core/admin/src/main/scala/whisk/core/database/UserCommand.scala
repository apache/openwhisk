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

import org.rogach.scallop.{ScallopConfBase, Subcommand}
import whisk.core.cli.WhiskCommand
import whisk.core.entity.{AuthKey, WhiskAuth}
import whisk.core.entity.types._

import scala.util.Try

object UserCommand extends Subcommand("user") with WhiskCommand {

  object CreateUserCmd extends Subcommand("create") {
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
        Left("Subject name must be at least 5 characters")
      } else {
        Right(Unit)
      }
    }

    validate(auth) { a =>
      a.split(":") match {
        case Array(uuid, key) =>
          if (key.length < 64) {
            Left("authorization key must be at least 64 characters long")
          } else if (!isUUID(uuid)) {
            Left("authorization id is not a valid UUID")
          } else {
            Right(Unit)
          }
        case _ => Left(s"failed to determine authorization id and key: $a")
      }

    }

    def isUUID(u: String) = Try(UUID.fromString(u)).isSuccess

    def desiredNamespace = namespace.getOrElse(subject()).trim

    def authKey(): AuthKey = auth.map(AuthKey(_)).getOrElse(AuthKey())
  }

  addSubcommand(CreateUserCmd)

  val delete = new Subcommand("delete") {
    descr("delete a user")
    val subject = trailArg[String](descr = "the subject to delete")
    val namespace =
      opt[String](descr = "delete key for given namespace only", argName = "NAMESPACE")
  }
  addSubcommand(delete)

  def exec(cmd: ScallopConfBase)(implicit authStore: AuthStore) = {
    cmd match {
      case CreateUserCmd => createUser()
    }
  }

  def createUser()(implicit authStore: AuthStore) = {
    println("Create user")
  }

}
