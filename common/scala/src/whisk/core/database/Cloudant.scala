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
import com.cloudant.client.api.Database
import whisk.core.entity.DocInfo
import whisk.core.entity.DocId
import whisk.utils.retry

class Cloudant(username: String, password: String) {

    def getAllDbs: java.util.List[String] = client.getAllDbs

    def createDb(name: String): Database = {
        if (name != null && name.nonEmpty) {
            client.createDB(name)
            getDb(name)
        } else null
    }

    def getDb(name: String): Database = {
        if (name != null && name.nonEmpty)
            client.database(name, false)
        else null
    }

    /**
     * Shuts down client connection - all operations on the client will fail after shutdown.
     */
    def shutdown() = client.shutdown()

    /**
     * A method to delete/drop a database and recreate it. It is hidden.
     * Do not make it public to avoid accidentally using it. This is not
     * a reversible operation.
     */
    private def dropAndRecreateDb(name: String): Database = {
        if (name != null && name.nonEmpty) {
            client.deleteDB(name, "delete database")
            createDb(name)
        } else null
    }

    /** Extract account from username if it exists else account == username. */
    private def account = {
        val parts = username.split(":")
        if (parts.length == 2) parts(1) else parts(0)
    }

    private val client = retry { new CloudantClient(account, username.split(":")(0), password) }
}