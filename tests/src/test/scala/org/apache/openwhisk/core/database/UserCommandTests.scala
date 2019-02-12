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

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.cli.{CommandMessages, Conf, WhiskAdmin}
import org.apache.openwhisk.core.entity._

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.Duration
import scala.util.Try
import org.apache.openwhisk.core.database.UserCommand.ExtendedAuth

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
    val key = BasicAuthenticationAuthKey()
    val conf = new Conf(Seq("user", "create", "--auth", key.compact, subject))
    val admin = WhiskAdmin(conf)
    admin.executeCommand().futureValue.right.get shouldBe key.compact
  }

  it should "create a user with default key" in {
    val subject = newSubject()
    val generatedKey = resultOk("user", "create", subject)
    resultOk("user", "get", subject) shouldBe generatedKey
  }

  it should "force update an existing user" in {
    val subject = newSubject()
    val oldKey = resultOk("user", "create", "--force", subject)
    resultOk("user", "get", subject) shouldBe oldKey

    // Force update with provided auth uuid:key
    val key = BasicAuthenticationAuthKey()
    val newKey = resultOk("user", "create", "--auth", key.compact, "--force", subject)
    resultOk("user", "get", subject) shouldBe newKey
    newKey shouldBe key.compact

    // Force update without auth, uuid:key is randomly generated
    val generatedKey = resultOk("user", "create", "--force", subject)
    generatedKey should not be newKey
    generatedKey should not be oldKey
  }

  it should "create a user or update an existing user with revoke flag" in {
    val subject = newSubject()
    val oldKey = resultOk("user", "create", "--revoke", subject)
    resultOk("user", "get", subject) shouldBe oldKey
    val newKey = resultOk("user", "create", "--revoke", subject)
    resultOk("user", "get", subject) shouldBe newKey
    val oldAuthKey = BasicAuthenticationAuthKey(oldKey)
    val newAuthKey = BasicAuthenticationAuthKey(newKey)
    newAuthKey.uuid shouldBe oldAuthKey.uuid
    newAuthKey.key should not be oldAuthKey.key
  }

  it should "add namespace to existing user" in {
    val subject = newSubject()
    val key = BasicAuthenticationAuthKey()

    //Create user
    WhiskAdmin(new Conf(Seq("user", "create", "--auth", key.compact, subject))).executeCommand().futureValue

    //Add new namespace
    val key2 = BasicAuthenticationAuthKey()
    resultOk("user", "create", "--auth", key2.compact, "--namespace", "foo", subject) shouldBe key2.compact

    //Adding same namespace should fail
    resultNotOk("user", "create", "--auth", key2.compact, "--namespace", "foo", subject) shouldBe CommandMessages.namespaceExists

    //Adding same namespace with force flag should update the namespace with specified uuid:key
    val newKey = resultOk("user", "create", "--force", "--auth", key2.compact, "--namespace", "foo", subject)
    newKey shouldBe key2.compact

    //Adding same namespace with force flag without auth should regenerate random uuid:key
    val generatedKey = resultOk("user", "create", "--force", "--namespace", "foo", subject)
    generatedKey should not be key2.compact
    generatedKey should not be key.compact

    //It should be possible to lookup by new namespace
    implicit val tid = transid()
    val i = Identity.get(authStore, EntityName("foo")).futureValue
    i.subject.asString shouldBe subject
    resultOk("user", "get", "--namespace", "foo", subject) shouldBe generatedKey
  }

  it should "not add namespace to a blocked user" in {
    val subject = newSubject()
    val ns = randomString()
    val blockedAuth =
      new ExtendedAuth(Subject(subject), Set(newNS(EntityName(ns), BasicAuthenticationAuthKey())), Some(true))
    val authStore2 = UserCommand.createDataStore()

    implicit val tid = transid()
    authStore2.put(blockedAuth).futureValue

    resultNotOk("user", "create", "--namespace", "foo", subject) shouldBe CommandMessages.subjectBlocked

    authStore2.shutdown()
  }

  behavior of "delete user"

  it should "fail deleting non existing user" in {
    resultNotOk("user", "delete", "non-existing-user") shouldBe CommandMessages.subjectMissing
  }

  it should "delete existing user" in {
    val subject = newSubject()
    val key = BasicAuthenticationAuthKey()

    //Create user
    WhiskAdmin(new Conf(Seq("user", "create", "--auth", key.compact, subject))).executeCommand().futureValue

    resultOk("user", "delete", subject) shouldBe CommandMessages.subjectDeleted
  }

  it should "remove namespace from existing user" in {
    implicit val tid = transid()
    val subject = newSubject()

    val ns1 = newNS()
    val ns2 = newNS()

    val auth = WhiskAuth(Subject(subject), Set(ns1, ns2))

    put(authStore, auth)

    resultOk("user", "delete", "--namespace", ns1.namespace.name.asString, subject) shouldBe CommandMessages.namespaceDeleted

    val authFromDB = authStore.get[WhiskAuth](DocInfo(DocId(subject))).futureValue
    authFromDB.namespaces shouldBe Set(ns2)
  }

  it should "not remove missing namespace" in {
    implicit val tid = transid()
    val subject = newSubject()
    val auth = WhiskAuth(Subject(subject), Set(newNS(), newNS()))

    put(authStore, auth)
    resultNotOk("user", "delete", "--namespace", "non-existing-ns", subject) shouldBe
      CommandMessages.namespaceMissing("non-existing-ns", subject)
  }

  behavior of "get key"

  it should "not get key for missing subject" in {
    resultNotOk("user", "get", "non-existing-user") shouldBe CommandMessages.subjectMissing
  }

  it should "get key for existing user" in {
    implicit val tid = transid()
    val subject = newSubject()

    val ns1 = newNS()
    val ns2 = newNS()
    val ns3 = newNS(EntityName(subject), BasicAuthenticationAuthKey())

    val auth = WhiskAuth(Subject(subject), Set(ns1, ns2, ns3))
    put(authStore, auth)

    resultOk("user", "get", "--namespace", ns1.namespace.name.asString, subject) shouldBe ns1.authkey.compact

    val all = resultOk("user", "get", "--all", subject)

    all should include(ns1.authkey.compact)
    all should include(ns2.authkey.compact)
    all should include(ns3.authkey.compact)

    //Is --namespace is not there look by subject
    resultOk("user", "get", subject) shouldBe ns3.authkey.compact

    //Look for namespace which does not exist
    resultNotOk("user", "get", "--namespace", "non-existing-ns", subject) shouldBe
      CommandMessages.namespaceMissing("non-existing-ns", subject)
  }

  behavior of "whois"

  it should "not get subject for missing subject" in {
    resultNotOk("user", "whois", BasicAuthenticationAuthKey().compact) shouldBe CommandMessages.subjectMissing
  }

  it should "get key for existing user" in {
    implicit val tid = transid()
    val subject = newSubject()

    val ns1 = newNS()
    val ns3 = newNS(EntityName(subject), BasicAuthenticationAuthKey())

    val auth = WhiskAuth(Subject(subject), Set(ns1, ns3))
    put(authStore, auth)

    val result = resultOk("user", "whois", ns1.authkey.compact)

    result should include(subject)
    result should include(ns1.namespace.name.asString)
  }

  behavior of "list"

  it should "list keys associated with given namespace" in {
    implicit val tid = transid()
    def newWhiskAuth(ns: String*) =
      WhiskAuth(Subject(newSubject()), ns.map(n => newNS(EntityName(n), BasicAuthenticationAuthKey())).toSet)

    def key(a: WhiskAuth, ns: String) = a.namespaces.find(_.namespace.name.asString == ns).map(_.authkey).get

    val ns1 = randomString()
    val ns2 = randomString()
    val ns3 = randomString()

    val a1 = newWhiskAuth(ns1)
    val a2 = newWhiskAuth(ns1, ns2)
    val a3 = newWhiskAuth(ns1, ns2, ns3)

    Seq(a1, a2, a3).foreach(put(authStore, _))

    Seq(a1, a2, a3).foreach(a => waitOnView(authStore, key(a, ns1), 1))

    //Check negative case
    resultNotOk("user", "list", "non-existing-ns") shouldBe CommandMessages.namespaceMissing("non-existing-ns")

    //Check all results
    val r1 = resultOk("user", "list", ns1)
    r1.split("\n").length shouldBe 3
    r1 should include(a1.subject.asString)
    r1 should include(key(a2, ns1).compact)

    //Check limit
    val r2 = resultOk("user", "list", "-p", "2", ns1)
    r2.split("\n").length shouldBe 2

    //Check key only
    val r3 = resultOk("user", "list", "-k", ns1)
    r3 should include(key(a2, ns1).compact)
    r3 should not include a2.subject.asString
  }

  behavior of "block"

  it should "block subjects" in {
    implicit val tid = transid()
    val a1 = WhiskAuth(Subject(newSubject()), Set(newNS()))
    val a2 = WhiskAuth(Subject(newSubject()), Set(newNS()))
    val a3 = WhiskAuth(Subject(newSubject()), Set(newNS()))

    Seq(a1, a2, a3).foreach(put(authStore, _))

    val r1 = resultOk("user", "block", a1.subject.asString, a2.subject.asString)

    val authStore2 = UserCommand.createDataStore()

    authStore2.get[ExtendedAuth](a1.docinfo).futureValue.isBlocked shouldBe true
    authStore2.get[ExtendedAuth](a2.docinfo).futureValue.isBlocked shouldBe true
    authStore2.get[ExtendedAuth](a3.docinfo).futureValue.isBlocked shouldBe false

    val r2 = resultOk("user", "unblock", a2.subject.asString, a3.subject.asString)

    authStore2.get[ExtendedAuth](a1.docinfo).futureValue.isBlocked shouldBe true
    authStore2.get[ExtendedAuth](a2.docinfo).futureValue.isBlocked shouldBe false
    authStore2.get[ExtendedAuth](a3.docinfo).futureValue.isBlocked shouldBe false

    authStore2.shutdown()
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

  private def newNS(): WhiskNamespace = newNS(EntityName(randomString()), BasicAuthenticationAuthKey())

  private def newNS(name: EntityName, authKey: BasicAuthenticationAuthKey) =
    WhiskNamespace(Namespace(name, authKey.uuid), authKey)

  private def newSubject(): String = {
    val subject = randomString()
    usersToDelete += subject
    subject
  }
}
