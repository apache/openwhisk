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

package whisk.core.entity.schema

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * The whisk activation representation in the database: it extends EntityRecord with
 * several properties that correspond to an action activation.
 *
 * This class must match the datastore schema in terms of the fields defined
 * and must also define a nullary constructor. The imperative nature is forced
 * upon us due to datastore interaction.
 */
protected[entity] class ActivationRecord extends EntityRecord {

    protected[entity] var subject: String = null
    protected[entity] var activationId: String = null
    protected[entity] var cause: String = null
    protected[entity] var start: Long = 0
    protected[entity] var end: Long = 0
    protected[entity] var response: JsonObject = null
    protected[entity] var logs: JsonArray = null

    override def toString: String =
        s"""|id: ${_id}
            |rev: ${_rev}
            |name: $name
            |subject: $subject
            |version: $version
            |annotations: $annotations
            |activationId: $activationId
            |cause: $cause
            |start: $start
            |end: $end
            |response: $response
            |logs: $logs""".stripMargin.replace("\n", ", ")
}
