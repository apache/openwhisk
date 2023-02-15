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

package org.apache.openwhisk.core.invoker.test

import common.{StreamLogging, WskActorSystem}
import org.junit.runner.RunWith
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, Matchers}
import spray.json._
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.database.test.DbUtils
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.invoker.NamespaceBlacklist
import org.apache.openwhisk.utils.{retry => testRetry}

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

  implicit val tid = TransactionId.testing

  val authStore = WhiskAuthStore.datastore()

  val limitsAndAuths = Seq(
    new LimitEntity(EntityName("testnamespace1"), UserLimits(invocationsPerMinute = Some(0))),
    new LimitEntity(EntityName("testnamespace2"), UserLimits(concurrentInvocations = Some(0))),
    new LimitEntity(
      EntityName("testnamespace3"),
      UserLimits(invocationsPerMinute = Some(1), concurrentInvocations = Some(1))))

  /* Subject document needed for the second test */
  val uuid4 = UUID()
  val uuid5 = UUID()
  val ak4 = BasicAuthenticationAuthKey(uuid4, Secret())
  val ak5 = BasicAuthenticationAuthKey(uuid5, Secret())
  val ns4 = Namespace(EntityName("different1"), uuid4)
  val ns5 = Namespace(EntityName("different2"), uuid5)
  val blockedSubject = new ExtendedAuth(Subject(), Set(WhiskNamespace(ns4, ak4), WhiskNamespace(ns5, ak5)), true)

  val blockedNamespacesCount = 2 + blockedSubject.namespaces.size

  private def authToIdentities(auth: WhiskAuth): Set[Identity] = {
    auth.namespaces.map { ns =>
      Identity(auth.subject, ns.namespace, ns.authkey)
    }
  }

  private def limitToIdentity(limit: LimitEntity): Identity = {
    val namespace = limit.docid.id.dropRight("/limits".length)
    Identity(limit.subject, Namespace(EntityName(namespace), UUID()), BasicAuthenticationAuthKey(UUID(), Secret()))
  }

  override def beforeAll() = {
    limitsAndAuths foreach (put(authStore, _))
    put(authStore, blockedSubject)
    waitOnView(authStore, blockedNamespacesCount, NamespaceBlacklist.view)
  }

  override def afterAll() = {
    cleanup()
    super.afterAll()
  }

  it should "mark a namespace as blocked if limit is 0 in database or if one of its subjects is blocked" in {
    val blacklist = new NamespaceBlacklist(authStore)

    testRetry({
      blacklist.refreshBlacklist().futureValue should have size blockedNamespacesCount
    }, 60, Some(1.second))

    limitsAndAuths.map(limitToIdentity).map(blacklist.isBlacklisted) shouldBe Seq(true, true, false)
    authToIdentities(blockedSubject).toSeq.map(blacklist.isBlacklisted) shouldBe Seq(true, true)
  }

  class LimitEntity(name: EntityName, limits: UserLimits) extends WhiskAuth(Subject(), namespaces = Set.empty) {
    override def docid = DocId(s"${name.name}/limits")

    override def toJson = UserLimits.serdes.write(limits).asJsObject
  }

  class ExtendedAuth(subject: Subject, namespaces: Set[WhiskNamespace], blocked: Boolean)
      extends WhiskAuth(subject, namespaces) {
    override def toJson = JsObject(super.toJson.fields + ("blocked" -> JsBoolean(blocked)))
  }
}
