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

import akka.actor.ActorSystem
import scala.util.Try
import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfterAll
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner
import org.scalatest.time.{Minutes, Seconds, Span}
import com.cloudant.client.api.Database
import whisk.common.TransactionCounter
import whisk.common.Verbosity
import whisk.core.WhiskConfig
import whisk.core.WhiskConfig._
import whisk.core.database.ArtifactStore
import whisk.core.entity._
import whisk.core.entity.schema._
import spray.json.JsObject
import spray.json.DefaultJsonProtocol

/**
 *  Tests to ensure our database records conform to the intended schema
 */
@RunWith(classOf[JUnitRunner])
class ConformanceTests extends FlatSpec
    with Matchers
    with BeforeAndAfterAll
    with ScalaFutures
    with DefaultJsonProtocol
    with TransactionCounter {

    implicit val actorSystem = ActorSystem()

    implicit val defaultPatience =
        PatienceConfig(timeout = Span(1, Minutes), interval = Span(1, Seconds))


    // Properties for WhiskAuthStore and WhiskEntityStore.
    val config = new WhiskConfig(Map(
        dbProvider -> null,
        dbProtocol -> null,
        dbUsername -> null,
        dbPassword -> null,
        dbHost -> null,
        dbPort -> null,
        dbAuths -> null,
        dbWhisk -> null))
    assert(config.isValid)

    val datastore: ArtifactStore[EntityRecord, WhiskEntity] = WhiskEntityStore.datastore(config)
    datastore.setVerbosity(Verbosity.Loud)

    val authstore: ArtifactStore[AuthRecord, WhiskAuth] = WhiskAuthStore.datastore(config)
    authstore.setVerbosity(Verbosity.Loud)

    override def afterAll() {
        println("Shutting down store connections")
        datastore.shutdown()
        authstore.shutdown()
        println("Shutting down actor system")
        actorSystem.shutdown()
    }

    /**
     * Helper functions: if d represents a document from the database,
     * is it a design doc, whisk record or whisk activation?
     */
    def isDesignDoc(d: JsObject) = Try(d.fields("_id").convertTo[String].startsWith("_design")).getOrElse(false)
    def isAction(m: JsObject) = m.fields.isDefinedAt("exec")
    def isRule(m: JsObject) = m.fields.isDefinedAt("trigger")
    def isTrigger(m: JsObject) = !isAction(m) && m.fields.isDefinedAt("parameters") && !m.fields.isDefinedAt("binding")
    def isPackage(m: JsObject) =  m.fields.isDefinedAt("binding")
    def isActivation(m: JsObject) = m.fields.isDefinedAt("activationId")

    /**
     * Check that all records in the database each have the required fields
     */
    def checkDatabaseFields[T,U,K](store: ArtifactStore[T,U], viewName: String, klass: Class[K], filter: JsObject=>Boolean, optional: Set[String]=Set.empty) = {
        implicit val tid = transid()

        val futureDocs = store.query(viewName, Nil, Nil, 0, 0, true, false, false)
        val requiredFields = klass.getDeclaredFields.map(_.getName)

        whenReady(futureDocs) { docs =>
            for(doc <- docs if !isDesignDoc(doc) && filter(doc)) {
                for(field <- requiredFields if !optional(field)) {
                    assert(doc.fields.isDefinedAt(field), s"did not find field '$field' in database record $doc expected to be of class '${klass.getCanonicalName}'")
                }
            }
        }
    }

    "Auth Database" should "conform to expected schema" in {
        checkDatabaseFields(authstore, "subjects/uuids", classOf[AuthRecord], _ => true)
    }

    "Whisk Database" should "conform to expected schema" in {
        checkDatabaseFields(datastore, "whisks/all", classOf[ActionRecord], isAction)

        checkDatabaseFields(datastore, "whisks/all", classOf[TriggerRecord], isTrigger)

        checkDatabaseFields(datastore, "whisks/all", classOf[RuleRecord], isRule)

        checkDatabaseFields(datastore, "whisks/all", classOf[ActivationRecord], isActivation, optional=Set("cause"))

        checkDatabaseFields(datastore, "whisks/all", classOf[PackageRecord], isPackage)
    }
}
