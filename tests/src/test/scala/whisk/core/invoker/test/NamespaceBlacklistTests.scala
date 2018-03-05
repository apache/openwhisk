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
import common.{StreamLogging, WhiskProperties, WskActorSystem}
import org.junit.runner.RunWith
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, Matchers}
import spray.json._
import whisk.common.TransactionId
import whisk.core.WhiskConfig
import whisk.core.database.CouchDbRestClient
import whisk.core.database.test.{DbUtils, ExtendedCouchDbRestClient}
import whisk.core.entity._
import whisk.core.invoker.NamespaceBlacklist
import spray.json.DefaultJsonProtocol._

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

  val config = new WhiskConfig(WhiskAuthStore.requiredProperties)

  implicit val materializer = ActorMaterializer()
  implicit val tid = TransactionId.testing

  val authStore = WhiskAuthStore.datastore(config)
  val subjectsDb = new ExtendedCouchDbRestClient(
    WhiskProperties.getProperty(WhiskConfig.dbProtocol),
    WhiskProperties.getProperty(WhiskConfig.dbHost),
    WhiskProperties.getProperty(WhiskConfig.dbPort).toInt,
    WhiskProperties.getProperty(WhiskConfig.dbUsername),
    WhiskProperties.getProperty(WhiskConfig.dbPassword),
    WhiskProperties.getProperty(WhiskConfig.dbAuths))

  val identities = Seq(
    Identity(Subject(), EntityName("testnamespace1"), AuthKey(), Set.empty, UserLimits(invocationsPerMinute = Some(0))),
    Identity(
      Subject(),
      EntityName("testnamespace2"),
      AuthKey(),
      Set.empty,
      UserLimits(concurrentInvocations = Some(0))),
    Identity(
      Subject(),
      EntityName("testnamespace3"),
      AuthKey(),
      Set.empty,
      UserLimits(invocationsPerMinute = Some(1), concurrentInvocations = Some(1))))

  override def beforeAll() = {
    identities.foreach { id =>
      putLimitsForIdentity(subjectsDb, id)
    }
    // Wait on view
    waitOnView(subjectsDb, NamespaceBlacklist.view.ddoc, NamespaceBlacklist.view.view, 2)(executionContext, 1.minute)
  }

  override def afterAll() = {
    identities.foreach { id =>
      deleteLimitDocForIdentity(subjectsDb, id)
    }
    super.afterAll()
  }

  def putLimitsForIdentity(db: CouchDbRestClient, identity: Identity) = {
    db.putDoc(identity.namespace.name + "/limits", identity.limits.toJson.asJsObject).futureValue
  }

  def deleteLimitDocForIdentity(db: CouchDbRestClient, identity: Identity) = {
    val doc = subjectsDb.getDoc(identity.namespace.name + "/limits").futureValue
    doc shouldBe 'right
    subjectsDb
      .deleteDoc(doc.right.get.fields("_id").convertTo[String], doc.right.get.fields("_rev").convertTo[String])
      .futureValue
  }

  it should "mark a namespace as blocked if limit is 0 in database" in {
    val blacklist = new NamespaceBlacklist(authStore)

    // Execute refresh
    blacklist.refreshBlacklist().futureValue should have size 2

    // execute isBlacklisted -> true
    identities.map(blacklist.isBlacklisted) shouldBe Seq(true, true, false)
  }
}
