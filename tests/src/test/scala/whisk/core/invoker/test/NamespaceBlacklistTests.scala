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

package whisk.core.invoker.test

import akka.stream.ActorMaterializer
import common.{StreamLogging, WskActorSystem}
import org.junit.runner.RunWith
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, Matchers}
import pureconfig.loadConfigOrThrow
import spray.json.DefaultJsonProtocol._
import spray.json._
import whisk.common.TransactionId
import whisk.core.database.CouchDbConfig
import whisk.core.ConfigKeys
import whisk.core.database.test.{DbUtils, ExtendedCouchDbRestClient}
import whisk.core.entity._
import whisk.core.invoker.NamespaceBlacklist
import whisk.utils.{retry => testRetry}

import scala.concurrent.duration._

@RunWith(classOf[JUnitRunner])
class NamespaceBlacklistTests
    extends FlatSpec
    with Matchers
    with DbUtils
    with ScalaFutures
    with IntegrationPatience
    with WskActorSystem
    with StreamLogging {

  behavior of "NamespaceBlacklist"

  implicit val materializer = ActorMaterializer()
  implicit val tid = TransactionId.testing

  val dbConfig = loadConfigOrThrow[CouchDbConfig](ConfigKeys.couchdb)
  val authStore = WhiskAuthStore.datastore()
  val subjectsDb = new ExtendedCouchDbRestClient(
    dbConfig.protocol,
    dbConfig.host,
    dbConfig.port,
    dbConfig.username,
    dbConfig.password,
    dbConfig.databaseFor[WhiskAuth])

  /* Identities needed for the first test */
  val uuid1 = UUID()
  val uuid2 = UUID()
  val uuid3 = UUID()
  val identities = Seq(
    Identity(
      Subject(),
      Namespace(EntityName("testnamespace1"), uuid1),
      BasicAuthenticationAuthKey(uuid1, Secret()),
      Set.empty,
      UserLimits(invocationsPerMinute = Some(0))),
    Identity(
      Subject(),
      Namespace(EntityName("testnamespace2"), uuid2),
      BasicAuthenticationAuthKey(uuid2, Secret()),
      Set.empty,
      UserLimits(concurrentInvocations = Some(0))),
    Identity(
      Subject(),
      Namespace(EntityName("testnamespace3"), uuid3),
      BasicAuthenticationAuthKey(uuid3, Secret()),
      Set.empty,
      UserLimits(invocationsPerMinute = Some(1), concurrentInvocations = Some(1))))

  /* Subject document needed for the second test */
  val uuid4 = UUID()
  val uuid5 = UUID()
  val ak4 = BasicAuthenticationAuthKey(uuid4, Secret())
  val ak5 = BasicAuthenticationAuthKey(uuid5, Secret())
  val ns4 = Namespace(EntityName("different1"), uuid4)
  val ns5 = Namespace(EntityName("different2"), uuid5)
  val subject = WhiskAuth(Subject(), Set(WhiskNamespace(ns4, ak4), WhiskNamespace(ns5, ak5)))
  val blockedSubject = JsObject(subject.toJson.fields + ("blocked" -> true.toJson))

  val blockedNamespacesCount = 2 + subject.namespaces.size

  def authToIdentities(auth: WhiskAuth): Set[Identity] = {
    auth.namespaces.map { ns =>
      Identity(auth.subject, ns.namespace, ns.authkey, Set.empty, UserLimits())
    }
  }

  override protected def withFixture(test: NoArgTest) = {
    assume(isCouchStore(authStore))
    super.withFixture(test)
  }

  override def beforeAll() = {
    val documents = identities.map { i =>
      (i.namespace.name + "/limits", i.limits.toJson.asJsObject)
    } :+ (subject.subject.asString, blockedSubject)

    // Add all documents to the database
    documents.foreach { case (id, doc) => subjectsDb.putDoc(id, doc).futureValue }

    // Waits for the 2 blocked identities + the namespaces of the blocked subject
    waitOnView(subjectsDb, NamespaceBlacklist.view.ddoc, NamespaceBlacklist.view.view, blockedNamespacesCount)(
      executionContext,
      1.minute)
  }

  override def afterAll() = {
    val ids = identities.map(_.namespace.name + "/limits") :+ subject.subject.asString

    // Force remove all documents with those ids by first getting and then deleting the documents
    ids.foreach { id =>
      val docE = subjectsDb.getDoc(id).futureValue
      docE shouldBe 'right
      val doc = docE.right.get
      subjectsDb
        .deleteDoc(doc.fields("_id").convertTo[String], doc.fields("_rev").convertTo[String])
        .futureValue
    }

    super.afterAll()
  }

  it should "mark a namespace as blocked if limit is 0 in database or if one of its subjects is blocked" in {
    val blacklist = new NamespaceBlacklist(authStore)

    testRetry({
      blacklist.refreshBlacklist().futureValue should have size blockedNamespacesCount
    }, 60, Some(1.second))

    identities.map(blacklist.isBlacklisted) shouldBe Seq(true, true, false)
    authToIdentities(subject).toSeq.map(blacklist.isBlacklisted) shouldBe Seq(true, true)
  }
}
