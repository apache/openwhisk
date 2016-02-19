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

import scala.collection.JavaConversions.asScalaBuffer
import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner
import com.cloudant.client.api.Database
import whisk.core.WhiskConfig
import whisk.core.WhiskConfig.dbAuths
import whisk.core.WhiskConfig.dbPassword
import whisk.core.WhiskConfig.dbUsername
import whisk.core.WhiskConfig.dbWhisk
import whisk.core.database.Cloudant
import whisk.core.entity.schema.ActionRecord
import whisk.core.entity.schema.ActivationRecord
import whisk.core.entity.schema.AuthRecord
import whisk.core.entity.schema.PackageRecord
import whisk.core.entity.schema.RuleRecord
import whisk.core.entity.schema.TriggerRecord
import com.google.gson.JsonObject

/**
 *  Tests to ensure our database records conform to the intended schema
 */
@RunWith(classOf[JUnitRunner])
class ConformanceTests extends FlatSpec with Matchers {

    type Doc = JsonObject

    /**
     * Helper functions: if d represents a document from cloudant,
     * is it a design doc, whisk record or whisk activation?
     */
    def isDesignDoc(d: Doc): Boolean = d.get("_id").getAsString.startsWith("_design")
    def isAction(m: Doc): Boolean = m.entrySet().contains("exec")
    def isRule(m: Doc): Boolean =  m.entrySet().contains("trigger")
    def isTrigger(m: Doc): Boolean = !isAction(m) &&  m.entrySet().contains("parameters") && ! m.entrySet().contains("binding")
    def isPackage(m: Doc): Boolean =  m.entrySet().contains("binding")
    def isActivation(m: Doc): Boolean =  m.entrySet().contains("activationId")

    /**
     * Check that all records in the database each have the required fields
     */
    def checkDatabaseFields(db: Database, requiredFields: Array[String], filter: Doc => Boolean = (d: Doc) => true) = {
        //val docs = db.getAllDocsRequestBuilder.includeDocs(true).build.getResponse.getDocsAs(classOf[Doc]).toList
        val docs = db.view("_all_docs").includeDocs(true).query(classOf[Doc]).toList
        docs.map(doc => {
            doc match {
                case m: Doc => if (!isDesignDoc(m) && filter(m)) {
                    requiredFields.map { f =>
                        assert(m.get(f) != null, "did not find field " + f + " in database record: " + m)
                    }
                }
            }
        })
    }

    "Auth Database" should "conform to expected schema" in {
        val config = new WhiskConfig(
            Map(dbUsername -> null,
                dbPassword -> null,
                dbAuths -> null))

        assert(config.isValid)
        val cloudant = new Cloudant(config.dbUsername, config.dbPassword)
        val authDb = cloudant.getDb(config.dbAuths)

        val requiredFields = classOf[AuthRecord].getDeclaredFields.map(f => f.getName)
        checkDatabaseFields(authDb, requiredFields)
    }

    "Whisk Database" should "conform to expected schema" in {
        val config = new WhiskConfig(
            Map(dbUsername -> null,
                dbPassword -> null,
                dbWhisk -> null))

        assert(config.isValid)
        val cloudant = new Cloudant(config.dbUsername, config.dbPassword)
        val whiskDb = cloudant.getDb(config.dbWhisk)

        val requiredActionFields = classOf[ActionRecord].getDeclaredFields.map(f => f.getName)
        checkDatabaseFields(whiskDb, requiredActionFields, isAction)

        val requiredTriggerFields = classOf[TriggerRecord].getDeclaredFields.map(f => f.getName)
        checkDatabaseFields(whiskDb, requiredTriggerFields, isTrigger)

        val requiredRuleFields = classOf[RuleRecord].getDeclaredFields.map(f => f.getName)
        checkDatabaseFields(whiskDb, requiredRuleFields, isRule)

        val requiredActivationFields = classOf[ActivationRecord].getDeclaredFields.map(f => f.getName)
        checkDatabaseFields(whiskDb, requiredActivationFields, isActivation)
        
        val requiredPackageFields = classOf[PackageRecord].getDeclaredFields.map(f => f.getName)
        checkDatabaseFields(whiskDb, requiredPackageFields, isPackage)
    }
}
