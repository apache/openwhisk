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

package whisk.core.entity.test

import java.time.Clock
import java.time.Instant
import scala.concurrent.Await
import scala.util.Try
import akka.actor.ActorSystem
import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfter
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner
import spray.json.JsObject
import whisk.core.WhiskConfig
import whisk.core.database.test.DbUtils
import whisk.core.entity.ActionLimits
import whisk.core.entity.ActivationId
import whisk.core.entity.AuthKey
import whisk.core.entity.Binding
import whisk.core.entity.EntityName
import whisk.core.entity.Exec
import whisk.core.entity.Namespace
import whisk.core.entity.Parameters
import whisk.core.entity.SemVer
import whisk.core.entity.Status
import whisk.core.entity.Subject
import whisk.core.entity.TriggerLimits
import whisk.core.entity.WhiskAction
import whisk.core.entity.WhiskActivation
import whisk.core.entity.WhiskAuth
import whisk.core.entity.WhiskEntity
import whisk.core.entity.WhiskEntityQueries.listAllInNamespace
import whisk.core.entity.WhiskEntityQueries.listEntitiesInNamespace
import whisk.core.entity.WhiskEntityQueries.listCollectionInAnyNamespace
import whisk.core.entity.WhiskEntityQueries.listCollectionByName
import whisk.core.entity.WhiskEntityQueries.listCollectionInNamespace
import whisk.core.entity.WhiskEntityStore
import whisk.core.entity.WhiskPackage
import whisk.core.entity.WhiskRule
import whisk.core.entity.WhiskTrigger
import java.util.Date
import org.scalatest.BeforeAndAfterAll
import scala.language.postfixOps
import akka.event.Logging.InfoLevel

import common.WskActorSystem

@RunWith(classOf[JUnitRunner])
class ViewTests extends FlatSpec
    with BeforeAndAfter
    with BeforeAndAfterAll
    with Matchers
    with DbUtils
    with WskActorSystem {

    def aname = MakeName.next("viewtests")

    object MakeName {
        @volatile var counter = 1
        def next(prefix: String = "test")(): EntityName = {
            counter = counter + 1
            EntityName(s"${prefix}_name$counter")
        }
    }

    val creds1 = WhiskAuth(Subject("s12345"), AuthKey())
    val namespace1 = Namespace(creds1.subject())

    val creds2 = WhiskAuth(Subject("t12345"), AuthKey())
    val namespace2 = Namespace(creds2.subject())

    val config = new WhiskConfig(WhiskEntityStore.requiredProperties)
    val datastore = WhiskEntityStore.datastore(config)
    datastore.setVerbosity(InfoLevel)

    after {
        cleanup()
    }

    override def afterAll() {
        println("Shutting down store connections")
        datastore.shutdown()
        super.afterAll()
    }

    behavior of "Datastore View"

    def getAllInNamespace(ns: Namespace)(implicit entities: Seq[WhiskEntity]) = {
        implicit val tid = transid()
        val result = Await.result(listAllInNamespace(datastore, ns, false), dbOpTimeout).values.toList flatMap { t => t }
        val expected = entities filter { _.namespace.root == ns }
        result.length should be(expected.length)
        expected forall { e => result contains e.summaryAsJson } should be(true)
    }

    def getEntitiesInNamespace(ns: Namespace)(implicit entities: Seq[WhiskEntity]) = {
        implicit val tid = transid()
        val map = Await.result(listEntitiesInNamespace(datastore, ns, false), dbOpTimeout)
        val result = map.values.toList flatMap { t => t }
        val expected = entities filter { !_.isInstanceOf[WhiskActivation] } filter { _.namespace.root == ns }
        map.get(WhiskActivation.collectionName) should be(None)
        result.length should be(expected.length)
        expected forall { e => result contains e.summaryAsJson } should be(true)
    }

    def getKindInNamespace(ns: Namespace, kind: String, f: (WhiskEntity) => Boolean)(implicit entities: Seq[WhiskEntity]) = {
        implicit val tid = transid()
        val result = Await.result(listCollectionInNamespace(datastore, kind, ns, 0, 0, convert = None) map { _.left.get map { e => e } }, dbOpTimeout)
        val expected = entities filter { e => f(e) && e.namespace.root == ns }
        result.length should be(expected.length)
        expected forall { e => result contains e.summaryAsJson } should be(true)
    }

    def getPublicPackages(implicit entities: Seq[WhiskEntity]) = {
        implicit val tid = transid()
        val result = Await.result(listCollectionInAnyNamespace(datastore, "packages", 0, 0, true, convert = None) map { _.left.get map { e => e } }, dbOpTimeout)
        val expected = entities filter { case (e: WhiskPackage) => e.publish && e.binding.isEmpty case _ => false }
        result.length should be >= (expected.length)
        expected forall { e => result contains e.summaryAsJson } should be(true)
    }

    def getKindInNamespaceWithDoc[T](ns: Namespace, kind: String, f: (WhiskEntity) => Boolean, convert: Option[JsObject => Try[T]])(implicit entities: Seq[WhiskEntity]) = {
        implicit val tid = transid()
        val result = Await.result(listCollectionInNamespace(datastore, kind, ns, 0, 0, convert = convert) map { _.right.get }, dbOpTimeout)
        val expected = entities filter { e => f(e) && e.namespace.root == ns }
        result.length should be(expected.length)
        expected forall { e => result contains e } should be(true)
    }

    def getKindInNamespaceByName(ns: Namespace, kind: String, name: EntityName, f: (WhiskEntity) => Boolean)(implicit entities: Seq[WhiskEntity]) = {
        implicit val tid = transid()
        val result = Await.result(listCollectionByName(datastore, kind, ns, name, 0, 0, convert = None) map { _.left.get map { e => e } }, dbOpTimeout)
        val expected = entities filter { e => f(e) && e.namespace.root == ns }
        result.length should be(expected.length)
        expected forall { e => result contains e.summaryAsJson } should be(true)
    }

    def getKindInPackage(ns: Namespace, kind: String, f: (WhiskEntity) => Boolean)(implicit entities: Seq[WhiskEntity]) = {
        implicit val tid = transid()
        val result = Await.result(listCollectionInNamespace(datastore, kind, ns, 0, 0, convert = None) map { _.left.get map { e => e } }, dbOpTimeout)
        val expected = entities filter { e => f(e) && e.namespace == ns }
        result.length should be(expected.length)
        expected forall { e => result contains e.summaryAsJson } should be(true)
    }

    def getKindInNamespaceByNameSortedByDate(
        ns: Namespace, kind: String, name: EntityName, skip: Int, count: Int, start: Option[Instant], end: Option[Instant], f: (WhiskEntity) => Boolean)(
            implicit entities: Seq[WhiskEntity]) = {
        implicit val tid = transid()
        val result = Await.result(listCollectionByName(datastore, kind, ns, name, skip, count, start, end, convert = None) map { _.left.get map { e => e } }, dbOpTimeout)
        val expected = entities filter { e => f(e) && e.namespace.root == ns } sortBy { case (e: WhiskActivation) => e.start.toEpochMilli; case _ => 0 } map { _.summaryAsJson }
        result.length should be(expected.length)
        result should be(expected reverse)
    }

    it should "query whisk view by namespace, collection and entity name" in {
        implicit val tid = transid()
        val exec = Exec.bb("image")
        val pkgname1 = namespace1.addpath(aname)
        val pkgname2 = namespace2.addpath(aname)
        val actionName = aname
        def now = Instant.now(Clock.systemUTC())

        // creates 17 entities in each namespace as follows:
        // - 2 actions in each namespace in the default namespace
        // - 2 actions in the same package within a namespace
        // - 1 action in two different packages in the same namespace
        // - 1 action in package with prescribed name
        // - 2 triggers in each namespace
        // - 2 rules in each namespace
        // - 2 packages in each namespace
        // - 2 package bindings in each namespace
        // - 2 activations in each namespace (some may have prescribed action name to query by name)
        implicit val entities = Seq(
            WhiskAction(namespace1, aname, exec),
            WhiskAction(namespace1, aname, exec),
            WhiskAction(namespace1.addpath(aname), aname, exec),
            WhiskAction(namespace1.addpath(aname), aname, exec),
            WhiskAction(pkgname1, aname, exec),
            WhiskAction(pkgname1, aname, exec),
            WhiskAction(pkgname1, actionName, exec),
            WhiskTrigger(namespace1, aname),
            WhiskTrigger(namespace1, aname),
            WhiskRule(namespace1, aname, trigger = aname, action = aname),
            WhiskRule(namespace1, aname, trigger = aname, action = aname),
            WhiskPackage(namespace1, aname),
            WhiskPackage(namespace1, aname),
            WhiskPackage(namespace1, aname, Some(Binding(namespace2, aname))),
            WhiskPackage(namespace1, aname, Some(Binding(namespace2, aname))),
            WhiskActivation(namespace1, aname, Subject(), ActivationId(), start = now, end = now),
            WhiskActivation(namespace1, aname, Subject(), ActivationId(), start = now, end = now),

            WhiskAction(namespace2, aname, exec),
            WhiskAction(namespace2, aname, exec),
            WhiskAction(namespace2.addpath(aname), aname, exec),
            WhiskAction(namespace2.addpath(aname), aname, exec),
            WhiskAction(pkgname2, aname, exec),
            WhiskAction(pkgname2, aname, exec),
            WhiskTrigger(namespace2, aname),
            WhiskTrigger(namespace2, aname),
            WhiskRule(namespace2, aname, trigger = aname, action = aname),
            WhiskRule(namespace2, aname, trigger = aname, action = aname),
            WhiskPackage(namespace2, aname),
            WhiskPackage(namespace2, aname),
            WhiskPackage(namespace2, aname, Some(Binding(namespace1, aname))),
            WhiskPackage(namespace2, aname, Some(Binding(namespace1, aname))),
            WhiskActivation(namespace2, aname, Subject(), ActivationId(), start = now, end = now),
            WhiskActivation(namespace2, actionName, Subject(), ActivationId(), start = now, end = now),
            WhiskActivation(namespace2, actionName, Subject(), ActivationId(), start = now, end = now))

        entities foreach { put(datastore, _) }
        waitOnView(datastore, namespace1, entities.length / 2)
        waitOnView(datastore, namespace2, entities.length / 2)

        getAllInNamespace(namespace1)
        getKindInNamespace(namespace1, "actions", { case (e: WhiskAction) => true case (_) => false })
        getKindInNamespace(namespace1, "triggers", { case (e: WhiskTrigger) => true case (_) => false })
        getKindInNamespace(namespace1, "rules", { case (e: WhiskRule) => true case (_) => false })
        getKindInNamespace(namespace1, "packages", { case (e: WhiskPackage) => true case (_) => false })
        getKindInNamespace(namespace1, "activations", { case (e: WhiskActivation) => true case (_) => false })
        getKindInPackage(pkgname1, "actions", { case (e: WhiskAction) => true case (_) => false })
        getKindInNamespaceByName(pkgname1, "actions", actionName, { case (e: WhiskAction) => (e.name == actionName) case (_) => false })
        getEntitiesInNamespace(namespace1)

        getAllInNamespace(namespace2)
        getKindInNamespace(namespace2, "actions", { case (e: WhiskAction) => true case (_) => false })
        getKindInNamespace(namespace2, "triggers", { case (e: WhiskTrigger) => true case (_) => false })
        getKindInNamespace(namespace2, "rules", { case (e: WhiskRule) => true case (_) => false })
        getKindInNamespace(namespace2, "packages", { case (e: WhiskPackage) => true case (_) => false })
        getKindInNamespace(namespace2, "activations", { case (e: WhiskActivation) => true case (_) => false })
        getKindInPackage(pkgname2, "actions", { case (e: WhiskAction) => true case (_) => false })
        getKindInNamespaceByName(namespace2, "activations", actionName, { case (e: WhiskActivation) => (e.name == actionName) case (_) => false })
        getEntitiesInNamespace(namespace2)
    }

    it should "query whisk view for activations sorted by date" in {
        implicit val tid = transid()
        val actionName = aname
        val now = Instant.now(Clock.systemUTC())
        val others = Seq(
            WhiskAction(namespace1, aname, Exec.js("??")),
            WhiskAction(namespace1, aname, Exec.js("??")))
        implicit val entities = Seq(
            WhiskActivation(namespace1, actionName, Subject(), ActivationId(), start = now, end = now),
            WhiskActivation(namespace1, actionName, Subject(), ActivationId(), start = now.plusSeconds(20), end = now.plusSeconds(20)),
            WhiskActivation(namespace1, actionName, Subject(), ActivationId(), start = now.plusSeconds(10), end = now.plusSeconds(20)),
            WhiskActivation(namespace1, actionName, Subject(), ActivationId(), start = now.plusSeconds(40), end = now.plusSeconds(20)),
            WhiskActivation(namespace1, actionName, Subject(), ActivationId(), start = now.plusSeconds(30), end = now.plusSeconds(20)))

        others foreach { put(datastore, _) }
        entities foreach { put(datastore, _) }
        waitOnView(datastore, namespace1, others.length + entities.length)

        getKindInNamespaceByNameSortedByDate(namespace1, "activations", actionName, 0, 5, None, None, {
            case (e: WhiskActivation) => e.name == actionName
            case (_)                  => false
        })

        val since = entities(1).start
        val upto = entities(4).start
        getKindInNamespaceByNameSortedByDate(namespace1, "activations", actionName, 0, 5, Some(since), Some(upto), {
            case (e: WhiskActivation) => e.name == actionName && (e.start.equals(since) || e.start.equals(upto) || (e.start.isAfter(since) && e.start.isBefore(upto)))
            case (_)                  => false
        })
    }

    it should "query whisk and retrieve full documents" in {
        implicit val tid = transid()
        val actionName = aname
        val now = Instant.now(Clock.systemUTC())
        implicit val entities = Seq(
            WhiskAction(namespace1, aname, Exec.js("??")),
            WhiskAction(namespace1, aname, Exec.js("??")))

        entities foreach { put(datastore, _) }
        waitOnView(datastore, namespace1, entities.length)
        getKindInNamespaceWithDoc[WhiskAction](namespace1, "actions", {
            case (e: WhiskAction) => true case (_) => false
        }, Some {
            case (o: JsObject) => Try { WhiskAction.serdes.read(o) }
        })
    }

    it should "query whisk for public packages in all namespaces" in {
        implicit val tid = transid()
        implicit val entities = Seq(
            WhiskPackage(namespace1, aname, publish = true),
            WhiskPackage(namespace1, aname, publish = false),
            WhiskPackage(namespace1, aname, Some(Binding(namespace2, aname)), publish = true),
            WhiskPackage(namespace1, aname, Some(Binding(namespace2, aname))),

            WhiskPackage(namespace2, aname, publish = true),
            WhiskPackage(namespace2, aname, publish = false),
            WhiskPackage(namespace2, aname, Some(Binding(namespace1, aname)), publish = true),
            WhiskPackage(namespace2, aname, Some(Binding(namespace1, aname))))

        entities foreach { put(datastore, _) }
        waitOnView(datastore, namespace1, entities.length / 2)
        waitOnView(datastore, namespace2, entities.length / 2)
        getPublicPackages(entities)
    }
}
