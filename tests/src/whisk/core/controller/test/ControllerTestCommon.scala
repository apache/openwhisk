/*
 * Copyright 2015-2016 IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.core.controller.test

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

import org.scalatest.BeforeAndAfter
import org.scalatest.BeforeAndAfterAll
import org.scalatest.FlatSpec
import org.scalatest.Matchers

import akka.actor.ActorSystem
import akka.event.Logging.InfoLevel
import spray.http.BasicHttpCredentials
import spray.routing.HttpService
import spray.testkit.ScalatestRouteTest
import whisk.common.Logging
import whisk.common.TransactionCounter
import whisk.common.TransactionId
import whisk.core.WhiskConfig
import whisk.core.connector.LoadBalancerResponse
import whisk.core.controller.WhiskActionsApi
import whisk.core.controller.WhiskServices
import whisk.core.database.test.DbUtils
import whisk.core.entitlement.Collection
import whisk.core.entitlement.EntitlementService
import whisk.core.entitlement.LocalEntitlementService
import whisk.core.entity.ActivationId
import whisk.core.entity.AuthKey
import whisk.core.entity.DocId
import whisk.core.entity.EntityName
import whisk.core.entity.Subject
import whisk.core.entity.WhiskAction
import whisk.core.entity.WhiskActivation
import whisk.core.entity.WhiskActivationStore
import whisk.core.entity.WhiskAuth
import whisk.core.entity.WhiskAuthStore
import whisk.core.entity.WhiskEntityStore
import whisk.core.entity.WhiskPackage
import whisk.core.entity.WhiskRule
import whisk.core.entity.WhiskTrigger

protected trait ControllerTestCommon
    extends FlatSpec
    with BeforeAndAfter
    with BeforeAndAfterAll
    with ScalatestRouteTest
    with Matchers
    with TransactionCounter
    with DbUtils
    with WhiskServices
    with HttpService
    with Logging {

    override val actorRefFactory = null
    implicit val routeTestTimeout = RouteTestTimeout(90 seconds)

    implicit val actorSystem = system // defined in ScalatestRouteTest
    val executionContext = actorSystem.dispatcher

    val whiskConfig = new WhiskConfig(WhiskActionsApi.requiredProperties)
    assert(whiskConfig.isValid)

    val entityStore = WhiskEntityStore.datastore(whiskConfig)
    val activationStore = WhiskActivationStore.datastore(whiskConfig)
    val authStore = WhiskAuthStore.datastore(whiskConfig)
    val entitlementService: EntitlementService = new LocalEntitlementService(whiskConfig)

    val activationId = ActivationId() // need a static activation id to test activations api
    val performLoadBalancerRequest = (lbr: WhiskServices.LoadBalancerReq) => Future {
        LoadBalancerResponse.id(activationId)
    }
    val queryActivationResponse = (activationId: ActivationId, transid: TransactionId) => Future.failed {
        new IllegalArgumentException("Unit test does not need fast path")
    }
    val consulServer = "???"

    def createTempCredentials(implicit transid: TransactionId) = {
        val auth = WhiskAuth(Subject(), AuthKey())
        put(authStore, auth)
        waitOnView(authStore, auth.uuid, 1)
        (auth, BasicHttpCredentials(auth.uuid(), auth.key()))
    }

    def deleteAction(doc: DocId)(implicit transid: TransactionId) = {
        Await.result(WhiskAction.get(entityStore, doc.asDocInfo) flatMap { doc =>
            info(this, s"deleting ${doc.docinfo}")
            WhiskAction.del(entityStore, doc.docinfo)
        }, dbOpTimeout)
    }

    def deleteActivation(doc: DocId)(implicit transid: TransactionId) = {
        Await.result(WhiskActivation.get(entityStore, doc.asDocInfo) flatMap { doc =>
            info(this, s"deleting ${doc.docinfo}")
            WhiskActivation.del(entityStore, doc.docinfo)
        }, dbOpTimeout)
    }

    def deleteTrigger(doc: DocId)(implicit transid: TransactionId) = {
        Await.result(WhiskTrigger.get(entityStore, doc.asDocInfo) flatMap { doc =>
            info(this, s"deleting ${doc.docinfo}")
            WhiskAction.del(entityStore, doc.docinfo)
        }, dbOpTimeout)
    }

    def deleteRule(doc: DocId)(implicit transid: TransactionId) = {
        Await.result(WhiskRule.get(entityStore, doc.asDocInfo) flatMap { doc =>
            info(this, s"deleting ${doc.docinfo}")
            WhiskRule.del(entityStore, doc.docinfo)
        }, dbOpTimeout)
    }

    def deleteAuth(doc: DocId)(implicit transid: TransactionId) = {
        Await.result(WhiskAuth.get(authStore, doc.asDocInfo) flatMap { doc =>
            info(this, s"deleting ${doc.docinfo}")
            WhiskAuth.del(authStore, doc.docinfo)
        }, dbOpTimeout)
    }

    def deletePackage(doc: DocId)(implicit transid: TransactionId) = {
        Await.result(WhiskPackage.get(entityStore, doc.asDocInfo) flatMap { doc =>
            info(this, s"deleting ${doc.docinfo}")
            WhiskPackage.del(entityStore, doc.docinfo)
        }, dbOpTimeout)
    }

    object MakeName {
        @volatile var counter = 1
        def next(prefix: String = "test")(): EntityName = {
            counter = counter + 1
            EntityName(s"${prefix}_name$counter")
        }
    }

    setVerbosity(InfoLevel)
    Collection.initialize(entityStore, InfoLevel)
    entityStore.setVerbosity(InfoLevel)
    activationStore.setVerbosity(InfoLevel)
    authStore.setVerbosity(InfoLevel)
    entitlementService.setVerbosity(InfoLevel)

    val ACTIONS = Collection(Collection.ACTIONS)
    val TRIGGERS = Collection(Collection.TRIGGERS)
    val RULES = Collection(Collection.RULES)
    val ACTIVATIONS = Collection(Collection.ACTIVATIONS)
    val NAMESPACES = Collection(Collection.NAMESPACES)
    val PACKAGES = Collection(Collection.PACKAGES)

    after {
        cleanup()
    }

    override def afterAll() {
        println("Shutting down cloudant connections");
        entityStore.shutdown()
        activationStore.shutdown()
        authStore.shutdown()
    }
}
