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

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner
import whisk.common.TransactionId
import whisk.core.cli.{CommandMessages, Conf, WhiskAdmin}
import whisk.core.entity.{AuthKey, DocInfo, WhiskAuth}

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.Duration
import scala.util.Try

@RunWith(classOf[JUnitRunner])
class UserCommandTests extends FlatSpec with WhiskAdminCliTestBase {
  private val usersToDelete = ListBuffer[String]()

  behavior of "create user"

  it should "fail for subject less than length 5" in {
    the[Exception] thrownBy {
      new Conf(Seq("user", "create", "foo"))
    } should have message CommandMessages.shortName
  }

  it should "fail for short key" in {
    the[Exception] thrownBy {
      new Conf(Seq("user", "create", "--auth", "uid:shortKey", "foobar"))
    } should have message CommandMessages.shortKey
  }

  it should "fail for invalid uuid" in {
    val key = "x" * 64
    the[Exception] thrownBy {
      new Conf(Seq("user", "create", "--auth", s"uid:$key", "foobar"))
    } should have message CommandMessages.invalidUUID
  }

  it should "create a user" in {
    val subject = newSubject()
    val key = AuthKey()
    val conf = new Conf(Seq("user", "create", "--auth", key.compact, subject))
    val admin = new WhiskAdmin(conf)
    admin.executeCommand().futureValue.right.get shouldBe key.compact
  }

  override def cleanup()(implicit timeout: Duration): Unit = {
    implicit val tid = TransactionId.testing
    usersToDelete.map { u =>
      Try {
        val auth = authStore.get[WhiskAuth](DocInfo(u)).futureValue
        delete(authStore, auth.docinfo)
      }
    }
    usersToDelete.clear()
    super.cleanup()
  }

  private def newSubject(): String = {
    val subject = randomString()
    usersToDelete += subject
    subject
  }
}
