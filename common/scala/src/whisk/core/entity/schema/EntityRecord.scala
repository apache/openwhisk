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

import whisk.core.entity.EntityName
import whisk.core.entity.Namespace
import whisk.core.entity.SemVer
import com.google.gson.JsonArray

/**
 * The most basic whisk entity representation in the database: the record has
 * three properties: name, version, and publish bit which is a proxy for entitlement
 * management (this will change once we have support for management rights).
 *
 * This class must match the datastore schema in terms of the fields defined
 * and must also define a nullary constructor. The imperative nature is forced
 * upon us due to datastore interaction.
 */
protected[entity] abstract class EntityRecord extends Record {

    /** The namesapce for the entity */
    protected[entity] var namespace: String = null

    /** The fully qualified name of the entity (namespace:name) */
    protected[entity] var name: String = null

    /** The semantic version of the entity */
    protected[entity] var version: String = null

    /** The entitlement; currently just a bit to indicate globally shared or not */
    protected[entity] var publish: Boolean = false

    /**
     * The date in epoch millis when the entity was last updated.
     * This is assigned to all instances of this type or its derivatives.
     */
    protected[entity] var updated: Long = 0

    /** The annotations for the entity (includes descriptions, tags, etc.) */
    protected[entity] var annotations: JsonArray = null

    override def toString: String =
        s"""|id: ${_id}
            |rev: ${_rev}
            |namespace: $namespace
            |name: $name
            |version: $version
            |publish: $publish
            |updated: $updated
            |annotations: $annotations""".stripMargin.replace("\n", ", ")
}
