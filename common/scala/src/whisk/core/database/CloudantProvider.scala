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

package whisk.core.database

import com.cloudant.client.api.CloudantClient
import com.cloudant.client.api.{ Database => CloudantDatabase }
import com.cloudant.client.api.model.{ Response => CloudantResponse }
import com.cloudant.client.api.{ View => CloudantView }

import whisk.core.entity.DocInfo
import whisk.core.entity.DocId
import whisk.core.entity.DocRevision
import whisk.utils.retry

object CloudantProvider extends CouchDbLikeProvider[CloudantView] {
    type Client = CloudantClient
    type Database = CloudantDatabase
    type Response = CloudantResponse

    def mkClient(dbHost: String, dbPort: Int, dbUsername: String, dbPassword: String) = {
        /** Extract account from username if it exists else account == username. */
        val account = {
            val parts = dbUsername.split(":")
            if (parts.length == 2) parts(1) else parts(0)
        }
        retry { new CloudantClient(account, dbUsername.split(":")(0), dbPassword) }
    }

    def getDB(client: Client, dbName: String) : Database = {
        if(dbName != null && dbName.nonEmpty) {
            client.database(dbName, false)
        } else {
            null
        }
    }

    def saveInDB(doc: Document, db: Database) : Response = {
        db.save(doc)
    }

    def findInDB[D](docInfo: DocInfo, db: Database)(implicit manifest: Manifest[D]) : D = {
        val DType = manifest.runtimeClass.asInstanceOf[Class[D]]
        db.find(DType, docInfo.id(), docInfo.rev())
    }

    def allDocsInDB[D](db: Database)(implicit manifest: Manifest[D]) : Seq[D] = {
        import scala.collection.JavaConversions.asScalaBuffer
        val klass = manifest.runtimeClass.asInstanceOf[Class[D]]
        db.view("_all_docs").includeDocs(true).query(klass)
    }

    def updateInDB(doc: Document, db: Database) : Response = {
        db.update(doc)
    }

    def removeFromDB(docInfo: DocInfo, db: Database) : Response = {
        db.remove(docInfo.id(), docInfo.rev())
    }

    def obtainViewFromDB(table: String, db: Database, includeDocs: Boolean, descending: Boolean, reduce: Boolean, inclusiveEnd: Boolean) : CloudantView = {
        db.view(table).includeDocs(includeDocs).descending(descending).reduce(reduce).inclusiveEnd(inclusiveEnd)
    }

    def mkDocInfo(response: Response) : DocInfo = {
        DocInfo(response)
    }

    def describeResponse(response: Response) : String = {
        if (response == null) {
            "undefined"
        } else {
            s"${response.getId}[${response.getRev}] err=${response.getError} reason=${response.getReason}"
        }
    }

    def validateResponse(response: Response) : Boolean = {
        require(response != null && response.getError == null && response.getId != null && response.getRev != null, "response not valid")
        true
    }

    def shutdownClient(client: Client) : Unit = {
        client.shutdown()
    }
}

object CloudantViewProvider extends CouchDbLikeViewProvider[CloudantView] {
    def limitView(view: CloudantView, limit: Int) : CloudantView = view.limit(limit)

    def skipView(view: CloudantView, skip: Int) : CloudantView = view.skip(skip)

    def withStartEndView(view: CloudantView, startKey: List[Any], endKey: List[Any]) : CloudantView = {
        import scala.collection.JavaConverters.seqAsJavaListConverter
        view.startKey(startKey.asJava).endKey(endKey.asJava)
    }

    def queryView[T](view: CloudantView)(implicit manifest: Manifest[T]) : Seq[T] = {
        import scala.collection.JavaConversions.asScalaBuffer
        val klass = manifest.runtimeClass.asInstanceOf[Class[T]]
        view.query(klass)
    }
}
