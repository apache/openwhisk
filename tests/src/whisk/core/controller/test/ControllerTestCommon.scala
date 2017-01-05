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

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration.{ DurationInt, FiniteDuration }
import scala.language.postfixOps

import org.scalatest.BeforeAndAfter
import org.scalatest.BeforeAndAfterAll
import org.scalatest.FlatSpec
import org.scalatest.Matchers

import akka.event.Logging.{ InfoLevel, LogLevel }
import spray.http.BasicHttpCredentials
import spray.json.DefaultJsonProtocol
import spray.json.JsString
import spray.routing.HttpService
import spray.testkit.ScalatestRouteTest
import whisk.common.{ Logging, TransactionCounter, TransactionId }
import whisk.core.WhiskConfig
import whisk.core.connector.ActivationMessage
import whisk.core.controller.WhiskActionsApi
import whisk.core.controller.WhiskServices
import whisk.core.database.DocumentFactory
import whisk.core.database.test.DbUtils
import whisk.core.entitlement._
import whisk.core.entity._
import whisk.core.iam.NamespaceProvider
import whisk.core.loadBalancer.LoadBalancer

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

    override val whiskConfig = new WhiskConfig(WhiskAuthStore.requiredProperties ++ WhiskActionsApi.requiredProperties)
    assert(whiskConfig.isValid)

    override val loadBalancer = new DegenerateLoadBalancerService(whiskConfig, InfoLevel)

    override val iam = new NamespaceProvider(whiskConfig, forceLocal = true)
    override val entitlementProvider: EntitlementProvider = new LocalEntitlementProvider(whiskConfig, loadBalancer, iam)

    override val activationIdFactory = new ActivationId.ActivationIdGenerator() {
        // need a static activation id to test activations api
        private val fixedId = ActivationId()
        override def make = fixedId
    }

    override val consulServer = "???"

    val entityStore = WhiskEntityStore.datastore(whiskConfig)
    val activationStore = WhiskActivationStore.datastore(whiskConfig)
    val authStore = WhiskAuthStore.datastore(whiskConfig)
    val authStoreV2 = WhiskAuthV2Store.datastore(whiskConfig)

    def createTempCredentials(implicit transid: TransactionId) = {
        val subject = Subject()
        val key = AuthKey()
        val auth = WhiskAuthV2.withDefaultNamespace(subject, key)
        put(authStoreV2, auth)
        waitOnView(authStore, key, 1)
        (subject.toIdentity(key), BasicHttpCredentials(key.uuid.asString, key.key.asString))
    }

    def deleteAction(doc: DocId)(implicit transid: TransactionId) = {
        Await.result(WhiskAction.get(entityStore, doc) flatMap { doc =>
            info(this, s"deleting ${doc.docinfo}")
            WhiskAction.del(entityStore, doc.docinfo)
        }, dbOpTimeout)
    }

    def deleteActivation(doc: DocId)(implicit transid: TransactionId) = {
        Await.result(WhiskActivation.get(entityStore, doc) flatMap { doc =>
            info(this, s"deleting ${doc.docinfo}")
            WhiskActivation.del(entityStore, doc.docinfo)
        }, dbOpTimeout)
    }

    def deleteTrigger(doc: DocId)(implicit transid: TransactionId) = {
        Await.result(WhiskTrigger.get(entityStore, doc) flatMap { doc =>
            info(this, s"deleting ${doc.docinfo}")
            WhiskAction.del(entityStore, doc.docinfo)
        }, dbOpTimeout)
    }

    def deleteRule(doc: DocId)(implicit transid: TransactionId) = {
        Await.result(WhiskRule.get(entityStore, doc) flatMap { doc =>
            info(this, s"deleting ${doc.docinfo}")
            WhiskRule.del(entityStore, doc.docinfo)
        }, dbOpTimeout)
    }

    def deleteAuth(doc: DocId)(implicit transid: TransactionId) = {
        Await.result(WhiskAuth.get(authStore, doc) flatMap { doc =>
            info(this, s"deleting ${doc.docinfo}")
            WhiskAuth.del(authStore, doc.docinfo)
        }, dbOpTimeout)
    }

    def deletePackage(doc: DocId)(implicit transid: TransactionId) = {
        Await.result(WhiskPackage.get(entityStore, doc) flatMap { doc =>
            info(this, s"deleting ${doc.docinfo}")
            WhiskPackage.del(entityStore, doc.docinfo)
        }, dbOpTimeout)
    }

    def stringToFullyQualifiedName(s: String) = FullyQualifiedEntityName.serdes.read(JsString(s))

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
    entitlementProvider.setVerbosity(InfoLevel)

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

    protected case class BadEntity(
        namespace: EntityPath,
        override val name: EntityName,
        version: SemVer = SemVer(),
        publish: Boolean = false,
        annotations: Parameters = Parameters())
        extends WhiskEntity(name) {
        override def toJson = BadEntity.serdes.write(this).asJsObject
    }

    protected object BadEntity
        extends DocumentFactory[BadEntity]
        with DefaultJsonProtocol {
        implicit val serdes = jsonFormat5(BadEntity.apply)
        override val cacheEnabled = true
        override def cacheKeyForUpdate(w: BadEntity) = w.docid.asDocInfo
    }
}

class DegenerateLoadBalancerService(config: WhiskConfig, verbosity: LogLevel)
    extends LoadBalancer {

    // unit tests that need an activation via active ack/fast path should set this to value expected
    var whiskActivationStub: Option[WhiskActivation] = None

    override def getActiveUserActivationCounts: Map[String, Long] = Map()

    override def publish(action: WhiskAction, msg: ActivationMessage, timeout: FiniteDuration)(implicit transid: TransactionId): (Future[Unit], Future[WhiskActivation]) =
        (Future.successful {},
            whiskActivationStub map {
                activation => Future.successful(activation)
            } getOrElse Future.failed(new IllegalArgumentException("Unit test does not need fast path")))

}
