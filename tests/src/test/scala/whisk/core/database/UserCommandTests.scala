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
import whisk.core.entity.{AuthKey, DocId, DocInfo, EntityName, Identity, Subject, WhiskAuth, WhiskNamespace}

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.Duration
import scala.util.Try
import whisk.core.database.UserCommand.ExtendedAuth

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
    val admin = WhiskAdmin(conf)
    admin.executeCommand().futureValue.right.get shouldBe key.compact
  }

  it should "add namespace to existing user" in {
    val subject = newSubject()
    val key = AuthKey()

    //Create user
    WhiskAdmin(new Conf(Seq("user", "create", "--auth", key.compact, subject))).executeCommand().futureValue

    //Add new namespace
    val key2 = AuthKey()
    WhiskAdmin(new Conf(Seq("user", "create", "--auth", key2.compact, "--namespace", "foo", subject)))
      .executeCommand()
      .futureValue
      .right
      .get shouldBe key2.compact

    //Adding same namespace should fail
    WhiskAdmin(new Conf(Seq("user", "create", "--auth", key2.compact, "--namespace", "foo", subject)))
      .executeCommand()
      .futureValue
      .left
      .get
      .message shouldBe CommandMessages.namespaceExists

    //It should be possible to lookup by new namespace
    implicit val tid = transid()
    val i = Identity.get(authStore, EntityName("foo")).futureValue
    i.subject.asString shouldBe subject
  }

  it should "not add namespace to a blocked user" in {
    val subject = newSubject()
    val ns = randomString()
    val blockedAuth = new ExtendedAuth(Subject(subject), Set(WhiskNamespace(EntityName(ns), AuthKey())), Some(true))
    val authStore2 = UserCommand.createDataStore()

    implicit val tid = transid()
    authStore2.put(blockedAuth).futureValue

    WhiskAdmin(new Conf(Seq("user", "create", "--namespace", "foo", subject)))
      .executeCommand()
      .futureValue
      .left
      .get
      .message shouldBe CommandMessages.subjectBlocked

    authStore2.shutdown()
  }

  behavior of "delete user"

  it should "fail deleting non existing user" in {
    WhiskAdmin(new Conf(Seq("user", "delete", "non-existing-user")))
      .executeCommand()
      .futureValue
      .left
      .get
      .message shouldBe CommandMessages.subjectMissing
  }

  it should "delete existing user" in {
    val subject = newSubject()
    val key = AuthKey()

    //Create user
    WhiskAdmin(new Conf(Seq("user", "create", "--auth", key.compact, subject))).executeCommand().futureValue

    WhiskAdmin(new Conf(Seq("user", "delete", subject)))
      .executeCommand()
      .futureValue
      .right
      .get shouldBe CommandMessages.subjectDeleted
  }

  it should "remove namespace from existing user" in {
    implicit val tid = transid()
    val subject = newSubject()

    val ns1 = newNS()
    val ns2 = newNS()

    val auth = WhiskAuth(Subject(subject), Set(ns1, ns2))

    put(authStore, auth)

    WhiskAdmin(new Conf(Seq("user", "delete", "--namespace", ns1.name.asString, subject)))
      .executeCommand()
      .futureValue
      .right
      .get shouldBe CommandMessages.namespaceDeleted

    val authFromDB = authStore.get[WhiskAuth](DocInfo(DocId(subject))).futureValue
    authFromDB.namespaces shouldBe Set(ns2)
  }

  it should "not remove missing namespace" in {
    implicit val tid = transid()
    val subject = newSubject()
    val auth = WhiskAuth(Subject(subject), Set(newNS(), newNS()))

    put(authStore, auth)
    WhiskAdmin(new Conf(Seq("user", "delete", "--namespace", "non-existing-ns", subject)))
      .executeCommand()
      .futureValue
      .left
      .get
      .message shouldBe CommandMessages.namespaceMissing("non-existing-ns", subject)

  }

  behavior of "get key"

  it should "not get key for missing subject" in {
    WhiskAdmin(new Conf(Seq("user", "get", "non-existing-user")))
      .executeCommand()
      .futureValue
      .left
      .get
      .message shouldBe CommandMessages.subjectMissing
  }

  it should "get key for existing user" in {
    implicit val tid = transid()
    val subject = newSubject()

    val ns1 = newNS()
    val ns2 = newNS()
    val ns3 = WhiskNamespace(EntityName(subject), AuthKey())

    val auth = WhiskAuth(Subject(subject), Set(ns1, ns2, ns3))
    put(authStore, auth)

    WhiskAdmin(new Conf(Seq("user", "get", "--namespace", ns1.name.asString, subject)))
      .executeCommand()
      .futureValue
      .right
      .get shouldBe ns1.authkey.compact

    val all = WhiskAdmin(new Conf(Seq("user", "get", "--all", subject)))
      .executeCommand()
      .futureValue
      .right
      .get

    all should include(ns1.authkey.compact)
    all should include(ns2.authkey.compact)
    all should include(ns3.authkey.compact)

    //Is --namespace is not there look by subject
    WhiskAdmin(new Conf(Seq("user", "get", subject)))
      .executeCommand()
      .futureValue
      .right
      .get shouldBe ns3.authkey.compact

    //Look for namespace which does not exist
    WhiskAdmin(new Conf(Seq("user", "get", "--namespace", "non-existing-ns", subject)))
      .executeCommand()
      .futureValue
      .left
      .get
      .message shouldBe CommandMessages.namespaceMissing("non-existing-ns", subject)

  }

  behavior of "whois"

  it should "not get subject for missing subject" in {
    WhiskAdmin(new Conf(Seq("user", "whois", AuthKey().compact)))
      .executeCommand()
      .futureValue
      .left
      .get
      .message shouldBe CommandMessages.subjectMissing
  }

  it should "get key for existing user" in {
    implicit val tid = transid()
    val subject = newSubject()

    val ns1 = newNS()
    val ns3 = WhiskNamespace(EntityName(subject), AuthKey())

    val auth = WhiskAuth(Subject(subject), Set(ns1, ns3))
    put(authStore, auth)

    val result = WhiskAdmin(new Conf(Seq("user", "whois", ns1.authkey.compact)))
      .executeCommand()
      .futureValue
      .right
      .get

    result should include(subject)
    result should include(ns1.name.asString)
  }

  behavior of "list"

  it should "list keys associated with given namespace" in {
    implicit val tid = transid()
    def newWhiskAuth(ns: String*) =
      WhiskAuth(Subject(newSubject()), ns.map(n => WhiskNamespace(EntityName(n), AuthKey())).toSet)

    def key(a: WhiskAuth, ns: String) = a.namespaces.find(_.name.asString == ns).map(_.authkey.compact)

    val ns1 = randomString()
    val ns2 = randomString()
    val ns3 = randomString()

    val a1 = newWhiskAuth(ns1)
    val a2 = newWhiskAuth(ns1, ns2)
    val a3 = newWhiskAuth(ns1, ns2, ns3)

    Seq(a1, a2, a3).foreach(put(authStore, _))

    //Check negative case
    resultNotOk("user", "list", "non-existing-ns") shouldBe CommandMessages.namespaceMissing("non-existing-ns")

    //Check all results
    val r1 = resultOk("user", "list", ns1)
    r1.split("\n").length shouldBe 3
    r1 should include(a1.subject.asString)
    r1 should include(key(a2, ns1).get)

    //Check limit
    val r2 = resultOk("user", "list", "-p", "2", ns1)
    r2.split("\n").length shouldBe 2

    //Check key only
    val r3 = resultOk("user", "list", "-k", ns1)
    r3 should include(key(a2, ns1).get)
    r3 should not include a2.subject.asString
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

  private def resultOk(args: String*) =
    WhiskAdmin(new Conf(args.toSeq))
      .executeCommand()
      .futureValue
      .right
      .get

  private def resultNotOk(args: String*) =
    WhiskAdmin(new Conf(args.toSeq))
      .executeCommand()
      .futureValue
      .left
      .get
      .message

  private def newNS() = WhiskNamespace(EntityName(randomString()), AuthKey())

  private def newSubject(): String = {
    val subject = randomString()
    usersToDelete += subject
    subject
  }
}
