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

package whisk.core.entity.schema.migration

import scala.collection.JavaConversions.asScalaBuffer
import scala.util.Try

import com.cloudant.client.api.Database
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject

import whisk.core.WhiskConfig
import whisk.core.database.Cloudant
import whisk.core.entity.DocRevision
import whisk.core.entity.WhiskAuth
import whisk.core.entity.schema.AuthRecord

protected[entity] object CloudantReader {

    type Doc = JsonObject

    trait Filter extends (Doc => Boolean) {
        def apply(d: Doc) = !isDesignDoc(d)
        def isDesignDoc(d: Doc): Boolean = d.get("_id").toString().startsWith("_design")
    }

    val gson = new GsonBuilder().create()

    def filterAllDocsAndApply[T, X](
        db: Database,
        tpe: Class[T] = classOf[Doc],
        op: (T => X),
        filter: Filter = new Filter {}): List[Try[X]] = {
        //val docs = db.getAllDocsRequestBuilder.includeDocs(true).build.getResponse.getDocsAs(classOf[Doc]).toList
        val docs = db.view("_all_docs").includeDocs(true).query(classOf[Doc]).toList
        docs filter {
            _ match { case m: Doc => filter(m) case _ => false }
        } map {
            doc => Try(gson.fromJson(doc, tpe)) map { op(_) }
        }
    }

    def migrateAuthTable(from: Database, to: Database): Unit = {
        val records = filterAllDocsAndApply(
            from,
            classOf[AuthRecord],
            (d: AuthRecord) => WhiskAuth(d) flatMap { _.revision[WhiskAuth](DocRevision()).serialize() })

        records
            .map { _ flatten }
            .filter { _ map { x => x.subject.contains("@") } getOrElse false }
            .map { _ get }
            .foreach { x =>
                println("saving", x)
                to.save(x)
            }
    }

    def main(args: Array[String]) {
        // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        // READ MigrationAssistant.md BEFORE RUNNING THIS
        // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

        require(args.length == 2 && args(0).equals("grant"), "permission required")

        val dbUsername = null
        val dbPassword = null

        val cloudant = new Cloudant(dbUsername, dbPassword)

        val from = cloudant.getDb(args(1))

        filterAllDocsAndApply(from, op = println, filter = new Filter {})
    }
}
