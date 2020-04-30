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

package org.apache.openwhisk.core.controller.test

import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}
import scala.language.postfixOps
import org.scalatest.BeforeAndAfterEach
import org.scalatest.BeforeAndAfterAll
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import common.StreamLogging
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.http.scaladsl.testkit.RouteTestTimeout
import spray.json._
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.{FeatureFlags, WhiskConfig}
import org.apache.openwhisk.core.connector.ActivationMessage
import org.apache.openwhisk.core.containerpool.logging.LogStoreProvider
import org.apache.openwhisk.core.controller.{CustomHeaders, RestApiCommons, WhiskServices}
import org.apache.openwhisk.core.database.{ActivationStoreProvider, CacheChangeNotification, DocumentFactory}
import org.apache.openwhisk.core.database.test.DbUtils
import org.apache.openwhisk.core.entitlement._
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.entity.test.ExecHelpers
import org.apache.openwhisk.core.loadBalancer.LoadBalancer
import org.apache.openwhisk.spi.SpiLoader
import org.apache.openwhisk.core.database.UserContext

protected trait ControllerTestCommon
    extends FlatSpec
    with BeforeAndAfterEach
    with BeforeAndAfterAll
    with ScalatestRouteTest
    with Matchers
    with DbUtils
    with ExecHelpers
    with WhiskServices
    with StreamLogging
    with CustomHeaders {

  val activeAckTopicIndex = ControllerInstanceId("0")

  implicit val routeTestTimeout = RouteTestTimeout(90 seconds)

  override implicit val actorSystem = system // defined in ScalatestRouteTest
  override val executionContext = actorSystem.dispatcher

  override val whiskConfig = new WhiskConfig(RestApiCommons.requiredProperties ++ WhiskConfig.kafkaHosts)
  assert(whiskConfig.isValid)

  // initialize runtimes manifest
  ExecManifest.initialize(whiskConfig)

  override val loadBalancer = new DegenerateLoadBalancerService(whiskConfig)

  override lazy val entitlementProvider: EntitlementProvider =
    new LocalEntitlementProvider(whiskConfig, loadBalancer, instance)

  override val activationIdFactory = new ActivationId.ActivationIdGenerator {
    // need a static activation id to test activations api
    private val fixedId = ActivationId.generate()
    override def make = fixedId
  }

  implicit val cacheChangeNotification = Some {
    new CacheChangeNotification {
      override def apply(k: CacheKey): Future[Unit] = Future.successful(())
    }
  }

  def checkWhiskEntityResponse(response: WhiskEntity, expected: WhiskEntity): Unit = {
    // Used to ignore `updated` field because timestamp is not known before inserting into the DB
    // If you use this method, test case that checks timestamp must be added
    val r = response match {
      case whiskAction: WhiskAction                 => whiskAction.copy(updated = expected.updated)
      case whiskActionMetaData: WhiskActionMetaData => whiskActionMetaData.copy(updated = expected.updated)
      case whiskTrigger: WhiskTrigger               => whiskTrigger.copy(updated = expected.updated)
      case whiskPackage: WhiskPackage               => whiskPackage.copy(updated = expected.updated)
    }
    r should be(expected)
  }

  def systemAnnotations(kind: String, create: Boolean = true): Parameters = {
    val base = if (create && FeatureFlags.requireApiKeyAnnotation) {
      Parameters(Annotations.ProvideApiKeyAnnotationName, JsFalse)
    } else {
      Parameters()
    }
    base ++ Parameters(WhiskAction.execFieldName, kind)
  }

  val entityStore = WhiskEntityStore.datastore()
  val authStore = WhiskAuthStore.datastore()
  val logStore = SpiLoader.get[LogStoreProvider].instance(actorSystem)
  val activationStore = SpiLoader.get[ActivationStoreProvider].instance(actorSystem, materializer, logging)

  def deleteAction(doc: DocId)(implicit transid: TransactionId) = {
    Await.result(WhiskAction.get(entityStore, doc) flatMap { doc =>
      logging.debug(this, s"deleting ${doc.docinfo}")
      WhiskAction.del(entityStore, doc.docinfo)
    }, dbOpTimeout)
  }

  def getActivation(activationId: ActivationId, context: UserContext)(
    implicit transid: TransactionId,
    timeout: Duration = 10 seconds): WhiskActivation = {
    Await.result(activationStore.get(activationId, context), timeout)
  }

  def storeActivation(
    activation: WhiskActivation,
    isBlockingActivation: Boolean,
    disableStore: Boolean,
    context: UserContext)(implicit transid: TransactionId, timeout: Duration = 10 seconds): DocInfo = {
    val docFuture = activationStore.storeAfterCheck(activation, isBlockingActivation, Some(disableStore), context)(
      transid,
      notifier = None,
      logging)
    val doc = Await.result(docFuture, timeout)
    assert(doc != null)
    doc
  }

  def deleteActivation(activationId: ActivationId, context: UserContext)(implicit transid: TransactionId) = {
    val res = Await.result(activationStore.delete(activationId, context), dbOpTimeout)
    assert(res, true)
    res
  }

  def waitOnListActivationsInNamespace(namespace: EntityPath, count: Int, context: UserContext)(
    implicit ec: ExecutionContext,
    transid: TransactionId,
    timeout: Duration) = {
    val success = retry(
      () => {
        val activations: Future[Either[List[JsObject], List[WhiskActivation]]] =
          activationStore.listActivationsInNamespace(namespace, 0, 0, context = context)
        val listFuture: Future[List[JsObject]] = activations map (_.fold(
          (js) => js,
          (wa) => wa.map(_.toExtendedJson())))

        listFuture map { l =>
          if (l.length != count) {
            throw RetryOp()
          } else true
        }
      },
      timeout)

    assert(success.isSuccess, "wait aborted")
  }

  def waitOnListActivationsMatchingName(namespace: EntityPath, name: EntityPath, count: Int, context: UserContext)(
    implicit ex: ExecutionContext,
    transid: TransactionId,
    timeout: Duration) = {
    val success = retry(
      () => {
        val activations: Future[Either[List[JsObject], List[WhiskActivation]]] =
          activationStore.listActivationsMatchingName(namespace, name, 0, 0, context = context)
        val listFuture: Future[List[JsObject]] = activations map (_.fold(
          (js) => js,
          (wa) => wa.map(_.toExtendedJson())))

        listFuture map { l =>
          if (l.length != count) {
            throw RetryOp()
          } else true
        }
      },
      timeout)

    assert(success.isSuccess, "wait aborted")
  }

  def deleteTrigger(doc: DocId)(implicit transid: TransactionId) = {
    Await.result(WhiskTrigger.get(entityStore, doc) flatMap { doc =>
      logging.debug(this, s"deleting ${doc.docinfo}")
      WhiskAction.del(entityStore, doc.docinfo)
    }, dbOpTimeout)
  }

  def deleteRule(doc: DocId)(implicit transid: TransactionId) = {
    Await.result(WhiskRule.get(entityStore, doc) flatMap { doc =>
      logging.debug(this, s"deleting ${doc.docinfo}")
      WhiskRule.del(entityStore, doc.docinfo)
    }, dbOpTimeout)
  }

  def deletePackage(doc: DocId)(implicit transid: TransactionId) = {
    Await.result(WhiskPackage.get(entityStore, doc) flatMap { doc =>
      logging.debug(this, s"deleting ${doc.docinfo}")
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

  override def afterEach() = {
    cleanup()
  }

  override def afterAll() = {
    println("Shutting down db connections");
    entityStore.shutdown()
    authStore.shutdown()
  }

  protected case class BadEntity(namespace: EntityPath,
                                 override val name: EntityName,
                                 version: SemVer = SemVer(),
                                 publish: Boolean = false,
                                 annotations: Parameters = Parameters())
      extends WhiskEntity(name, "badEntity") {

    override def toJson = BadEntity.serdes.write(this).asJsObject
  }

  protected object BadEntity extends DocumentFactory[BadEntity] with DefaultJsonProtocol {
    implicit val serdes = jsonFormat5(BadEntity.apply)
    override val cacheEnabled = true
  }

  /**
   * Makes a simple sequence action and installs it in the db (no call to wsk api/cli).
   * All actions are in the default package.
   *
   * @param sequenceName the name of the sequence
   * @param ns           the namespace to be used when creating the component actions and the sequence action
   * @param components   the names of the actions (entity names, no namespace)
   */
  protected def putSimpleSequenceInDB(sequenceName: String, ns: EntityPath, components: Vector[String])(
    implicit tid: TransactionId) = {
    val seqAction = makeSimpleSequence(sequenceName, ns, components)
    put(entityStore, seqAction)
  }

  /**
   * Returns a WhiskAction that can be used to create/update a sequence.
   * If instructed to do so, installs the component actions in the db.
   * All actions are in the default package.
   *
   * @param sequenceName   the name of the sequence
   * @param ns             the namespace to be used when creating the component actions and the sequence action
   * @param componentNames the names of the actions (entity names, no namespace)
   * @param installDB      if true, installs the component actions in the db (default true)
   */
  protected def makeSimpleSequence(sequenceName: String,
                                   ns: EntityPath,
                                   componentNames: Vector[String],
                                   installDB: Boolean = true)(implicit tid: TransactionId): WhiskAction = {
    if (installDB) {
      // create bogus wsk actions
      val wskActions = componentNames.toSet[String] map { c =>
        WhiskAction(ns, EntityName(c), jsDefault("??"))
      }
      // add them to the db
      wskActions.foreach {
        put(entityStore, _)
      }
    }
    // add namespace to component names
    val components = componentNames map { c =>
      stringToFullyQualifiedName(s"/$ns/$c")
    }
    // create wsk action for the sequence
    WhiskAction(ns, EntityName(sequenceName), sequence(components))
  }
}

class DegenerateLoadBalancerService(config: WhiskConfig)(implicit ec: ExecutionContext) extends LoadBalancer {
  import scala.concurrent.blocking

  // unit tests that need an activation via active ack/fast path should set this to value expected
  var whiskActivationStub: Option[(FiniteDuration, Either[ActivationId, WhiskActivation])] = None
  var activationMessageChecker: Option[ActivationMessage => Unit] = None

  override def totalActiveActivations = Future.successful(0)
  override def activeActivationsFor(namespace: UUID) = Future.successful(0)

  override def publish(action: ExecutableWhiskActionMetaData, msg: ActivationMessage)(
    implicit transid: TransactionId): Future[Future[Either[ActivationId, WhiskActivation]]] = {
    activationMessageChecker.foreach(_(msg))

    Future.successful {
      whiskActivationStub map {
        case (timeout, activation) =>
          Future {
            blocking {
              println(s"load balancer active ack stub: waiting for $timeout...")
              Thread.sleep(timeout.toMillis)
              println(".... done waiting")
            }
            activation
          }
      } getOrElse Future.failed(new IllegalArgumentException("Unit test does not need fast path"))
    }
  }

  override def invokerHealth() = Future.successful(IndexedSeq.empty)
}
