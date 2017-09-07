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

package whisk.core.controller.test

import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.language.postfixOps
import org.scalatest.BeforeAndAfter
import org.scalatest.BeforeAndAfterAll
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import common.StreamLogging
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.http.scaladsl.testkit.RouteTestTimeout
import spray.json.DefaultJsonProtocol
import spray.json.JsString
import whisk.common.TransactionCounter
import whisk.common.TransactionId
import whisk.core.WhiskConfig
import whisk.core.connector.ActivationMessage
import whisk.core.containerpool.logging.LogStoreProvider
import whisk.core.controller.RestApiCommons
import whisk.core.controller.WhiskServices
import whisk.core.database.DocumentFactory
import whisk.core.database.CacheChangeNotification
import whisk.core.database.test.DbUtils
import whisk.core.entitlement._
import whisk.core.entity._
import whisk.core.entity.test.ExecHelpers
import whisk.core.loadBalancer.LoadBalancer
import whisk.spi.SpiLoader

protected trait ControllerTestCommon
    extends FlatSpec
    with BeforeAndAfter
    with BeforeAndAfterAll
    with ScalatestRouteTest
    with Matchers
    with TransactionCounter
    with DbUtils
    with ExecHelpers
    with WhiskServices
    with StreamLogging {

  override val instanceOrdinal = 0
  override val instance = InstanceId(instanceOrdinal)
  override val numberOfInstances = 1
  val activeAckTopicIndex = InstanceId(instanceOrdinal)

  implicit val routeTestTimeout = RouteTestTimeout(90 seconds)

  override implicit val actorSystem = system // defined in ScalatestRouteTest
  override val executionContext = actorSystem.dispatcher

  override val whiskConfig = new WhiskConfig(RestApiCommons.requiredProperties)
  assert(whiskConfig.isValid)

  // initialize runtimes manifest
  ExecManifest.initialize(whiskConfig)

  override val loadBalancer = new DegenerateLoadBalancerService(whiskConfig)

  override lazy val entitlementProvider: EntitlementProvider = new LocalEntitlementProvider(whiskConfig, loadBalancer)

  override val activationIdFactory = new ActivationId.ActivationIdGenerator() {
    // need a static activation id to test activations api
    private val fixedId = ActivationId()
    override def make = fixedId
  }

  implicit val cacheChangeNotification = Some {
    new CacheChangeNotification {
      override def apply(k: CacheKey): Future[Unit] = Future.successful(())
    }
  }

  val entityStore = WhiskEntityStore.datastore(whiskConfig)
  val activationStore = WhiskActivationStore.datastore(whiskConfig)
  val authStore = WhiskAuthStore.datastore(whiskConfig)
  val logStore = SpiLoader.get[LogStoreProvider].logStore(actorSystem)

  def deleteAction(doc: DocId)(implicit transid: TransactionId) = {
    Await.result(WhiskAction.get(entityStore, doc) flatMap { doc =>
      logging.info(this, s"deleting ${doc.docinfo}")
      WhiskAction.del(entityStore, doc.docinfo)
    }, dbOpTimeout)
  }

  def deleteActivation(doc: DocId)(implicit transid: TransactionId) = {
    Await.result(WhiskActivation.get(activationStore, doc) flatMap { doc =>
      logging.info(this, s"deleting ${doc.docinfo}")
      WhiskActivation.del(activationStore, doc.docinfo)
    }, dbOpTimeout)
  }

  def deleteTrigger(doc: DocId)(implicit transid: TransactionId) = {
    Await.result(WhiskTrigger.get(entityStore, doc) flatMap { doc =>
      logging.info(this, s"deleting ${doc.docinfo}")
      WhiskAction.del(entityStore, doc.docinfo)
    }, dbOpTimeout)
  }

  def deleteRule(doc: DocId)(implicit transid: TransactionId) = {
    Await.result(WhiskRule.get(entityStore, doc) flatMap { doc =>
      logging.info(this, s"deleting ${doc.docinfo}")
      WhiskRule.del(entityStore, doc.docinfo)
    }, dbOpTimeout)
  }

  def deletePackage(doc: DocId)(implicit transid: TransactionId) = {
    Await.result(WhiskPackage.get(entityStore, doc) flatMap { doc =>
      logging.info(this, s"deleting ${doc.docinfo}")
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

  Collection.initialize(entityStore)

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
    println("Shutting down db connections");
    entityStore.shutdown()
    activationStore.shutdown()
    authStore.shutdown()
  }

  protected case class BadEntity(namespace: EntityPath,
                                 override val name: EntityName,
                                 version: SemVer = SemVer(),
                                 publish: Boolean = false,
                                 annotations: Parameters = Parameters())
      extends WhiskEntity(name) {
    override def toJson = BadEntity.serdes.write(this).asJsObject
  }

  protected object BadEntity extends DocumentFactory[BadEntity] with DefaultJsonProtocol {
    implicit val serdes = jsonFormat5(BadEntity.apply)
    override val cacheEnabled = true
  }
}

class DegenerateLoadBalancerService(config: WhiskConfig)(implicit ec: ExecutionContext) extends LoadBalancer {
  import scala.concurrent.blocking

  // unit tests that need an activation via active ack/fast path should set this to value expected
  var whiskActivationStub: Option[(FiniteDuration, WhiskActivation)] = None

  override def totalActiveActivations = Future.successful(0)
  override def activeActivationsFor(namespace: UUID) = Future.successful(0)

  override def publish(action: ExecutableWhiskActionMetaData, msg: ActivationMessage)(
    implicit transid: TransactionId): Future[Future[Either[ActivationId, WhiskActivation]]] =
    Future.successful {
      whiskActivationStub map {
        case (timeout, activation) =>
          Future {
            blocking {
              println(s"load balancer active ack stub: waiting for $timeout...")
              Thread.sleep(timeout.toMillis)
              println(".... done waiting")
            }
            Right(activation)
          }
      } getOrElse Future.failed(new IllegalArgumentException("Unit test does not need fast path"))
    }

}
