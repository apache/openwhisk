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

package org.apache.openwhisk.core.entity.test

import java.time.Clock
import java.time.Instant

import scala.concurrent.Await
import scala.language.postfixOps
import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner
import common.StreamLogging
import common.WskActorSystem
import org.apache.openwhisk.core.controller.test.WhiskAuthHelpers
import org.apache.openwhisk.core.database.ArtifactStore
import org.apache.openwhisk.core.database.StaleParameter
import org.apache.openwhisk.core.database.test.DbUtils
import org.apache.openwhisk.core.entity._

@RunWith(classOf[JUnitRunner])
class ViewTests
    extends FlatSpec
    with BeforeAndAfterEach
    with BeforeAndAfterAll
    with Matchers
    with DbUtils
    with ExecHelpers
    with WskActorSystem
    with StreamLogging {

  def aname() = MakeName.next("viewtests")
  def afullname(namespace: EntityPath) = FullyQualifiedEntityName(namespace, aname())

  object MakeName {
    @volatile var counter = 1
    def next(prefix: String = "test")(): EntityName = {
      counter = counter + 1
      EntityName(s"${prefix}_name$counter")
    }
  }

  val creds1 = WhiskAuthHelpers.newAuth(Subject("s12345"))
  val namespace1 = EntityPath(creds1.subject.asString)

  val creds2 = WhiskAuthHelpers.newAuth(Subject("t12345"))
  val namespace2 = EntityPath(creds2.subject.asString)

  val entityStore = WhiskEntityStore.datastore()
  val activationStore = WhiskActivationStore.datastore()

  override def afterEach = {
    cleanup()
  }

  override def afterAll() = {
    println("Shutting down store connections")
    entityStore.shutdown()
    activationStore.shutdown()
    super.afterAll()
  }

  behavior of "Datastore View"

  def getAllActivationsInNamespace[Au <: WhiskEntity](store: ArtifactStore[Au], ns: EntityPath)(
    implicit entities: Seq[WhiskEntity]) = {
    implicit val tid = transid()
    val result =
      Await
        .result(WhiskActivation.listCollectionInNamespace(store, ns, 0, 0, stale = StaleParameter.No), dbOpTimeout)
        .left
        .get
        .map(e => e)
    val expected = entities filter { _.namespace.root.toPath == ns }
    result should have length expected.length
    result should contain theSameElementsAs expected.map(_.summaryAsJson)
  }

  def resolveListMethodForKind(kind: String) = kind match {
    case "actions"     => WhiskAction
    case "packages"    => WhiskPackage
    case "rules"       => WhiskRule
    case "triggers"    => WhiskTrigger
    case "activations" => WhiskActivation
  }

  def getKindInNamespace[Au <: WhiskEntity](store: ArtifactStore[Au],
                                            ns: EntityPath,
                                            kind: String,
                                            f: (WhiskEntity) => Boolean)(implicit entities: Seq[WhiskEntity]) = {
    implicit val tid = transid()
    val q = resolveListMethodForKind(kind)
    val result =
      Await.result(q.listCollectionInNamespace(store, ns, 0, 0, stale = StaleParameter.No).map(_.left.get), dbOpTimeout)
    val expected = entities filter { e =>
      f(e) && e.namespace.root.toPath == ns
    }
    result should have length expected.length
    result should contain theSameElementsAs expected.map(_.summaryAsJson)
  }

  def getKindInNamespaceWithDoc[T](ns: EntityPath, kind: String, f: (WhiskEntity) => Boolean)(
    implicit entities: Seq[WhiskEntity]) = {
    implicit val tid = transid()
    val q = resolveListMethodForKind(kind)
    val result =
      Await.result(
        q.listCollectionInNamespace(entityStore, ns, 0, 0, includeDocs = true, stale = StaleParameter.No)
          .map(_.right.get),
        dbOpTimeout)
    val expected = entities filter { e =>
      f(e) && e.namespace.root.toPath == ns
    }
    result should have length expected.length
    result should contain theSameElementsAs expected
  }

  def getKindInNamespaceByName[Au <: WhiskEntity](store: ArtifactStore[Au],
                                                  ns: EntityPath,
                                                  kind: String,
                                                  name: EntityName,
                                                  f: (WhiskEntity) => Boolean)(implicit entities: Seq[WhiskEntity]) = {
    require(kind == "actions") // currently only actions are permitted in packages
    implicit val tid = transid()
    val q = resolveListMethodForKind(kind)
    val result =
      Await.result(
        q.listCollectionInNamespace(store, ns.addPath(name), 0, 0, stale = StaleParameter.No).map(_.left.get),
        dbOpTimeout)
    val expected = entities filter { e =>
      f(e) && e.namespace.root.toPath == ns
    }
    result should have length expected.length
    result should contain theSameElementsAs expected.map(_.summaryAsJson)
  }

  def getActivationsInNamespaceByName(store: ArtifactStore[WhiskActivation],
                                      ns: EntityPath,
                                      name: EntityName,
                                      f: (WhiskEntity) => Boolean)(implicit entities: Seq[WhiskEntity]) = {
    implicit val tid = transid()
    val result =
      Await.result(
        WhiskActivation
          .listActivationsMatchingName(store, ns, name.toPath, 0, 0, stale = StaleParameter.No)
          .map(_.left.get),
        dbOpTimeout)
    val expected = entities filter { e =>
      f(e) && e.namespace.root.toPath == ns
    }
    result should have length expected.length
    result should contain theSameElementsAs expected.map(_.summaryAsJson)
  }

  def getKindInPackage(ns: EntityPath, kind: String, f: (WhiskEntity) => Boolean)(
    implicit entities: Seq[WhiskEntity]) = {
    implicit val tid = transid()
    val q = resolveListMethodForKind(kind)
    val result = Await.result(
      q.listCollectionInNamespace(entityStore, ns, 0, 0, stale = StaleParameter.No).map(_.left.get),
      dbOpTimeout)
    val expected = entities filter { e =>
      f(e) && e.namespace == ns
    }
    result should have length expected.length
    result should contain theSameElementsAs expected.map(_.summaryAsJson)
  }

  def getActivationsInNamespaceByNameSortedByDate(store: ArtifactStore[WhiskActivation],
                                                  ns: EntityPath,
                                                  kind: String,
                                                  name: EntityName,
                                                  skip: Int,
                                                  count: Int,
                                                  start: Option[Instant],
                                                  end: Option[Instant],
                                                  f: (WhiskEntity) => Boolean)(implicit entities: Seq[WhiskEntity]) = {
    implicit val tid = transid()
    val result = Await.result(
      WhiskActivation
        .listActivationsMatchingName(store, ns, name.toPath, skip, count, false, start, end, StaleParameter.No)
        .map(_.left.get),
      dbOpTimeout)
    val expected = entities filter { e =>
      f(e) && e.namespace.root.toPath == ns
    } sortBy { case (e: WhiskActivation) => e.start.toEpochMilli; case _ => 0 } map { _.summaryAsJson }
    result should have length expected.length
    result should be(expected reverse)
  }

  it should "query whisk view by namespace, collection and entity name in whisk actions db" in {
    implicit val tid = transid()
    val exec = bb("image")
    val pkgname1 = namespace1.addPath(aname())
    val pkgname2 = namespace2.addPath(aname())
    val actionName = aname()
    def now = Instant.now(Clock.systemUTC())

    implicit val entities = Seq(
      WhiskAction(namespace1, aname(), exec),
      WhiskAction(namespace1, aname(), exec),
      WhiskAction(namespace1.addPath(aname()), aname(), exec),
      WhiskAction(namespace1.addPath(aname()), aname(), exec),
      WhiskAction(pkgname1, aname(), exec),
      WhiskAction(pkgname1, aname(), exec),
      WhiskAction(pkgname1, actionName, exec),
      WhiskTrigger(namespace1, aname()),
      WhiskTrigger(namespace1, aname()),
      WhiskRule(namespace1, aname(), trigger = afullname(namespace1), action = afullname(namespace1)),
      WhiskRule(namespace1, aname(), trigger = afullname(namespace1), action = afullname(namespace1)),
      WhiskPackage(namespace1, aname()),
      WhiskPackage(namespace1, aname()),
      WhiskPackage(namespace1, aname(), Some(Binding(namespace2.root, aname()))),
      WhiskPackage(namespace1, aname(), Some(Binding(namespace2.root, aname()))),
      WhiskAction(namespace2, aname(), exec),
      WhiskAction(namespace2, aname(), exec),
      WhiskAction(namespace2.addPath(aname()), aname(), exec),
      WhiskAction(namespace2.addPath(aname()), aname(), exec),
      WhiskAction(pkgname2, aname(), exec),
      WhiskAction(pkgname2, aname(), exec),
      WhiskTrigger(namespace2, aname()),
      WhiskTrigger(namespace2, aname()),
      WhiskRule(namespace2, aname(), trigger = afullname(namespace2), action = afullname(namespace2)),
      WhiskRule(namespace2, aname(), trigger = afullname(namespace2), action = afullname(namespace2)),
      WhiskPackage(namespace2, aname()),
      WhiskPackage(namespace2, aname()),
      WhiskPackage(namespace2, aname(), Some(Binding(namespace1.root, aname()))),
      WhiskPackage(namespace2, aname(), Some(Binding(namespace1.root, aname()))))

    entities foreach { put(entityStore, _) }
    waitOnView(entityStore, namespace1.root, 7, WhiskAction.view)
    waitOnView(entityStore, namespace1.root, 2, WhiskTrigger.view)
    waitOnView(entityStore, namespace1.root, 2, WhiskRule.view)
    waitOnView(entityStore, namespace1.root, 4, WhiskPackage.view)
    waitOnView(entityStore, namespace2.root, 6, WhiskAction.view)
    waitOnView(entityStore, namespace2.root, 2, WhiskTrigger.view)
    waitOnView(entityStore, namespace2.root, 2, WhiskRule.view)
    waitOnView(entityStore, namespace2.root, 4, WhiskPackage.view)

    getKindInNamespace(entityStore, namespace1, "actions", {
      case (e: WhiskAction) => true
      case (_)              => false
    })
    getKindInNamespace(entityStore, namespace1, "triggers", {
      case (e: WhiskTrigger) => true
      case (_)               => false
    })
    getKindInNamespaceWithDoc[WhiskRule](namespace1, "rules", {
      case (e: WhiskRule) => true
      case (_)            => false
    })
    getKindInNamespace(entityStore, namespace1, "packages", {
      case (e: WhiskPackage) => true
      case (_)               => false
    })
    getKindInPackage(pkgname1, "actions", {
      case (e: WhiskAction) => true
      case (_)              => false
    })
    getKindInNamespaceByName(entityStore, pkgname1, "actions", actionName, {
      case (e: WhiskAction) => (e.name == actionName)
      case (_)              => false
    })

    getKindInNamespace(entityStore, namespace2, "actions", {
      case (e: WhiskAction) => true
      case (_)              => false
    })
    getKindInNamespace(entityStore, namespace2, "triggers", {
      case (e: WhiskTrigger) => true
      case (_)               => false
    })
    getKindInNamespaceWithDoc[WhiskRule](namespace2, "rules", {
      case (e: WhiskRule) => true
      case (_)            => false
    })
    getKindInNamespace(entityStore, namespace2, "packages", {
      case (e: WhiskPackage) => true
      case (_)               => false
    })
    getKindInPackage(pkgname2, "actions", {
      case (e: WhiskAction) => true
      case (_)              => false
    })
  }

  it should "query whisk view by namespace, collection and entity name in whisk activations db" in {
    implicit val tid = transid()
    val actionName = aname()
    def now = Instant.now(Clock.systemUTC())

    // creates 5 entities in each namespace as follows:
    // - some activations in each namespace (some may have prescribed action name to query by name)
    implicit val entities = Seq(
      WhiskActivation(namespace1, aname(), Subject(), ActivationId.generate(), start = now, end = now),
      WhiskActivation(namespace1, aname(), Subject(), ActivationId.generate(), start = now, end = now),
      WhiskActivation(namespace2, aname(), Subject(), ActivationId.generate(), start = now, end = now),
      WhiskActivation(namespace2, actionName, Subject(), ActivationId.generate(), start = now, end = now),
      WhiskActivation(namespace2, actionName, Subject(), ActivationId.generate(), start = now, end = now))

    entities foreach { put(activationStore, _) }
    waitOnView(activationStore, namespace1.root, 2, WhiskActivation.view)
    waitOnView(activationStore, namespace2.root, 3, WhiskActivation.view)

    getAllActivationsInNamespace(activationStore, namespace1)
    getKindInNamespace(activationStore, namespace1, "activations", {
      case (e: WhiskActivation) => true
      case (_)                  => false
    })

    getAllActivationsInNamespace(activationStore, namespace2)
    getKindInNamespace(activationStore, namespace2, "activations", {
      case (e: WhiskActivation) => true
      case (_)                  => false
    })
    getActivationsInNamespaceByName(activationStore, namespace2, actionName, {
      case (e: WhiskActivation) => (e.name == actionName)
      case (_)                  => false
    })
  }

  it should "query whisk view for activations sorted by date" in {
    implicit val tid = transid()
    val actionName = aname()
    val now = Instant.now(Clock.systemUTC())
    implicit val entities = Seq(
      WhiskActivation(namespace1, actionName, Subject(), ActivationId.generate(), start = now, end = now),
      WhiskActivation(
        namespace1,
        actionName,
        Subject(),
        ActivationId.generate(),
        start = now.plusSeconds(20),
        end = now.plusSeconds(20)),
      WhiskActivation(
        namespace1,
        actionName,
        Subject(),
        ActivationId.generate(),
        start = now.plusSeconds(10),
        end = now.plusSeconds(20)),
      WhiskActivation(
        namespace1,
        actionName,
        Subject(),
        ActivationId.generate(),
        start = now.plusSeconds(40),
        end = now.plusSeconds(20)),
      WhiskActivation(
        namespace1,
        actionName,
        Subject(),
        ActivationId.generate(),
        start = now.plusSeconds(30),
        end = now.plusSeconds(20)))

    entities foreach { put(activationStore, _) }
    waitOnView(activationStore, namespace1.addPath(actionName), entities.length, WhiskActivation.filtersView)

    getActivationsInNamespaceByNameSortedByDate(
      activationStore,
      namespace1,
      "activations",
      actionName,
      0,
      5,
      None,
      None, {
        case (e: WhiskActivation) => e.name == actionName
        case (_)                  => false
      })

    val since = entities(1).start
    val upto = entities(4).start
    getActivationsInNamespaceByNameSortedByDate(
      activationStore,
      namespace1,
      "activations",
      actionName,
      0,
      5,
      Some(since),
      Some(upto), {
        case (e: WhiskActivation) =>
          e.name == actionName && (e.start.equals(since) || e.start
            .equals(upto) || (e.start.isAfter(since) && e.start.isBefore(upto)))
        case (_) => false
      })
  }

  it should "list actions and retrieve full documents" in {
    implicit val tid = transid()
    val actionName = aname()
    val now = Instant.now(Clock.systemUTC())
    implicit val entities =
      Seq(WhiskAction(namespace1, aname(), jsDefault("??")), WhiskAction(namespace1, aname(), jsDefault("??")))

    entities foreach { put(entityStore, _) }
    waitOnView(entityStore, namespace1.root, 2, WhiskAction.view)

    getKindInNamespaceWithDoc[WhiskAction](namespace1, "actions", {
      case (e: WhiskAction) => true
      case (_)              => false
    })
  }
}
